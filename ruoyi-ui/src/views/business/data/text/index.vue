<template>
  <div class="app-container">
    <el-card shadow="never" class="extract-card">
      <div slot="header" class="card-header">
        <span><i class="el-icon-magic-stick" /> 自由文本 LLM 实体抽取</span>
        <el-tag type="info" size="small">LLM识别 + Java单位归一</el-tag>
      </div>
      <el-alert title="粘贴行业文字、数字或从Excel复制的表格片段；系统识别企业、车型、销量/出货量、尺寸和技术路线，并保留原文证据。" type="info" :closable="false" class="mb16" />
      <el-form label-width="90px">
        <el-form-item label="任务名称"><el-input v-model="extractForm.taskName" maxlength="200" placeholder="可选，例如：2025 Q1-Q3车载显示市场摘录" /></el-form-item>
        <el-form-item label="待解析文本">
          <el-input v-model="extractForm.sourceText" type="textarea" :rows="11" maxlength="30000" show-word-limit resize="vertical"
            placeholder="示例：2025年Q1-Q3，Tianma LTPS车载显示出货量为1.2 Mpcs，主要应用于12.3英寸车型A。&#10;&#10;也可直接粘贴表格：&#10;企业    车型    技术    尺寸    销量&#10;Tianma  车型A   LTPS   12.3英寸  1.2 Mpcs" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-cpu" :loading="submitting" @click="submitExtraction" v-hasPermi="['business:data:text:add']">开始智能抽取</el-button>
          <el-button @click="extractForm.sourceText=''">清空文本</el-button>
        </el-form-item>
      </el-form>
      <div v-if="activeTask && ['0','1'].includes(activeTask.status)" class="task-progress">
        <el-progress :percentage="displayProgress" :show-text="false" />
        <div><i class="el-icon-loading" /> {{ activeTask.remark || '任务处理中' }}</div>
        <div class="progress-hint">已等待 {{ elapsedSeconds }} 秒。LLM接口不返回真实百分比，进度条仅表示任务仍在运行，最长等待约300秒。</div>
      </div>
    </el-card>

    <el-card v-if="result" shadow="never" class="result-card">
      <div slot="header" class="card-header"><span>结构化结果（{{ result.entityCount || 0 }} 个实体，{{ result.recordCount || 0 }} 条对应记录）</span><span class="model-name">{{ result.model }}</span></div>
      <div v-if="result.summary" class="summary-block">
        <div v-for="(values, type) in result.summary" :key="type" class="summary-row"><b>{{ typeLabel(type) }}：</b><el-tag v-for="value in values" :key="value" size="small">{{ value }}</el-tag></div>
      </div>
      <el-divider content-position="left">实体对应关系</el-divider>
      <el-table v-if="result.records && result.records.length" :data="result.records" border stripe>
        <el-table-column label="供应商" prop="supplier" min-width="110" />
        <el-table-column label="汽车客户" prop="customer" min-width="110" />
        <el-table-column label="车型" prop="vehicleModel" min-width="130" />
        <el-table-column label="技术路线" prop="technology" min-width="110" />
        <el-table-column label="尺寸（归一）" prop="size" min-width="120" />
        <el-table-column label="销量/出货量（归一）" prop="sales" min-width="160" />
        <el-table-column label="对应原文证据" prop="evidence" min-width="320" show-overflow-tooltip />
        <el-table-column label="置信度" width="90"><template slot-scope="s">{{ formatConfidence(s.row.confidence) }}</template></el-table-column>
      </el-table>
      <el-alert v-else title="未形成可由连续原文证明的对应记录；下方仍展示独立实体。" type="warning" :closable="false" class="mb16" />
      <el-divider content-position="left">独立实体</el-divider>
      <el-table :data="result.entities || []" border stripe>
        <el-table-column label="实体类型" width="120"><template slot-scope="s"><el-tag :type="entityTagType(s.row.type)" size="small">{{ s.row.typeLabel }}</el-tag></template></el-table-column>
        <el-table-column label="企业角色" width="100"><template slot-scope="s">{{ s.row.type === 'COMPANY' ? (s.row.roleLabel || '未区分') : '--' }}</template></el-table-column>
        <el-table-column label="原文值" prop="rawValue" min-width="130" />
        <el-table-column label="标准化值" prop="normalizedValue" min-width="150" />
        <el-table-column label="数值" prop="numericValue" width="120" />
        <el-table-column label="标准单位" prop="canonicalUnit" width="100" />
        <el-table-column label="置信度" width="100"><template slot-scope="s">{{ formatConfidence(s.row.confidence) }}</template></el-table-column>
        <el-table-column label="原文证据" prop="evidence" min-width="300" show-overflow-tooltip />
      </el-table>
      <el-alert v-if="result.warnings && result.warnings.length" :title="result.warnings.join('；')" type="warning" :closable="false" class="mt16" />
    </el-card>

    <el-divider content-position="left">历史任务</el-divider>
    <el-form v-show="showSearch" :model="queryParams" ref="queryForm" size="small" :inline="true">
      <el-form-item label="任务名称" prop="taskName"><el-input v-model="queryParams.taskName" clearable @keyup.enter.native="handleQuery" /></el-form-item>
      <el-form-item label="状态" prop="status"><el-select v-model="queryParams.status" clearable style="width:120px"><el-option label="处理中" value="1" /><el-option label="成功" value="2" /><el-option label="失败" value="3" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" icon="el-icon-search" @click="handleQuery">搜索</el-button><el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button></el-form-item>
    </el-form>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="danger" plain icon="el-icon-delete" size="mini" :disabled="!ids.length" @click="handleDelete()" v-hasPermi="['business:data:text:remove']">删除</el-button></el-col>
      <el-col :span="1.5"><el-button type="warning" plain icon="el-icon-download" size="mini" @click="handleExport" v-hasPermi="['business:data:text:export']">导出</el-button></el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList" />
    </el-row>
    <el-table v-loading="loading" :data="textStructList" @selection-change="selection => ids=selection.map(item => item.id)">
      <el-table-column type="selection" width="50" />
      <el-table-column label="任务名称" prop="taskName" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" width="100"><template slot-scope="s"><el-tag :type="statusType(s.row.status)" size="small">{{ statusText(s.row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="实体数量" prop="entityCount" width="100" />
      <el-table-column label="模型" prop="llmModel" min-width="180" show-overflow-tooltip />
      <el-table-column label="说明" prop="remark" min-width="240" show-overflow-tooltip />
      <el-table-column label="创建时间" prop="createTime" width="170"><template slot-scope="s">{{ parseTime(s.row.createTime) }}</template></el-table-column>
      <el-table-column label="操作" width="140" fixed="right"><template slot-scope="s"><el-button type="text" icon="el-icon-view" @click="showResult(s.row)">查看</el-button><el-button type="text" class="danger" icon="el-icon-delete" @click="handleDelete(s.row)" v-hasPermi="['business:data:text:remove']">删除</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total>0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <el-dialog title="文本结构化任务详情" :visible.sync="detailOpen" width="900px" append-to-body>
      <div v-if="detailTask">
        <el-descriptions :column="2" border><el-descriptions-item label="任务名称">{{ detailTask.taskName }}</el-descriptions-item><el-descriptions-item label="状态">{{ statusText(detailTask.status) }}</el-descriptions-item><el-descriptions-item label="实体数量">{{ detailTask.entityCount || 0 }}</el-descriptions-item><el-descriptions-item label="模型">{{ detailTask.llmModel || '--' }}</el-descriptions-item></el-descriptions>
        <h4>原始文本</h4><pre class="source-text">{{ detailTask.sourceText }}</pre>
        <h4>标准化结果</h4><pre class="json-result">{{ prettyJson(detailTask.resultJson) }}</pre>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listTextStruct, getTextStruct, delTextStruct, extractTextStruct } from '@/api/business/data/text/textStruct'

