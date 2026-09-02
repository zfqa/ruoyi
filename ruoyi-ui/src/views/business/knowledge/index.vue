<template>
  <div class="app-container kb-page">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="固定资料" name="sources">
        <el-form :model="queryParams" ref="queryForm" size="small" :inline="true">
          <el-form-item label="资料名称"><el-input v-model="queryParams.sourceName" clearable @keyup.enter.native="getList" /></el-form-item>
          <el-form-item label="类型"><el-select v-model="queryParams.sourceType" clearable><el-option v-for="t in sourceTypes" :key="t" :label="t" :value="t" /></el-select></el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" @click="getList">查询</el-button><el-button @click="resetQuery">重置</el-button></el-form-item>
        </el-form>
        <el-row :gutter="10" class="mb8">
          <el-col :span="1.5"><el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd" v-hasPermi="['business:knowledge:add']">登记资料</el-button></el-col>
        </el-row>
        <el-table v-loading="loading" :data="sourceList">
          <el-table-column label="编码" prop="sourceCode" width="130" />
          <el-table-column label="资料名称" prop="sourceName" min-width="220" show-overflow-tooltip />
          <el-table-column label="类型" prop="sourceType" width="90"><template slot-scope="s"><el-tag size="mini">{{ s.row.sourceType }}</el-tag></template></el-table-column>
          <el-table-column label="当前版本ID" prop="currentVersionId" width="110" align="center" />
          <el-table-column label="使用范围" prop="allowedPurpose" min-width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="100"><template slot-scope="s"><el-tag :type="statusType(s.row.status)" size="mini">{{ statusText(s.row.status) }}</el-tag></template></el-table-column>
          <el-table-column label="启用" width="70"><template slot-scope="s">{{ s.row.enabled === '1' ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="操作" width="245" fixed="right">
            <template slot-scope="s">
              <el-button size="mini" type="text" icon="el-icon-upload2" @click="openIngest(s.row)">入库</el-button>
              <el-button size="mini" type="text" icon="el-icon-time" @click="showVersions(s.row)">版本</el-button>
              <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(s.row)">编辑</el-button>
              <el-button size="mini" type="text" class="danger" @click="handleDelete(s.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <pagination v-show="total>0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />
      </el-tab-pane>

      <el-tab-pane label="知识检索" name="search">
        <el-form :inline="true" size="small" @submit.native.prevent>
          <el-form-item label="检索内容"><el-input v-model="searchForm.q" style="width:420px" placeholder="输入至少2个字符" @keyup.enter.native="doSearch" /></el-form-item>
          <el-form-item label="来源"><el-select v-model="searchForm.sourceType" clearable><el-option v-for="t in sourceTypes" :key="t" :label="t" :value="t" /></el-select></el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" :loading="searching" @click="doSearch">检索</el-button></el-form-item>
        </el-form>
        <el-empty v-if="searched && !searchResults.length" description="没有命中当前有效版本" />
        <el-card v-for="(item,index) in searchResults" :key="item.id" class="result-card" shadow="hover">
          <div slot="header" class="result-head"><span><b>[S{{ index+1 }}]</b> {{ item.sourceName }}</span><el-tag size="mini">{{ item.sourceType }}</el-tag></div>
          <p class="snippet">{{ item.sourceSnippet || item.content }}</p>
          <div class="source-meta">
            <span>版本：{{ item.versionNo }}</span><span v-if="item.pageStart">PDF 第 {{ item.pageStart }} 页</span>
            <span v-if="item.metricId">指标：{{ item.metricId }}</span><a v-if="item.sourceUrl" :href="item.sourceUrl" target="_blank" rel="noopener noreferrer">查看新闻原文</a>
          </div>
          <el-collapse v-if="item.evidenceJson"><el-collapse-item title="查看结构化来源证据"><pre>{{ prettyEvidence(item.evidenceJson) }}</pre></el-collapse-item></el-collapse>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="知识问答" name="qa">
        <el-form size="small" @submit.native.prevent>
          <el-form-item label="问题">
            <el-input v-model="qaForm.question" type="textarea" :rows="3" placeholder="回答只允许使用当前有效版本，并强制附带来源编号" />
          </el-form-item>
          <el-form-item label="限定来源">
            <el-select v-model="qaForm.sourceType" clearable><el-option v-for="t in sourceTypes" :key="t" :label="t" :value="t" /></el-select>
            <el-button type="primary" icon="el-icon-chat-dot-round" :loading="asking" style="margin-left:12px" @click="doAsk">提问</el-button>
          </el-form-item>
        </el-form>
        <el-card v-if="qaResult" shadow="never" class="answer-card">
          <div slot="header"><b>知识库回答</b><span class="model-name">{{ qaResult.model }}</span></div>
          <div class="answer-text">{{ qaResult.answer }}</div>
          <el-divider content-position="left">引用来源</el-divider>
          <div v-for="item in qaResult.citations" :key="item.id" class="citation-row">
            <b>[{{ item.citationLabel }}]</b> {{ item.sourceName }} · {{ item.versionNo }}
            <span v-if="item.pageStart"> · PDF 第 {{ item.pageStart }} 页</span>
            <span v-if="item.metricId"> · 指标 {{ item.metricId }}</span>
            <a v-if="item.sourceUrl" :href="item.sourceUrl" target="_blank" rel="noopener noreferrer"> · 原文链接</a>
            <div class="citation-snippet">{{ item.sourceSnippet }}</div>
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog :title="form.id ? '编辑固定资料' : '登记固定资料'" :visible.sync="editOpen" width="620px">
      <el-form ref="form" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="资料编码" prop="sourceCode"><el-input v-model="form.sourceCode" placeholder="例如 POC-PDF-001" /></el-form-item>
        <el-form-item label="资料名称" prop="sourceName"><el-input v-model="form.sourceName" /></el-form-item>
        <el-form-item label="来源类型" prop="sourceType"><el-radio-group v-model="form.sourceType"><el-radio-button v-for="t in sourceTypes" :key="t" :label="t" /></el-radio-group></el-form-item>
        <el-form-item label="密级"><el-select v-model="form.confidentiality"><el-option label="内部" value="INTERNAL" /><el-option label="公开" value="PUBLIC" /><el-option label="受限" value="RESTRICTED" /></el-select></el-form-item>
        <el-form-item label="允许使用范围" prop="allowedPurpose"><el-input v-model="form.allowedPurpose" type="textarea" placeholder="例如：仅限POC问答和内部分析，不允许外发" /></el-form-item>
        <el-form-item label="允许角色ID"><el-input v-model="form.allowedRoleIds" placeholder="逗号分隔；留空则继承菜单权限" /></el-form-item>
        <el-form-item label="是否启用"><el-switch v-model="form.enabled" active-value="1" inactive-value="0" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="editOpen=false">取消</el-button><el-button type="primary" @click="submitForm">保存</el-button></div>
    </el-dialog>

    <el-dialog title="资料入库" :visible.sync="ingestOpen" width="620px" :close-on-click-modal="false">
      <el-alert v-if="ingestSource" :title="`${ingestSource.sourceName}（${ingestSource.sourceType}）`" type="info" :closable="false" class="mb16" />
      <el-form label-width="100px">
        <el-form-item label="版本号"><el-input v-model="ingestForm.versionNo" placeholder="留空自动生成时间版本" /></el-form-item>
        <template v-if="ingestSource && ingestSource.sourceType==='PDF'">
          <el-form-item label="PDF文件"><el-upload action="#" :auto-upload="false" :limit="1" accept=".pdf,application/pdf" :on-change="onPdfChange" :on-remove="onPdfRemove"><el-button icon="el-icon-document-add">选择PDF</el-button></el-upload></el-form-item>
        </template>
        <template v-else-if="ingestSource && ingestSource.sourceType==='NEWS'">
          <el-form-item label="新闻URL"><el-input v-model="ingestForm.url" placeholder="必须属于后端配置的官网白名单" /></el-form-item>
          <el-form-item label="新闻标题"><el-input v-model="ingestForm.title" /></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="报告ID"><el-input-number v-model="ingestForm.reportId" :min="1" /></el-form-item>
        </template>
        <el-card v-if="currentTask" shadow="never" class="task-card">
          <el-progress :percentage="currentTask.progress || 0" :status="currentTask.status==='3' ? 'exception' : (currentTask.status==='2' ? 'success' : undefined)" />
          <div>{{ currentTask.currentStage }} <span v-if="currentTask.chunkCount">· {{ currentTask.chunkCount }} 个切片</span></div>
          <div v-if="currentTask.errorMessage" class="danger">{{ currentTask.errorMessage }}</div>
        </el-card>
      </el-form>
      <div slot="footer"><el-button @click="closeIngest">关闭</el-button><el-button type="primary" :loading="submitting" :disabled="!!currentTask && ['0','1'].includes(currentTask.status)" @click="submitIngest">开始入库</el-button></div>
    </el-dialog>

    <el-dialog title="版本记录" :visible.sync="versionOpen" width="850px">
      <el-table :data="versions"><el-table-column label="版本" prop="versionNo" /><el-table-column label="原始文件/标题" prop="originalName" min-width="220" /><el-table-column label="页数" prop="pageCount" width="70" /><el-table-column label="切片" prop="chunkCount" width="70" /><el-table-column label="状态" width="90"><template slot-scope="s">{{ statusText(s.row.status) }}</template></el-table-column><el-table-column label="创建时间" prop="createTime" width="170" /></el-table>
    </el-dialog>
  </div>
</template>

<script>
import { listKnowledgeBase, getKnowledgeBase, addKnowledgeBase, updateKnowledgeBase, delKnowledgeBase,
  ingestPdf, ingestNews, ingestReport, getKnowledgeTask, listKnowledgeVersions, searchKnowledge, askKnowledge } from '@/api/business/knowledge/knowledgeBase'

export default {
  name: 'KnowledgeBase',
  data() {
    return {
      activeTab: 'sources', sourceTypes: ['PDF', 'NEWS', 'REPORT'], loading: false, total: 0, sourceList: [],
      queryParams: { pageNum: 1, pageSize: 10, sourceName: undefined, sourceType: undefined },
      editOpen: false, form: {}, rules: { sourceCode: [{ required: true, message: '资料编码不能为空', trigger: 'blur' }], sourceName: [{ required: true, message: '资料名称不能为空', trigger: 'blur' }], sourceType: [{ required: true, message: '请选择类型', trigger: 'change' }], allowedPurpose: [{ required: true, message: '请填写允许使用范围', trigger: 'blur' }] },
      ingestOpen: false, ingestSource: null, ingestForm: {}, pdfFile: null, submitting: false, currentTask: null, poller: null,
      versionOpen: false, versions: [], searchForm: { q: '', sourceType: '' }, searching: false, searched: false, searchResults: [],
      qaForm: { question: '', sourceType: '' }, asking: false, qaResult: null
    }
  },
  created() { this.getList() },
  beforeDestroy() { this.stopPolling() },
  methods: {
    getList() { this.loading = true; listKnowledgeBase(this.queryParams).then(r => { this.sourceList = r.rows; this.total = r.total }).finally(() => { this.loading = false }) },
    resetQuery() { this.queryParams = { pageNum: 1, pageSize: 10, sourceName: undefined, sourceType: undefined }; this.getList() },
    resetFormData() { this.form = { id: undefined, sourceCode: '', sourceName: '', sourceType: 'PDF', confidentiality: 'INTERNAL', allowedPurpose: '', allowedRoleIds: '', enabled: '1', status: '0', remark: '' } },
    handleAdd() { this.resetFormData(); this.editOpen = true },
    handleUpdate(row) { getKnowledgeBase(row.id).then(r => { this.form = r.data; this.editOpen = true }) },
    submitForm() { this.$refs.form.validate(valid => { if (!valid) return; const action = this.form.id ? updateKnowledgeBase : addKnowledgeBase; action(this.form).then(() => { this.$modal.msgSuccess('保存成功'); this.editOpen = false; this.getList() }) }) },
    handleDelete(row) { this.$modal.confirm(`确认删除“${row.sourceName}”吗？`).then(() => delKnowledgeBase(row.id)).then(() => { this.$modal.msgSuccess('删除成功'); this.getList() }).catch(() => {}) },
    openIngest(row) { this.stopPolling(); this.ingestSource = row; this.ingestForm = { versionNo: '', url: '', title: '', reportId: undefined }; this.pdfFile = null; this.currentTask = null; this.ingestOpen = true },
    closeIngest() { this.ingestOpen = false; if (!this.currentTask || !['0','1'].includes(this.currentTask.status)) this.stopPolling() },
    onPdfChange(file) { this.pdfFile = file.raw }, onPdfRemove() { this.pdfFile = null },
    submitIngest() {
      if (!this.ingestSource) return
      let request
      if (this.ingestSource.sourceType === 'PDF') { if (!this.pdfFile) return this.$modal.msgError('请选择PDF文件'); request = ingestPdf(this.ingestSource.id, this.ingestForm.versionNo, this.pdfFile) }
      else if (this.ingestSource.sourceType === 'NEWS') { if (!this.ingestForm.url) return this.$modal.msgError('请输入新闻URL'); request = ingestNews({ sourceId: this.ingestSource.id, ...this.ingestForm }) }
      else { if (!this.ingestForm.reportId) return this.$modal.msgError('请输入报告ID'); request = ingestReport({ sourceId: this.ingestSource.id, ...this.ingestForm }) }
      this.submitting = true
      request.then(r => { this.currentTask = r.data; this.startPolling(r.data.id); this.$modal.msgSuccess('入库任务已提交') }).finally(() => { this.submitting = false })
    },
    startPolling(id) { this.stopPolling(); const tick = () => getKnowledgeTask(id).then(r => { this.currentTask = r.data; if (['2','3'].includes(r.data.status)) { this.stopPolling(); this.getList() } }); tick(); this.poller = setInterval(tick, 2000) },
    stopPolling() { if (this.poller) clearInterval(this.poller); this.poller = null },
    showVersions(row) { listKnowledgeVersions(row.id).then(r => { this.versions = r.data || []; this.versionOpen = true }) },
    doSearch() { if (!this.searchForm.q || this.searchForm.q.trim().length < 2) return this.$modal.msgError('检索词至少2个字符'); this.searching = true; this.searched = true; searchKnowledge({ ...this.searchForm, limit: 20 }).then(r => { this.searchResults = r.data || [] }).finally(() => { this.searching = false }) },
    doAsk() { if (!this.qaForm.question || this.qaForm.question.trim().length < 2) return this.$modal.msgError('问题至少2个字符'); this.asking = true; this.qaResult = null; askKnowledge(this.qaForm).then(r => { this.qaResult = r.data }).finally(() => { this.asking = false }) },
    statusText(s) { return ({ '0': '待处理', '1': '处理中', '2': '成功', '3': '失败' })[s] || s },
    statusType(s) { return ({ '0': 'info', '1': 'warning', '2': 'success', '3': 'danger' })[s] || 'info' },
    prettyEvidence(value) { try { return JSON.stringify(JSON.parse(value), null, 2) } catch (e) { return value } }
  }
}
</script>

<style scoped>
.mb16 { margin-bottom: 16px; }.danger { color: #f56c6c; }.result-card { margin-bottom: 14px; }.result-head { display:flex; justify-content:space-between; align-items:center; }.snippet { line-height:1.75; white-space:pre-wrap; }.source-meta { display:flex; flex-wrap:wrap; gap:18px; color:#8492a6; font-size:13px; }.task-card { margin-top:16px; line-height:2; } pre { white-space:pre-wrap; max-height:260px; overflow:auto; }.answer-card { margin-top:18px; }.answer-text { line-height:1.9; white-space:pre-wrap; }.model-name { float:right; color:#909399; font-size:12px; }.citation-row { padding:10px 0; border-bottom:1px solid #ebeef5; line-height:1.7; }.citation-snippet { color:#909399; font-size:13px; }
</style>
