<template>
  <div class="app-container legacy-display">
    <el-card class="mb8" shadow="never">
      <div slot="header" class="clearfix">
        <span>上传解析</span>
      </div>
      <div class="free-upload">
        <el-upload
          ref="freeUpload"
          action="#"
          :auto-upload="false"
          :multiple="true"
          :limit="5"
          :show-file-list="false"
          :disabled="uploadingFiles || parsing"
          :file-list="freeFileList"
          accept=".xlsx,.xlsm,.csv"
          :on-change="onFreeFileChange"
          :on-remove="onFreeFileRemove"
          :on-exceed="onFreeFileExceed"
        >
          <el-button size="small" type="primary" icon="el-icon-folder-opened" :loading="uploadingFiles">选取文件</el-button>
          <div slot="tip" class="el-upload__tip">可一次选择 1～5 个 Excel/CSV（单个 ≤10MB）。支持多份 History / Supply 按 Year/Quarter 合并；系统按文件名自动识别角色，识别不准时可手动调整。</div>
        </el-upload>
        <el-table v-if="selectedFiles.length" :data="selectedFiles" border size="mini" class="role-table">
          <el-table-column label="文件名" min-width="260" show-overflow-tooltip>
            <template slot-scope="scope">{{ scope.row.originalName }}</template>
          </el-table-column>
          <el-table-column label="解析角色" width="240">
            <template slot-scope="scope">
              <el-select v-model="scope.row.role" size="mini" placeholder="请指定角色" style="width:100%" @change="onRoleChange(scope.row)">
                <el-option label="当前主 History" value="current" />
                <el-option label="补充 History（多季度）" value="extra_history" />
                <el-option label="Y22 基准文件" value="baseline" />
                <el-option label="主 Supply Chain" value="supply" />
                <el-option label="补充 Supply Chain" value="extra_supply" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template slot-scope="scope">
              <el-tag v-if="scope.row.storedName" type="success" size="mini">已上传</el-tag>
              <el-tag v-else-if="scope.row.uploading" type="warning" size="mini">上传中</el-tag>
              <el-tag v-else type="info" size="mini">待上传</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center">
            <template slot-scope="scope">
              <el-button type="text" size="mini" :disabled="uploadingFiles || parsing" @click="removeSelectedFile(scope.$index)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="upload-actions">
          <el-button type="primary" icon="el-icon-magic-stick" :loading="parsing" :disabled="!canParseUploaded" @click="handleParseUploaded">解析已选文件</el-button>
          <el-button icon="el-icon-delete" :disabled="!selectedFiles.length || uploadingFiles || parsing" @click="clearSelectedFiles">清空</el-button>
          <span class="upload-tip">{{ roleHint }}</span>
        </div>
      </div>
    </el-card>

    <div v-if="parsing" class="parse-progress mb8">
      <el-progress :percentage="taskProgress" :status="taskProgress >= 100 ? 'success' : undefined" />
      <div class="parse-progress__text">{{ taskRemark || '解析任务排队中' }}</div>
    </div>

    <el-alert
      v-if="parseSummary"
      class="mb8"
      type="success"
      :closable="false"
      :title="parseSummary"
      show-icon
    />
    <div v-if="parseResult && parseResult.report_id" class="report-actions mb8">
      <el-button
        type="success"
        plain
        icon="el-icon-document"
        @click="viewGeneratedReport"
      >查看自动生成的第一份报告</el-button>
      <el-button
        type="primary"
        plain
        icon="el-icon-document"
        :loading="exportingFormat === 'word'"
        @click="handleOfficeExport('word')"
      >下载 Word</el-button>
      <el-button
        type="warning"
        plain
        icon="el-icon-data-analysis"
        :loading="exportingFormat === 'ppt'"
        @click="handleOfficeExport('ppt')"
      >下载 PPT</el-button>
    </div>

    <el-collapse v-if="parseResult" class="mb8">
      <el-collapse-item title="解析结果 JSON 预览" name="result">
        <pre class="json-preview">{{ parsePreview }}</pre>
      </el-collapse-item>
    </el-collapse>

    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="任务名称" prop="taskName">
        <el-input
          v-model="queryParams.taskName"
          placeholder="请输入任务名称"
          clearable
          style="width: 240px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="状态" clearable style="width: 120px">
          <el-option label="待处理" value="0" />
          <el-option label="处理中" value="1" />
          <el-option label="成功" value="2" />
          <el-option label="失败" value="3" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd" v-hasPermi="['business:data:excel:add']">新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="success" plain icon="el-icon-edit" size="mini" :disabled="single" @click="handleUpdate" v-hasPermi="['business:data:excel:edit']">修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="danger" plain icon="el-icon-delete" size="mini" :disabled="multiple" @click="handleDelete" v-hasPermi="['business:data:excel:remove']">删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="warning" plain icon="el-icon-download" size="mini" @click="handleExport" v-hasPermi="['business:data:excel:export']">导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="excelImportList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="主键" align="center" prop="id" />
      <el-table-column label="任务名称" align="center" prop="taskName" :show-overflow-tooltip="true" />
      <el-table-column label="文件名" align="center" prop="fileName" :show-overflow-tooltip="true" />
      <el-table-column label="Sheet" align="center" prop="sheetCount" width="80" />
      <el-table-column label="表格" align="center" prop="tableCount" width="80" />
      <el-table-column label="记录" align="center" prop="recordCount" width="80" />
      <el-table-column label="状态" align="center" prop="status">
        <template slot-scope="scope">
          <span>{{ formatStatus(scope.row.status) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" align="center" prop="createTime" width="180">
        <template slot-scope="scope">
          <span>{{ parseTime(scope.row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-view" @click="handleViewResult(scope.row)" v-if="String(scope.row.status) === '2'">结果</el-button>
          <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)" v-hasPermi="['business:data:excel:edit']">修改</el-button>
          <el-button size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)" v-hasPermi="['business:data:excel:remove']">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="total > 0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />

    <!-- 添加或修改Excel导入对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="任务名称" prop="taskName">
          <el-input v-model="form.taskName" placeholder="请输入任务名称" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="form.status" placeholder="请选择状态">
            <el-option label="待处理" value="0" />
            <el-option label="处理中" value="1" />
            <el-option label="成功" value="2" />
            <el-option label="失败" value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" placeholder="请输入备注" />
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listExcelImport, getExcelImport, getExcelImportResult, getExcelImportStatus, addExcelImport, updateExcelImport, delExcelImport, exportExcelImport, parseUploadExcel, uploadExcelImport } from "@/api/business/data/excel/excelImport";
import { exportAiReportOffice } from "@/api/business/report/aiReport";

const ROLE_OPTIONS = [
  { value: 'current', label: '当前主 History' },
  { value: 'extra_history', label: '补充 History（多季度）' },
  { value: 'baseline', label: 'Y22 基准文件' },
  { value: 'supply', label: '主 Supply Chain' },
  { value: 'extra_supply', label: '补充 Supply Chain' }
]

/** Unique roles: only one current / baseline / supply; extras may repeat. */
const UNIQUE_ROLES = new Set(['current', 'baseline', 'supply'])

/** 解析默认参数：与原先表单默认值一致，页面不再暴露高级开关。 */
const DEFAULT_PARSE_OPTIONS = {
  includeRawCells: false,
  rawCellMode: 'non-empty',
  useLlm: true
}

export default {
  name: "LegacyDisplay",
  data() {
    return {
      loading: true,
      ids: [],
      single: true,
      multiple: true,
      showSearch: true,
      total: 0,
      excelImportList: [],
      title: "",
      open: false,
      parsing: false,
      uploadingFiles: false,
      taskProgress: 0,
      taskRemark: "",
      pollTimer: null,
      parseResult: null,
      exportingFormat: "",
      freeFileList: [],
      selectedFiles: [],
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        taskName: undefined,
        status: undefined
      },
      form: {},
      rules: {
        taskName: [
          { required: true, message: "任务名称不能为空", trigger: "blur" }
        ]
      }
    };
  },
  computed: {
    uploadForm() {
      const byRole = role => {
        const item = this.selectedFiles.find(file => file.role === role && file.storedName)
        return item ? item.storedName : ""
      }
      const many = role => this.selectedFiles
        .filter(file => file.role === role && file.storedName)
        .map(file => file.storedName)
      return {
        fileName: byRole('current'),
        baselineFileName: byRole('baseline'),
        supplyChainFileName: byRole('supply'),
        extraHistoryFileNames: many('extra_history'),
        extraSupplyChainFileNames: many('extra_supply')
      }
    },
    canParseUploaded() {
      return !!this.uploadForm.fileName && !this.uploadingFiles && !this.selectedFiles.some(file => file.uploading)
    },
    roleHint() {
      if (!this.selectedFiles.length) {
        return "三文件对标终稿；五文件=三文件+1Q26 History/Supply，对标 1Q26 Analysis。"
      }
      const labels = this.selectedFiles.map(file => {
        const role = ROLE_OPTIONS.find(item => item.value === file.role)
        return `${file.originalName} → ${role ? role.label : '未指定'}`
      })
      return labels.join('；')
    },
    parseSummary() {
      if (!this.parseResult) {
        return "";
      }
      if (this.parseResult.sheet_count !== undefined) {
        const quality = this.parseResult.quality || {};
        const llm = quality.llm_used
          ? `，LLM增强 ${quality.llm_success_count || 0} 个候选表`
          : (quality.structure_note
            ? `，${quality.structure_note}`
            : (quality.llm_requested ? '，LLM调用失败，已降级为规则解析' : ''));
        return `已解析 ${this.parseResult.sheet_count} 个 Sheet，识别 ${this.parseResult.table_count || 0} 个候选表，提取 ${this.parseResult.record_count || 0} 条记录${llm}`;
      }
      const sheets = this.parseResult.sheets || [];
      const tableCount = sheets.reduce((sum, sheet) => sum + ((sheet.tables || []).length), 0);
      const recordCount = sheets.reduce((sum, sheet) => sum + (sheet.tables || []).reduce((inner, table) => inner + ((table.records || []).length), 0), 0);
      return `已解析 ${sheets.length} 个 Sheet，识别 ${tableCount} 个候选表，预览 ${recordCount} 条记录`;
    },
    parsePreview() {
      if (!this.parseResult) {
        return "";
      }
      return JSON.stringify(this.parseResult, null, 2);
    }
  },
  created() {
    this.getList();
  },
  beforeDestroy() {
    if (this.pollTimer) {
      clearTimeout(this.pollTimer);
    }
  },
  methods: {
    getList() {
      this.loading = true;
      listExcelImport(this.queryParams).then(response => {
        this.excelImportList = response.rows;
        this.total = response.total;
        this.loading = false;
      });
    },
    formatStatus(status) {
      const map = { '0': '待处理', '1': '处理中', '2': '成功', '3': '失败' };
      return map[status] || status;
    },
    guessFileRole(fileName) {
      const name = String(fileName || '').toLowerCase()
      if (/supply\s*chain|供应链|customer|region|区域/.test(name)) {
        return /1q26|q126/.test(name) ? 'extra_supply' : 'supply'
      }
      if (/1q25|4q24|baseline|基准|y22/.test(name) && !/4q25|3q25|1q26/.test(name)) return 'baseline'
      if (/1q26|q126/.test(name) && /history|pivot/.test(name)) return 'extra_history'
      if (/4q25|3q25|current|当前|history|pivot/.test(name)) return 'current'
      return ''
    },
    historyRank(fileName) {
      const name = String(fileName || '').toLowerCase()
      if (/1q26|q126/.test(name)) return 2601
      if (/4q25|3q25/.test(name)) return 2504
      if (/1q25|4q24/.test(name)) return 2501
      return 0
    },
    assignRoles(files) {
      const used = new Set()
      const next = files.map(file => ({ ...file }))
      next.forEach(file => {
        const guessed = this.guessFileRole(file.originalName)
        if (!guessed) {
          file.role = ''
          return
        }
        if (UNIQUE_ROLES.has(guessed) && used.has(guessed)) {
          if (guessed === 'current') file.role = 'extra_history'
          else if (guessed === 'supply') file.role = 'extra_supply'
          else file.role = ''
          return
        }
        file.role = guessed
        if (UNIQUE_ROLES.has(guessed)) used.add(guessed)
      })
      // Newest History should be primary current so merge last-write wins.
      const historyFiles = next.filter(file =>
        file.role === 'current' || file.role === 'extra_history' ||
        (/history|pivot/.test(String(file.originalName || '').toLowerCase()) &&
          !/supply/.test(String(file.originalName || '').toLowerCase()) &&
          file.role !== 'baseline')
      )
      if (historyFiles.length) {
        historyFiles.sort((a, b) => this.historyRank(b.originalName) - this.historyRank(a.originalName))
        historyFiles.forEach((file, index) => {
          file.role = index === 0 ? 'current' : 'extra_history'
        })
      }
      const supplyFiles = next.filter(file =>
        file.role === 'supply' || file.role === 'extra_supply' ||
        /supply\s*chain|供应链/.test(String(file.originalName || '').toLowerCase())
      )
      if (supplyFiles.length) {
        supplyFiles.sort((a, b) => this.historyRank(b.originalName) - this.historyRank(a.originalName))
        supplyFiles.forEach((file, index) => {
          file.role = index === 0 ? 'supply' : 'extra_supply'
        })
      }
      const leftovers = ['current', 'baseline', 'supply'].filter(role => !next.some(file => file.role === role))
      next.forEach(file => {
        if (!file.role && leftovers.length) {
          file.role = leftovers.shift()
        }
      })
      return next
    },
    onFreeFileExceed() {
      this.$modal.msgWarning('最多选择 5 个文件')
    },
    onFreeFileRemove(file, fileList) {
      this.freeFileList = fileList
      this.syncSelectedFromUploadList(fileList)
    },
    onFreeFileChange(file, fileList) {
      this.freeFileList = fileList
      this.syncSelectedFromUploadList(fileList)
    },
    syncSelectedFromUploadList(fileList) {
      const allowed = ['xlsx', 'xlsm', 'csv']
      const next = []
      for (const item of fileList.slice(-5)) {
        const raw = item.raw
        const originalName = (raw && raw.name) || item.name || ''
        const ext = originalName.split('.').pop().toLowerCase()
        if (!allowed.includes(ext)) {
          this.$modal.msgError(`不支持的文件格式：${originalName}`)
          continue
        }
        if (raw && raw.size / 1024 / 1024 >= 10) {
          this.$modal.msgError(`文件不能超过 10MB：${originalName}`)
          continue
        }
        const existing = this.selectedFiles.find(file => file.uid === item.uid)
        next.push(existing && existing.originalName === originalName ? existing : {
          uid: item.uid,
          originalName,
          raw,
          role: '',
          storedName: '',
          uploading: false
        })
      }
      this.selectedFiles = this.assignRoles(next)
      this.uploadPendingFiles()
    },
    uploadPendingFiles() {
      const pending = this.selectedFiles.filter(file => file.raw && !file.storedName && !file.uploading)
      if (!pending.length) return
      this.uploadingFiles = true
      const tasks = pending.map(file => {
        file.uploading = true
        const formData = new FormData()
        formData.append('file', file.raw)
        return uploadExcelImport(formData).then(res => {
          file.storedName = res.fileName || res.url || ''
          file.uploading = false
          if (!file.storedName) {
            return Promise.reject(new Error(`${file.originalName} 上传失败：未返回文件名`))
          }
        }).catch(error => {
          file.uploading = false
          throw error
        })
      })
      Promise.all(tasks).catch(error => {
        const message = (error && error.message) || '文件上传失败'
        this.$modal.msgError(message)
      }).finally(() => {
        this.uploadingFiles = this.selectedFiles.some(file => file.uploading)
      })
    },
    onRoleChange(changed) {
      if (!UNIQUE_ROLES.has(changed.role)) return
      this.selectedFiles.forEach(file => {
        if (file !== changed && file.role === changed.role) {
          file.role = ''
        }
      })
    },
    removeSelectedFile(index) {
      const removed = this.selectedFiles.splice(index, 1)[0]
      this.freeFileList = this.freeFileList.filter(item => item.uid !== (removed && removed.uid))
      if (this.$refs.freeUpload) {
        this.$refs.freeUpload.uploadFiles = this.freeFileList
      }
    },
    clearSelectedFiles() {
      this.selectedFiles = []
      this.freeFileList = []
      if (this.$refs.freeUpload) {
        this.$refs.freeUpload.clearFiles()
      }
    },
    handleParseUploaded() {
      if (!this.uploadForm.fileName) {
        this.$modal.msgError("请至少指定一个「当前主 History」并完成上传");
        return;
      }
      const missingRole = this.selectedFiles.find(file => !file.role)
      if (missingRole) {
        this.$modal.msgError(`请为「${missingRole.originalName}」指定解析角色`);
        return;
      }
      if (this.selectedFiles.some(file => !file.storedName)) {
        this.$modal.msgError("仍有文件未上传完成，请稍候再解析");
        return;
      }
      this.parsing = true;
      this.taskProgress = 0;
      this.taskRemark = "正在提交解析任务";
      const payload = {
        ...DEFAULT_PARSE_OPTIONS,
        baselineFileName: this.uploadForm.baselineFileName || undefined,
        supplyChainFileName: this.uploadForm.supplyChainFileName || undefined
      }
      if (this.uploadForm.extraHistoryFileNames && this.uploadForm.extraHistoryFileNames.length) {
        payload.extraHistoryFileNames = this.uploadForm.extraHistoryFileNames
      }
      if (this.uploadForm.extraSupplyChainFileNames && this.uploadForm.extraSupplyChainFileNames.length) {
        payload.extraSupplyChainFileNames = this.uploadForm.extraSupplyChainFileNames
      }
      parseUploadExcel(this.uploadForm.fileName, payload).then(response => {
        this.$modal.msgSuccess("解析任务已提交");
        this.getList();
        return this.waitForTask(response.data.taskId);
      }).then(payload => {
        this.parseResult = payload;
        this.$modal.msgSuccess("解析完成");
        this.getList();
      }).catch(error => {
        if (error && error.message) {
          this.$modal.msgError(error.message);
        }
      }).finally(() => {
        this.parsing = false;
      });
    },
    waitForTask(taskId) {
      // 后端大型工作簿全局上限为30分钟，额外保留2分钟用于排队和结果读取。
      const deadline = Date.now() + 32 * 60 * 1000;
      return new Promise((resolve, reject) => {
        const poll = () => {
          getExcelImportStatus(taskId).then(response => {
            const task = response.data;
            this.taskProgress = Number(task.progress || 0);
            this.taskRemark = task.remark || "解析中";
            if (String(task.status) === '2') {
              this.taskProgress = 100;
              getExcelImportResult(taskId).then(result => {
                const normalized = this.normalizeResult(result.data);
                normalized.report_id = task.report_id;
                normalized.report_status = task.report_status;
                resolve(normalized);
              }).catch(reject);
              return;
            }
            if (String(task.status) === '3') {
              reject(new Error(task.remark || '解析失败'));
              return;
            }
            if (Date.now() >= deadline) {
              reject(new Error('等待解析结果超时，请稍后在任务列表中查看'));
              return;
            }
            this.pollTimer = setTimeout(poll, 1000);
          }).catch(reject);
        };
        poll();
      });
    },
    handleViewResult(row) {
      getExcelImportResult(row.id).then(response => {
        this.parseResult = this.normalizeResult(response.data);
      });
    },
    normalizeResult(data) {
      if (typeof data === "string") {
        return JSON.parse(data);
      }
      return data;
    },
    viewGeneratedReport() {
      this.$router.push({ path: '/business/report', query: { reportId: this.parseResult.report_id } });
    },
    handleOfficeExport(format) {
      const reportId = this.parseResult && this.parseResult.report_id;
      if (!reportId || this.exportingFormat) return;
      this.exportingFormat = format;
      exportAiReportOffice(reportId, format).then(blob => {
        if (blob && blob.type === 'application/json') {
          return this.$download.printErrMsg(blob);
        }
        const suffix = format === 'word' ? 'docx' : 'pptx';
        this.$download.saveAs(blob, `车载市场分析报告_${reportId}.${suffix}`);
        this.$modal.msgSuccess(`${format === 'word' ? 'Word' : 'PPT'} 下载成功`);
      }).finally(() => {
        this.exportingFormat = '';
      });
    },
    cancel() {
      this.open = false;
      this.reset();
    },
    reset() {
      this.form = {
        id: undefined,
        taskName: undefined,
        status: "0",
        remark: undefined
      };
      this.resetForm("form");
    },
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    resetQuery() {
      this.resetForm("queryForm");
      this.handleQuery();
    },
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id);
      this.single = selection.length !== 1;
      this.multiple = !selection.length;
    },
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = "添加Excel导入";
    },
    handleUpdate(row) {
      this.reset();
      const id = row.id || this.ids;
      getExcelImport(id).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改Excel导入";
      });
    },
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id !== undefined) {
            updateExcelImport(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addExcelImport(this.form).then(response => {
              this.$modal.msgSuccess("新增成功");
              this.open = false;
              this.getList();
            });
          }
        }
      });
    },
    handleDelete(row) {
      const ids = row.id || this.ids;
      this.$modal.confirm('是否确认删除编号为"' + ids + '"的数据项？').then(function() {
        return delExcelImport(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    handleExport() {
      this.download('/business/data/excel/export', {
        ...this.queryParams
      }, `Excel导入_${new Date().getTime()}.xlsx`)
    }
  }
};
</script>

<style scoped>
.parse-progress {
  padding: 12px 16px;
  background: #f5f7fa;
  border-radius: 4px;
}
.parse-progress__text {
  margin-top: 6px;
  color: #606266;
  font-size: 13px;
}
.upload-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.free-upload .el-upload__tip {
  margin-top: 6px;
  color: #909399;
}
.role-table {
  margin-top: 12px;
}
.upload-label {
  margin-bottom: 8px;
  color: #606266;
  font-size: 13px;
}
.upload-tip {
  color: #909399;
  font-size: 12px;
  line-height: 1.5;
}
.report-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.json-preview {
  max-height: 360px;
  overflow: auto;
  padding: 12px;
  margin: 0;
  background: #f6f8fa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
}
</style>
