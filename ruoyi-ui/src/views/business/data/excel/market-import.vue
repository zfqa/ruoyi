<template>
  <div class="market-import">
    <el-alert
      title="上传Excel或CSV文件，系统将自动识别工作表、表头、业务指标和分析维度，并转换为标准字段。"
      type="info"
      show-icon
      :closable="false"
      class="mb16"
    />

    <el-card shadow="never" class="upload-card">
      <div slot="header" class="card-head">
        <div>
          <div class="card-title">上传Excel/CSV文件</div>
          <div class="card-subtitle">Excel导入只负责解析、字段识别、数据预览与质量检查，不会自动执行整车市场分析。</div>
        </div>
        <el-tag :type="serviceOnline ? 'success' : 'danger'" size="small">
          {{ serviceOnline ? '解析服务正常' : '解析服务未启动' }}
        </el-tag>
      </div>

      <el-steps :active="uploadStepActive" finish-status="success" simple class="parse-steps">
        <el-step title="选择文件" icon="el-icon-document-add" />
        <el-step title="上传并解析" icon="el-icon-cpu" />
        <el-step title="查看解析结果" icon="el-icon-data-analysis" />
      </el-steps>

      <el-upload
        ref="upload"
        drag
        multiple
        action="#"
        :auto-upload="false"
        :disabled="uploading"
        :file-list="fileList"
        :on-change="onFileChange"
        :on-remove="onFileRemove"
        accept=".xlsx,.xlsm,.csv"
        class="upload-area"
      >
        <i class="el-icon-upload" />
        <div class="el-upload__text">将 Excel/CSV 拖到此处，或<em>点击选择</em></div>
        <div slot="tip" class="el-upload__tip">支持 .xlsx、.xlsm、.csv；可一次选择多个文件，单个文件不超过50MB。</div>
      </el-upload>

      <div class="actions">
        <el-button type="primary" icon="el-icon-cpu" :loading="uploading" :disabled="!fileList.length" @click="submit">
          {{ uploading ? '正在解析，请稍候' : '上传并解析' }}
        </el-button>
        <el-button icon="el-icon-delete" :disabled="uploading || (!fileList.length && !result)" @click="clearFiles">清空</el-button>
        <el-button v-if="job && ['queued','processing'].includes(job.status)" type="danger" plain @click="cancelJob">取消解析</el-button>
        <el-button v-if="job && ['failed','cancelled'].includes(job.status)" type="warning" plain @click="retryJob">重新解析</el-button>
      </div>

      <div v-if="job && !result" class="job-progress">
        <div class="progress-head">
          <span class="progress-title">{{ jobStatusText }}</span>
          <span class="progress-percent">{{ Number(job.progress || 0) }}%</span>
        </div>
        <el-progress :percentage="Number(job.progress || 0)" :status="jobProgressStatus" :show-text="false" />
        <div class="progress-message">
          <el-tag size="mini" :type="jobTagType">{{ jobStatusText }}</el-tag>
          <span>{{ job.message || currentParseStage }}</span>
        </div>
      </div>
    </el-card>

    <template v-if="result">
      <el-alert type="success" show-icon :closable="false" class="result-alert">
        <div slot="title" class="result-alert-content">
          <strong>解析完成</strong>
          <span>{{ completionSummary }}</span>
        </div>
      </el-alert>

      <el-row :gutter="16" class="summary-row">
        <el-col v-for="item in summaryCards" :key="item.label" :xs="12" :sm="12" :md="6">
          <div class="summary-card">
            <div class="summary-icon"><i :class="item.icon" /></div>
            <div>
              <div class="value">{{ formatNumber(item.value) }}</div>
              <div class="label">{{ item.label }}</div>
            </div>
          </div>
        </el-col>
      </el-row>

      <el-card shadow="never" class="result-card">
        <div slot="header" class="result-head">
          <div class="result-file">
            <i class="el-icon-document-checked" />
            <div>
              <strong>{{ currentFileName }}</strong>
              <div class="result-file-note">{{ fieldRecognitionSummary }}；{{ issueSummary }}</div>
            </div>
          </div>
          <div v-if="fileResults.length > 1" class="file-selector">
            <span>查看文件</span>
            <el-select v-model="selectedFile" size="small" @change="onSelectedFileChange">
              <el-option v-for="(file, index) in fileResults" :key="`${index}-${file.file_name}`" :label="file.file_name" :value="index" />
            </el-select>
          </div>
        </div>

        <el-tabs v-model="activeResultTab">
          <el-tab-pane label="数据预览" name="preview">
            <div class="tab-toolbar">
              <div class="toolbar-group">
                <el-select v-if="previewSheetOptions.length > 1" v-model="previewSheet" size="small" placeholder="全部工作表" class="sheet-filter">
                  <el-option label="全部工作表" value="" />
                  <el-option v-for="sheet in previewSheetOptions" :key="sheet" :label="sheet" :value="sheet" />
                </el-select>
                <el-input
                  v-model="previewKeywordInput"
                  size="small"
                  clearable
                  prefix-icon="el-icon-search"
                  placeholder="输入内容后点击查询"
                  class="preview-search"
                  @keyup.enter.native="applyPreviewSearch"
                  @clear="clearPreviewSearch"
                  @input="onPreviewSearchInput"
                />
                <el-button type="primary" size="small" icon="el-icon-search" @click="applyPreviewSearch">查询</el-button>
              </div>
              <div class="toolbar-group toolbar-note">
                <span>当前显示 {{ filteredPreviewRows.length }} 条，接口最多返回40条预览记录</span>
                <el-popover placement="bottom-end" width="320" trigger="click">
                  <div class="column-picker-title">选择预览列</div>
                  <el-checkbox-group v-model="visiblePreviewColumns" class="column-picker">
                    <el-checkbox v-for="column in availablePreviewColumns" :key="column" :label="column">{{ fieldLabel(column) }}</el-checkbox>
                  </el-checkbox-group>
                  <el-button slot="reference" size="small" icon="el-icon-setting">列显示</el-button>
                </el-popover>
              </div>
            </div>
            <el-table :data="filteredPreviewRows" border stripe size="mini" height="420" empty-text="暂无符合条件的预览数据">
              <el-table-column
                v-for="column in selectedPreviewColumns"
                :key="column"
                :prop="column"
                :label="fieldLabel(column)"
                min-width="140"
                show-overflow-tooltip
              >
                <template slot-scope="scope">{{ formatCell(scope.row[column], column) }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane :label="`工作表识别（${sheetRows.length}）`" name="sheets">
            <div class="tab-description">展示每张工作表被识别成了什么；程序使用的校验分数和JSON明细已转为可理解的业务结果。</div>
            <el-table :data="sheetRows" border stripe size="small" empty-text="暂无工作表识别结果">
              <el-table-column prop="sheetName" label="工作表名称" min-width="130" show-overflow-tooltip />
              <el-table-column prop="dataType" label="数据类型" min-width="150" show-overflow-tooltip />
              <el-table-column prop="metric" label="主要指标" min-width="120" show-overflow-tooltip />
              <el-table-column label="已识别维度" min-width="230">
                <template slot-scope="scope">
                  <span v-if="!scope.row.dimensions.length" class="muted">暂未识别</span>
                  <el-tag v-for="dimension in scope.row.dimensions.slice(0, 4)" :key="dimension" size="mini" class="dimension-tag">{{ dimension }}</el-tag>
                  <el-tooltip v-if="scope.row.dimensions.length > 4" :content="scope.row.dimensions.join('、')" placement="top">
                    <el-tag size="mini" type="info">+{{ scope.row.dimensions.length - 4 }}</el-tag>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column prop="dataSize" label="数据量" min-width="150" />
              <el-table-column label="识别结果" width="110" align="center">
                <template slot-scope="scope"><el-tag :type="scope.row.statusType" size="small">{{ scope.row.status }}</el-tag></template>
              </el-table-column>
              <el-table-column label="问题" width="90" align="center">
                <template slot-scope="scope">
                  <span v-if="!scope.row.issueCount" class="success-text">无</span>
                  <el-button v-else type="text" @click="showSheetIssues(scope.row)">{{ scope.row.issueCount }}项</el-button>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right" align="center">
                <template slot-scope="scope"><el-button type="text" @click="openSheetDetail(scope.row)">查看详情</el-button></template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane :label="`质量问题（${issueRows.length}）`" name="issues">
            <div v-if="issueRows.length" class="issue-overview">
              <div class="toolbar-group">
                <span>问题分级：</span>
                <el-tag v-if="issueStats.error" type="danger" size="small">错误 {{ issueStats.error }}</el-tag>
                <el-tag v-if="issueStats.warning" type="warning" size="small">警告 {{ issueStats.warning }}</el-tag>
                <el-tag v-if="issueStats.info" type="info" size="small">提示 {{ issueStats.info }}</el-tag>
              </div>
              <el-select v-if="issueSheetOptions.length" v-model="issueSheetFilter" size="small" placeholder="全部工作表" class="sheet-filter">
                <el-option label="全部工作表" value="" />
                <el-option v-for="sheet in issueSheetOptions" :key="sheet" :label="sheet" :value="sheet" />
              </el-select>
            </div>
            <el-empty v-if="!issueRows.length" description="未发现数据质量问题" />
            <el-empty v-else-if="!displayedIssueRows.length" description="该工作表没有数据质量问题" />
            <el-table v-else :data="displayedIssueRows" border stripe size="small" height="420">
              <el-table-column label="级别" width="90" align="center">
                <template slot-scope="scope"><el-tag :type="scope.row.tagType" size="mini">{{ scope.row.levelText }}</el-tag></template>
              </el-table-column>
              <el-table-column prop="sheet" label="工作表" min-width="120" show-overflow-tooltip />
              <el-table-column prop="position" label="原表位置" min-width="240" show-overflow-tooltip />
              <el-table-column prop="message" label="问题说明" min-width="360" show-overflow-tooltip />
              <el-table-column prop="suggestion" label="处理建议" min-width="220" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </template>

    <el-drawer :visible.sync="sheetDetailVisible" title="工作表识别详情" size="58%" append-to-body>
      <div v-if="selectedSheet" class="sheet-detail">
        <el-descriptions title="基本信息" :column="2" border size="small">
          <el-descriptions-item label="来源文件">{{ selectedSheet.raw.source_file || result.file_name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="工作表">{{ selectedSheet.sheetName }}</el-descriptions-item>
          <el-descriptions-item label="数据类型">{{ selectedSheet.dataType }}</el-descriptions-item>
          <el-descriptions-item label="主要指标">{{ selectedSheet.metric }}</el-descriptions-item>
          <el-descriptions-item label="源数据">{{ formatNumber(selectedSheet.raw.source_data_rows || 0) }} 行 / {{ formatNumber(selectedSheet.raw.physical_columns || 0) }} 列</el-descriptions-item>
          <el-descriptions-item label="标准化结果">{{ formatNumber(selectedSheet.raw.rows_detected || 0) }} 条</el-descriptions-item>
          <el-descriptions-item label="时间范围">{{ periodRange(selectedSheet.raw) }}</el-descriptions-item>
          <el-descriptions-item label="识别结果"><el-tag :type="selectedSheet.statusType" size="small">{{ selectedSheet.status }}</el-tag></el-descriptions-item>
        </el-descriptions>

        <div class="detail-section-title">字段识别与标准化结果</div>
        <el-table :data="fieldMappingRows(selectedSheet.raw)" border stripe size="small" max-height="360">
          <el-table-column prop="original" label="原始字段" min-width="180" show-overflow-tooltip />
          <el-table-column prop="meaning" label="系统识别含义" min-width="160" show-overflow-tooltip />
          <el-table-column prop="standard" label="标准字段" min-width="180" show-overflow-tooltip />
          <el-table-column label="识别状态" width="110" align="center">
            <template slot-scope="scope"><el-tag :type="scope.row.mapped ? 'success' : 'warning'" size="mini">{{ scope.row.mapped ? '已识别' : '需要确认' }}</el-tag></template>
          </el-table-column>
        </el-table>

        <div class="recognition-notes">
          <strong>识别说明</strong>
          <ul>
            <li>识别出主要指标：{{ selectedSheet.metric }}</li>
            <li>识别出 {{ selectedSheet.dimensions.length }} 个业务维度：{{ selectedSheet.dimensions.join('、') || '暂无' }}</li>
            <li>{{ mappedFieldSummary(selectedSheet.raw) }}</li>
            <li>该工作表发现 {{ selectedSheet.issueCount }} 项质量问题。</li>
          </ul>
        </div>

        <el-collapse v-if="(selectedSheet.raw.semantic_evidence || []).length" class="evidence-collapse">
          <el-collapse-item title="查看系统识别依据" name="evidence">
            <ul><li v-for="(evidence, index) in selectedSheet.raw.semantic_evidence" :key="index">{{ evidence }}</li></ul>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-drawer>
  </div>
</template>

<script>
import { marketHealth, createExcelParseJob, getExcelParseJob, cancelExcelParseJob, retryExcelParseJob } from '@/api/business/market/marketAgent'

const FIELD_LABELS = {
  time_period: '时间/月份', region: '国家/地区', market: '市场', vehicle_type: '车辆类型', oem: '整车厂/OEM',
  brand: '品牌', model: '车型', power_type: '动力类型', size_class: '尺寸级别', tech_route: '技术路线',
  production: '产量', sales: '销量', retail_sales: '零售销量', wholesale: '批发销量', domestic_sales: '国内销量',
  domestic_wholesale: '国内批发销量', export: '出口', inventory: '库存量', manufacturer: '生产企业', origin_type: '国产/进口',
  yoy_production: '产量同比', yoy_sales: '销量同比', yoy_retail_sales: '零售销量同比', yoy_wholesale: '批发销量同比',
  yoy_domestic_sales: '国内销量同比', yoy_domestic_wholesale: '国内批发销量同比', yoy_export: '出口同比',
  source_file: '来源文件', source_sheet: '来源工作表', source_row: '来源行号', source_metric_label: '源指标名称'
}

const DIMENSION_FIELDS = ['region', 'market', 'vehicle_type', 'oem', 'brand', 'model', 'power_type', 'size_class', 'tech_route', 'manufacturer', 'origin_type']
const TECHNICAL_PREVIEW_FIELDS = [
  'source_metric_type', 'source_metric_scope', 'source_dimension', 'source_table_type', 'source_parser_id',
  'source_semantic_confidence', 'source_row_kind', 'source_sheet_name'
]

export default {
  name: 'MarketImport',
  data() {
    return {
      fileList: [], uploading: false, serviceOnline: false, result: null, job: null, pollTimer: null,
      activeResultTab: 'preview', selectedFile: 0, previewKeywordInput: '', previewKeyword: '', previewSheet: '', visiblePreviewColumns: [],
      issueSheetFilter: '', sheetDetailVisible: false, selectedSheet: null
    }
  },
  computed: {
    fileResults() {
      if (!this.result) return []
      if (Array.isArray(this.result.file_results) && this.result.file_results.length) return this.result.file_results
      return [{
        file_name: this.result.file_name || ((this.result.source_files || [])[0]) || '解析结果',
        rows: this.result.rows,
        columns: this.result.columns || [],
        parse_summary: this.result.parse_summary || {},
        sheet_meta: this.result.sheet_meta || [],
        issues: this.result.issues || [],
        preview: this.result.preview || []
      }]
    },
    currentFileResult() {
      return this.fileResults[Number(this.selectedFile)] || this.fileResults[0] || {}
    },
    currentFileName() { return this.currentFileResult.file_name || '解析结果' },
    parseSummary() { return this.currentFileResult.parse_summary || {} },
    overallParseSummary() { return (this.result && this.result.parse_summary) || {} },
    summaryCards() {
      const s = this.parseSummary
      return [
        { label: '文件', value: s.file_count || (this.result && (this.result.source_files || []).length) || 0, icon: 'el-icon-document' },
        { label: '工作表', value: s.sheet_count || this.sheetRows.length, icon: 'el-icon-folder-opened' },
        { label: '有效源数据', value: s.source_data_rows_total || 0, icon: 'el-icon-tickets' },
        { label: '标准化结果', value: s.standardized_rows != null ? s.standardized_rows : (this.currentFileResult.rows || 0), icon: 'el-icon-finished' }
      ]
    },
    uploadStepActive() {
      if (this.result) return 3
      if (this.job) return 2
      if (this.fileList.length) return 1
      return 0
    },
    currentParseStage() {
      const progress = Number((this.job && this.job.progress) || 0)
      if (progress < 10) return '文件已接收，等待解析'
      if (progress < 40) return '正在读取工作表并识别文件结构'
      if (progress < 70) return '正在识别指标、维度和标准字段'
      if (progress < 100) return '正在执行数据质量检查'
      return '解析完成'
    },
    completionSummary() {
      const s = this.overallParseSummary
      const files = s.file_count || ((this.result && this.result.source_files) || []).length
      const sheets = s.sheet_count || ((this.result && this.result.sheet_meta) || []).length
      return `共解析${files}个文件、${sheets}个工作表，读取${this.formatNumber(s.source_data_rows_total || 0)}行有效源数据，生成${this.formatNumber(s.standardized_rows || (this.result && this.result.rows) || 0)}条标准化记录。`
    },
    issueStats() {
      return this.issueRows.reduce((stats, row) => { stats[row.level] = (stats[row.level] || 0) + 1; return stats }, { error: 0, warning: 0, info: 0 })
    },
    issueSummary() {
      if (!this.issueRows.length) return '未发现数据质量问题'
      const parts = []
      if (this.issueStats.error) parts.push(`${this.issueStats.error}项错误`)
      if (this.issueStats.warning) parts.push(`${this.issueStats.warning}项警告`)
      if (this.issueStats.info) parts.push(`${this.issueStats.info}项提示`)
      return `发现${parts.join('、')}`
    },
    originalFieldCount() {
      const total = (this.currentFileResult.sheet_meta || []).reduce((count, meta) => count + this.fieldMappingRows(meta).length, 0)
      return total || (this.currentFileResult.columns || []).filter(key => !TECHNICAL_PREVIEW_FIELDS.includes(key)).length
    },
    mappedFieldCount() {
      return (this.currentFileResult.sheet_meta || []).reduce((count, meta) => count + this.fieldMappingRows(meta).filter(row => row.mapped).length, 0)
    },
    fieldRecognitionSummary() {
      return `共读取${this.originalFieldCount}个原始字段，其中${this.mappedFieldCount}个已匹配标准字段`
    },
    sheetRows() {
      return (this.currentFileResult.sheet_meta || []).map(meta => {
        const issues = this.issuesForMeta(meta)
        const status = this.sheetRecognitionStatus(meta, issues)
        return {
          sheetName: meta.sheet_name || '-', dataType: this.sheetTypeLabel(meta.sheet_type), metric: this.metricLabel(meta.metric_label || meta.detected_metric),
          dimensions: this.sheetDimensions(meta), dataSize: `${this.formatNumber(meta.source_data_rows || 0)}行 / ${this.formatNumber(meta.physical_columns || 0)}列`,
          status: status.text, statusType: status.type, issueCount: issues.length, issues, raw: meta
        }
      })
    },
    issueRows() {
      return (this.currentFileResult.issues || []).map(issue => {
        const level = ['error', 'warning', 'info'].includes(issue.level) ? issue.level : 'info'
        const locationSheets = this.issueLocationSheets(issue)
        const isGlobal = !issue.sheet && !locationSheets.length
        return {
          ...issue, level, levelText: { error: '错误', warning: '警告', info: '提示' }[level],
          tagType: { error: 'danger', warning: 'warning', info: 'info' }[level],
          sheet: issue.sheet || (locationSheets.length === 1 ? locationSheets[0] : locationSheets.length > 1 ? '多个工作表' : '文件级问题'),
          filterSheets: locationSheets.length ? locationSheets : (issue.sheet ? [issue.sheet] : []),
          isGlobal,
          position: this.formatIssuePosition(issue), suggestion: this.issueSuggestion(issue)
        }
      })
    },
    issueSheetOptions() {
      return Array.from(new Set(this.sheetRows.map(row => row.sheetName).filter(name => name && name !== '-')))
    },
    displayedIssueRows() {
      return this.issueSheetFilter ? this.issueRows.filter(row => row.isGlobal || row.filterSheets.includes(this.issueSheetFilter)) : this.issueRows
    },
    availablePreviewColumns() {
      const rows = this.currentFileResult.preview || []
      const ordered = []
      const declared = this.currentFileResult.columns || []
      declared.concat(...rows.map(row => Object.keys(row || {}))).forEach(column => {
        if (!ordered.includes(column) && !TECHNICAL_PREVIEW_FIELDS.includes(column)) ordered.push(column)
      })
      return ordered.filter(column => rows.some(row => row[column] !== null && row[column] !== undefined && row[column] !== ''))
    },
    selectedPreviewColumns() {
      return this.availablePreviewColumns.filter(column => this.visiblePreviewColumns.includes(column))
    },
    previewSheetOptions() {
      const values = new Set()
      ;(this.currentFileResult.preview || []).forEach(row => {
        const value = row.source_sheet_name || row.source_sheet
        if (value) values.add(String(value))
      })
      return Array.from(values)
    },
    filteredPreviewRows() {
      const keyword = this.previewKeyword.toLowerCase()
      return (this.currentFileResult.preview || []).filter(row => {
        const sheet = row.source_sheet_name || row.source_sheet || ''
        if (this.previewSheet && String(sheet) !== this.previewSheet) return false
        if (!keyword) return true
        return this.selectedPreviewColumns.some(column => String(row[column] == null ? '' : row[column]).toLowerCase().includes(keyword))
      })
    },
    jobProgressStatus() { return this.job && this.job.status === 'failed' ? 'exception' : this.job && this.job.status === 'success' ? 'success' : undefined },
    jobTagType() { return this.job && this.job.status === 'failed' ? 'danger' : this.job && this.job.status === 'success' ? 'success' : this.job && this.job.status === 'cancelled' ? 'info' : 'warning' },
    jobStatusText() {
      const labels = { queued: '排队中', processing: '正在解析', success: '已完成', failed: '解析失败', cancelled: '已取消' }
      return labels[(this.job && this.job.status)] || '等待处理'
    }
  },
  created() {
    // 页面刷新只保留空白上传状态，组件被keep-alive切换时仍保留当前解析结果。
    marketHealth().then(() => { this.serviceOnline = true }).catch(() => { this.serviceOnline = false })
  },
  beforeDestroy() { if (this.pollTimer) clearTimeout(this.pollTimer) },
  methods: {
    resetResult() {
      this.result = null
      this.job = null
      this.activeResultTab = 'preview'
      this.selectedFile = 0
      this.previewKeywordInput = ''
      this.previewKeyword = ''
      this.previewSheet = ''
      this.visiblePreviewColumns = []
      this.issueSheetFilter = ''
      this.selectedSheet = null
      this.sheetDetailVisible = false
      if (this.pollTimer) { clearTimeout(this.pollTimer); this.pollTimer = null }
    },
    prepareResult() {
      this.activeResultTab = 'preview'
      this.selectedFile = 0
      this.previewKeywordInput = ''
      this.previewKeyword = ''
      this.previewSheet = ''
      this.issueSheetFilter = ''
      this.$nextTick(() => { this.visiblePreviewColumns = this.availablePreviewColumns.slice() })
    },
    onSelectedFileChange() {
      this.previewKeywordInput = ''
      this.previewKeyword = ''
      this.previewSheet = ''
      this.issueSheetFilter = ''
      this.selectedSheet = null
      this.sheetDetailVisible = false
      this.$nextTick(() => { this.visiblePreviewColumns = this.availablePreviewColumns.slice() })
    },
    applyPreviewSearch() {
      this.previewKeyword = String(this.previewKeywordInput || '').trim()
    },
    clearPreviewSearch() {
      this.previewKeywordInput = ''
      this.previewKeyword = ''
    },
    onPreviewSearchInput(value) {
      if (!String(value || '').trim()) this.previewKeyword = ''
    },
    onFileChange(file, files) { this.fileList = files; this.resetResult() },
    onFileRemove(file, files) { this.fileList = files; this.resetResult() },
    clearFiles() {
      this.fileList = []
      this.$refs.upload && this.$refs.upload.clearFiles()
      this.resetResult()
    },
    submit() {
      const invalid = this.fileList.find(file => !(file.raw instanceof Blob))
      if (invalid) {
        this.clearFiles()
        this.$modal.msgWarning('文件选择已失效，请重新选择后再解析')
        return
      }
      this.uploading = true
      this.result = null
      createExcelParseJob(this.fileList.map(file => file.raw)).then(job => {
        this.job = job
        this.serviceOnline = true
        this.pollJob()
      }).catch(() => { this.uploading = false })
    },
    pollJob() {
      if (!this.job || !this.job.job_id) return
      getExcelParseJob(this.job.job_id).then(job => {
        this.job = job
        if (job.status === 'success') {
          this.uploading = false
          this.result = job.result || null
          this.prepareResult()
          this.$modal.msgSuccess('解析完成，可查看标准化预览、工作表识别和质量问题')
        } else if (['failed', 'cancelled'].includes(job.status)) {
          this.uploading = false
          if (job.status === 'failed') this.$modal.msgError(job.message || '解析失败')
        } else {
          this.pollTimer = setTimeout(this.pollJob, 1200)
        }
      }).catch(() => { this.uploading = false })
    },
    cancelJob() { cancelExcelParseJob(this.job.job_id).then(job => { this.job = job; this.$modal.msgSuccess('已提交取消请求') }) },
    retryJob() {
      this.uploading = true
      retryExcelParseJob(this.job.job_id).then(job => { this.job = job; this.pollJob() }).catch(() => { this.uploading = false })
    },
    formatNumber(value) {
      const number = Number(value)
      return Number.isFinite(number) ? number.toLocaleString('zh-CN', { maximumFractionDigits: 4 }) : (value == null || value === '' ? '-' : value)
    },
    formatCell(value, column) {
      if (value == null || value === '') return '-'
      if (typeof value === 'boolean') return value ? '是' : '否'
      const number = typeof value === 'number' ? value : Number(value)
      if (/^yoy_|占比|同比|增长率|增速|变化率/.test(column) && Number.isFinite(number)) return `${(number * 100).toFixed(2)}%`
      if (typeof value === 'number' && Number.isFinite(value)) return value.toLocaleString('zh-CN', { maximumFractionDigits: 4 })
      return value
    },
    fieldLabel(field) {
      const key = String(field == null ? '' : field)
      return FIELD_LABELS[key] || key || '-'
    },
    metricLabel(metric) {
      if (!metric) return '暂未识别'
      return FIELD_LABELS[metric] || String(metric)
    },
    sheetTypeLabel(type) {
      const labels = {
        multi_dimension_detail: '多维汽车销量明细', vehicle_market: '汽车市场数据', vehicle_display_tracker: '车载显示数据',
        unknown_table: '待识别数据表', annual_month_blocks: '年度月度明细', yyyymm_wide: '月度宽表', long_table: '标准明细表'
      }
      return labels[type] || (type ? String(type).replace(/_/g, ' ') : '待识别数据表')
    },
    sheetDimensions(meta) {
      const mapped = Object.values(meta.field_mapping || {})
      if (DIMENSION_FIELDS.includes(meta.detected_dimension)) mapped.push(meta.detected_dimension)
      return Array.from(new Set(mapped.filter(field => DIMENSION_FIELDS.includes(field)).map(field => this.fieldLabel(field))))
    },
    issueLocationSheets(issue) {
      const sheets = (issue.locations || []).map(location => location && location.source_sheet).filter(Boolean)
      if (issue.sheet) sheets.push(issue.sheet)
      return Array.from(new Set(sheets.map(String)))
    },
    formatIssuePosition(issue) {
      const locations = Array.isArray(issue.locations) ? issue.locations : []
      if (locations.length) {
        const grouped = {}
        locations.forEach(location => {
          if (!location) return
          const sheet = String(location.source_sheet || issue.sheet || '工作表')
          let cell = location.source_cell
          if (!cell && location.source_row != null) {
            const column = location.source_column || (location.source_column_index != null ? `第${location.source_column_index}列` : '')
            cell = `${column ? `${column}，` : ''}第${location.source_row}行`
          }
          if (!cell) return
          if (!grouped[sheet]) grouped[sheet] = []
          if (!grouped[sheet].includes(String(cell))) grouped[sheet].push(String(cell))
        })
        const parts = Object.keys(grouped).map(sheet => `${sheet}：${grouped[sheet].slice(0, 8).join('、')}`)
        if (parts.length) {
          const total = Number(issue.location_count || locations.length)
          const suffix = issue.locations_truncated || total > 8 ? `（共${total}个原表位置）` : ''
          return `${parts.join('；')}${suffix}`
        }
      }
      const fallback = []
      if (issue.row != null) fallback.push(`第${issue.row}行`)
      if (issue.column) fallback.push(`字段“${this.fieldLabel(issue.column)}”`)
      return fallback.join('，') || '文件级问题'
    },
    issuesForMeta(meta) {
      const issues = this.currentFileResult.issues || []
      const metas = this.currentFileResult.sheet_meta || []
      return issues.filter(issue => issue.sheet === meta.sheet_name || this.issueLocationSheets(issue).includes(meta.sheet_name) || (!issue.sheet && !(issue.locations || []).length && metas.length === 1))
    },
    sheetRecognitionStatus(meta, issues) {
      if (issues.some(issue => issue.level === 'error') || (!meta.detected_metric && !Object.keys(meta.field_mapping || {}).length)) return { text: '识别失败', type: 'danger' }
      const passed = Number(meta.template_validation_score) === 1 && ['PASS', 'CORE_PASS_AUX_REVIEW'].includes(meta.template_validation_status)
      if (passed) return { text: '识别成功', type: 'success' }
      return { text: '建议确认', type: 'warning' }
    },
    issueSuggestion(issue) {
      const code = String(issue.code || '')
      if (/FIELD_MAPPING|UNMAPPED/.test(code)) return '核对原始表头，确认未识别字段的业务含义'
      if (/DIMENSION/.test(code)) return '核对原始维度值是否完整，避免分类遗漏'
      if (/TEMPLATE|SEMANTIC/.test(code)) return '核对工作表类型、指标口径和字段名称'
      if (/PERIOD|DATE|TIME/.test(code)) return '核对日期格式及月份连续性'
      if (/DUPLICATE/.test(code)) return '检查重复记录，确认是否需要去重'
      if (/MISSING|NULL|EMPTY/.test(code)) return '检查空值并按业务规则补充或剔除'
      if (issue.level === 'info') return '系统已记录该信息，通常无需处理'
      return '根据问题说明核对原始文件后重新解析'
    },
    openSheetDetail(row) { this.selectedSheet = row; this.sheetDetailVisible = true },
    showSheetIssues(row) { this.issueSheetFilter = row.sheetName; this.activeResultTab = 'issues' },
    periodRange(meta) {
      if (meta.period_start && meta.period_end) return meta.period_start === meta.period_end ? meta.period_start : `${meta.period_start} 至 ${meta.period_end}`
      return '未识别时间范围'
    },
    fieldMappingRows(meta) {
      const mapping = meta.field_mapping || {}
      const headers = (meta.original_headers || []).map(value => String(value == null ? '' : value).trim()).filter(Boolean)
      const allHeaders = Array.from(new Set(headers.concat(Object.keys(mapping))))
      return allHeaders.map(original => {
        const standard = mapping[original] || (this.isPeriodHeader(original) ? 'time_period' : '')
        return { original, meaning: standard ? this.fieldLabel(standard) : '暂未识别', standard: standard || '-', mapped: Boolean(standard) }
      })
    },
    isPeriodHeader(value) {
      const text = String(value == null ? '' : value).trim().replace(/\s/g, '')
      return /^20\d{2}(?:[-/.]?(?:0?[1-9]|1[0-2]))(?:\.0)?$/.test(text) || /^(?:0?[1-9]|1[0-2])月$/.test(text) || /^20\d{2}年$/.test(text)
    },
    mappedFieldSummary(meta) {
      const rows = this.fieldMappingRows(meta)
      const mapped = rows.filter(row => row.mapped).length
      return `${mapped}个字段已匹配标准字段，${rows.length - mapped}个字段需要确认。`
    }
  }
}
</script>

<style scoped>
.market-import { padding: 16px; }
.mb16 { margin-bottom: 16px; }
.card-head, .result-head, .progress-head, .tab-toolbar, .toolbar-group, .result-file, .result-alert-content { display: flex; align-items: center; }
.card-head, .result-head, .progress-head, .tab-toolbar { justify-content: space-between; gap: 16px; }
.card-title { color: #303133; font-size: 17px; font-weight: 600; }
.card-subtitle { color: #909399; font-size: 13px; margin-top: 6px; }
.parse-steps { margin-bottom: 20px; }
.upload-area { max-width: 620px; }
.upload-area ::v-deep .el-upload, .upload-area ::v-deep .el-upload-dragger { width: 100%; }
.upload-area ::v-deep .el-upload-list { max-width: 620px; }
.actions { margin-top: 16px; }
.job-progress { margin-top: 20px; max-width: 760px; padding: 16px; background: #f7f9fc; border-radius: 6px; }
.progress-title { color: #303133; font-weight: 600; }
.progress-percent { color: #409eff; font-weight: 600; }
.progress-message { display: flex; align-items: center; gap: 10px; color: #606266; font-size: 13px; margin-top: 10px; }
.result-alert { margin-top: 16px; }
.result-alert-content { gap: 12px; flex-wrap: wrap; }
.summary-row { margin-top: 16px; }
.summary-card { display: flex; align-items: center; gap: 14px; min-height: 92px; background: #f7f9fc; border: 1px solid #edf0f5; border-radius: 8px; padding: 16px 20px; margin-bottom: 16px; }
.summary-icon { width: 42px; height: 42px; display: flex; align-items: center; justify-content: center; border-radius: 8px; color: #409eff; background: #eaf3ff; font-size: 21px; }
.summary-card .value { color: #303133; font-size: 23px; font-weight: 600; line-height: 1.2; }
.summary-card .label { color: #909399; margin-top: 5px; font-size: 13px; }
.result-card { margin-top: 0; }
.result-file { gap: 10px; min-width: 0; }
.result-file > i { color: #67c23a; font-size: 22px; }
.result-file strong { display: block; max-width: 1000px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.result-file-note { color: #909399; font-size: 12px; margin-top: 5px; }
.file-selector { display: flex; align-items: center; gap: 10px; flex: 0 0 auto; color: #606266; font-size: 13px; }
.file-selector .el-select { width: 320px; }
.tab-toolbar { margin-bottom: 12px; flex-wrap: wrap; }
.toolbar-group { gap: 10px; }
.toolbar-note { color: #909399; font-size: 12px; }
.preview-search { width: 260px; }
.sheet-filter { width: 190px; }
.column-picker-title { font-weight: 600; margin-bottom: 10px; }
.column-picker { max-height: 260px; overflow-y: auto; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.column-picker ::v-deep .el-checkbox { margin: 0 10px 8px 0; overflow: hidden; text-overflow: ellipsis; }
.tab-description { color: #909399; font-size: 13px; margin-bottom: 12px; }
.dimension-tag { margin: 2px 5px 2px 0; }
.muted { color: #c0c4cc; }
.success-text { color: #67c23a; }
.issue-overview { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #606266; margin-bottom: 12px; }
.sheet-detail { padding: 0 24px 30px; }
.detail-section-title { color: #303133; font-size: 15px; font-weight: 600; margin: 24px 0 12px; }
.recognition-notes { margin-top: 18px; padding: 14px 18px; background: #f7f9fc; border-radius: 6px; color: #606266; }
.recognition-notes ul, .evidence-collapse ul { margin: 8px 0 0; padding-left: 20px; line-height: 1.9; }
.evidence-collapse { margin-top: 14px; }
@media (max-width: 768px) {
  .market-import { padding: 10px; }
  .card-head, .tab-toolbar { align-items: flex-start; flex-direction: column; }
  .upload-area, .preview-search, .sheet-filter { width: 100%; max-width: none; }
  .toolbar-group { width: 100%; flex-wrap: wrap; }
  .result-head { align-content: flex-start; }
  .file-selector { width: 100%; }
  .file-selector .el-select { width: 100%; }
  .sheet-detail { padding: 0 12px 20px; }
}
</style>