export default {
  name: 'TextStruct',
  data() {
    return {
      loading: false, submitting: false, showSearch: true, ids: [], total: 0, textStructList: [],
      extractForm: { taskName: '', sourceText: '' }, activeTask: null, result: null, poller: null, pollCount: 0,
      polling: false, taskStartedAt: 0, elapsedSeconds: 0,
      queryParams: { pageNum: 1, pageSize: 10, taskName: undefined, status: undefined }, detailOpen: false, detailTask: null
    }
  },
  created() { this.getList() },
  beforeDestroy() { this.stopPolling() },
  computed: {
    displayProgress() {
      if (!this.activeTask) return 0
      if (this.activeTask.status === '0') return 15
      if (this.activeTask.status !== '1') return 100
      return Math.min(92, Math.round(35 + 55 * (1 - Math.exp(-this.elapsedSeconds / 100))))
    }
  },
  methods: {
    getList() { this.loading = true; listTextStruct(this.queryParams).then(r => { this.textStructList = r.rows; this.total = r.total }).finally(() => { this.loading = false }) },
    submitExtraction() {
      if (!this.extractForm.sourceText || this.extractForm.sourceText.trim().length < 2) return this.$modal.msgError('请粘贴至少2个字符的待解析文本')
      this.submitting = true; this.result = null; this.stopPolling()
      extractTextStruct(this.extractForm).then(r => {
        this.activeTask = r.data; this.pollCount = 0; this.elapsedSeconds = 0; this.taskStartedAt = Date.now()
        this.pollTask(); this.poller = setInterval(this.pollTask, 1500); this.$modal.msgSuccess('实体抽取任务已提交')
      }).finally(() => { this.submitting = false })
    },
    pollTask() {
      if (!this.activeTask || this.polling) return
      this.pollCount++; this.elapsedSeconds = Math.max(0, Math.round((Date.now() - this.taskStartedAt) / 1000))
      if (this.pollCount > 220) { this.stopPolling(); return this.$modal.msgWarning('任务仍在处理，请稍后从历史任务查看') }
      this.polling = true
      getTextStruct(this.activeTask.id).then(r => {
        this.activeTask = r.data
        if (r.data.status === '2') { this.result = this.parseResult(r.data.resultJson); this.stopPolling(); this.getList(); this.$modal.msgSuccess(`抽取完成，共识别 ${r.data.entityCount || 0} 个实体`) }
        else if (r.data.status === '3') { this.stopPolling(); this.getList(); this.$modal.msgError(r.data.remark || 'LLM实体抽取失败') }
      }).finally(() => { this.polling = false })
    },
    stopPolling() { if (this.poller) clearInterval(this.poller); this.poller = null },
    showResult(row) { getTextStruct(row.id).then(r => { this.detailTask = r.data; this.detailOpen = true; if (r.data.status === '2') this.result = this.parseResult(r.data.resultJson) }) },
    parseResult(value) { try { return typeof value === 'string' ? JSON.parse(value) : value } catch (e) { this.$modal.msgError('结构化结果JSON损坏'); return null } },
    prettyJson(value) { try { return JSON.stringify(typeof value === 'string' ? JSON.parse(value) : value, null, 2) } catch (e) { return value || '--' } },
    handleQuery() { this.queryParams.pageNum = 1; this.getList() }, resetQuery() { this.resetForm('queryForm'); this.handleQuery() },
    handleDelete(row) { const targets = row ? [row.id] : this.ids; this.$modal.confirm(`确认删除任务 ${targets.join(',')} 吗？`).then(() => delTextStruct(targets.join(','))).then(() => { this.$modal.msgSuccess('删除成功'); this.getList() }).catch(() => {}) },
    handleExport() { this.download('/business/data/text/export', { ...this.queryParams }, `文本结构化_${Date.now()}.xlsx`) },
    statusText(status) { return ({ '0': '待处理', '1': '处理中', '2': '成功', '3': '失败' })[status] || status },
    statusType(status) { return ({ '0': 'info', '1': 'warning', '2': 'success', '3': 'danger' })[status] || 'info' },
    typeLabel(type) { return ({ COMPANY: '企业', VEHICLE_MODEL: '车型', SALES: '销量/出货量', SIZE: '尺寸', TECHNOLOGY: '技术路线' })[type] || type },
    entityTagType(type) { return ({ COMPANY: 'primary', VEHICLE_MODEL: 'success', SALES: 'warning', SIZE: 'info', TECHNOLOGY: 'danger' })[type] || 'info' },
    formatConfidence(value) { return value == null ? '--' : `${Math.round(Number(value) * 100)}%` }
  }
}
</script>

<style scoped>
.extract-card,.result-card{margin-bottom:18px}.card-header{display:flex;justify-content:space-between;align-items:center;font-weight:600}.mb16{margin-bottom:16px}.mt16{margin-top:16px}.task-progress{padding:14px 24px;background:#f7f9fc;line-height:2}.progress-hint{color:#909399;font-size:12px}.summary-block{margin-bottom:16px}.summary-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:8px 0}.model-name{font-size:12px;color:#909399;font-weight:400}.danger{color:#f56c6c}.source-text,.json-result{white-space:pre-wrap;max-height:260px;overflow:auto;padding:14px;background:#f7f9fc;border-radius:4px;line-height:1.7}.json-result{max-height:380px}
</style>
