<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="任务名称" prop="taskName"><el-input v-model="queryParams.taskName" placeholder="请输入任务名称" clearable style="width: 240px" @keyup.enter.native="handleQuery" /></el-form-item>
      <el-form-item label="状态" prop="status"><el-select v-model="queryParams.status" placeholder="状态" clearable style="width: 120px"><el-option label="待处理" value="0" /><el-option label="处理中" value="1" /><el-option label="成功" value="2" /><el-option label="失败" value="3" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button><el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button></el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="primary" plain icon="el-icon-upload2" size="mini" @click="handleAdd" v-hasPermi="['business:data:pdf:add']">上传并解析</el-button></el-col>
      <el-col :span="1.5"><el-button type="success" plain icon="el-icon-edit" size="mini" :disabled="single" @click="handleUpdate" v-hasPermi="['business:data:pdf:edit']">修改</el-button></el-col>
      <el-col :span="1.5"><el-button type="danger" plain icon="el-icon-delete" size="mini" :disabled="multiple" @click="handleDelete" v-hasPermi="['business:data:pdf:remove']">删除</el-button></el-col>
      <el-col :span="1.5"><el-button type="warning" plain icon="el-icon-download" size="mini" @click="handleExport" v-hasPermi="['business:data:pdf:export']">导出</el-button></el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList" />
    </el-row>

    <el-table v-loading="loading" :data="pdfParseList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="任务名称" align="center" prop="taskName" :show-overflow-tooltip="true" />
      <el-table-column label="原始文件" align="center" prop="originalFileName" :show-overflow-tooltip="true" />
      <el-table-column label="解析状态" align="center" prop="status" width="100"><template slot-scope="scope">{{ formatStatus(scope.row.status) }}</template></el-table-column>
      <el-table-column label="知识库状态" align="center" prop="knowledgeStatus" width="150"><template slot-scope="scope"><el-tag :type="knowledgeTagType(scope.row.knowledgeStatus)" size="mini">{{ formatKnowledgeStatus(scope.row.knowledgeStatus) }}</el-tag><div v-if="isKnowledgeActive(scope.row) && scope.row.knowledgeStage" class="knowledge-stage">{{ scope.row.knowledgeProgress || 0 }}% · {{ scope.row.knowledgeStage }}</div></template></el-table-column>
      <el-table-column label="实体数" align="center" prop="entityCount" width="90" />
      <el-table-column label="创建时间" align="center" prop="createTime" width="180"><template slot-scope="scope">{{ parseTime(scope.row.createTime) }}</template></el-table-column>
      <el-table-column label="完成时间" align="center" prop="completedTime" width="180"><template slot-scope="scope">{{ parseTime(scope.row.completedTime) }}</template></el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="260">
        <template slot-scope="scope">
          <el-button v-if="scope.row.resultJson" size="mini" type="text" icon="el-icon-view" @click="handleViewResult(scope.row)" v-hasPermi="['business:data:pdf:query']">查看结果</el-button>
          <el-button v-if="scope.row.status === '3' && scope.row.errorMessage" size="mini" type="text" icon="el-icon-warning-outline" @click="handleViewResult(scope.row)" v-hasPermi="['business:data:pdf:query']">查看失败</el-button>
          <el-button v-if="scope.row.status === '3'" size="mini" type="text" icon="el-icon-refresh-right" @click="handleRetryParse(scope.row)" v-hasPermi="['business:data:pdf:edit']">重新解析</el-button>
          <el-button v-if="canPublish(scope.row)" size="mini" type="text" icon="el-icon-upload" @click="handlePublishKnowledge(scope.row)" v-hasPermi="['business:data:pdf:edit']">{{ scope.row.knowledgeStatus === 'FAILED' ? '重新发布' : '发布到知识库' }}</el-button>
          <el-button v-else-if="isKnowledgeActive(scope.row)" size="mini" type="text" disabled>{{ formatKnowledgeStatus(scope.row.knowledgeStatus) }}</el-button>
          <el-button v-else-if="scope.row.knowledgeStatus === 'PUBLISHED'" size="mini" type="text" disabled>已发布</el-button>
          <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)" v-hasPermi="['business:data:pdf:edit']">修改</el-button>
          <el-button size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)" v-hasPermi="['business:data:pdf:remove']">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <el-dialog title="上传并解析文档" :visible.sync="parseOpen" width="500px" append-to-body :close-on-click-modal="!parseLoading">
      <el-form :model="parseForm" label-width="90px" v-loading="parseLoading">
        <el-form-item label="任务名称"><el-input v-model="parseForm.taskName" placeholder="可选，默认使用原始文件名" maxlength="200" /></el-form-item>
        <el-form-item label="选择文件" required>
          <el-upload ref="upload" action="#" :auto-upload="false" :limit="1" accept=".pdf,.pptx" :file-list="fileList" :on-change="handleFileChange" :on-remove="handleFileRemove" :on-exceed="handleFileExceed">
            <el-button size="small" type="primary" :disabled="parseLoading">选择 PDF/PPTX</el-button>
            <div slot="tip" class="el-upload__tip">仅支持 PDF 或 PPTX，文件保存后再解析。</div>
          </el-upload>
        </el-form-item>
        <el-alert v-if="parseLoading" title="正在解析文档，请勿重复提交" type="info" :closable="false" show-icon />
      </el-form>
      <div slot="footer" class="dialog-footer"><el-button type="primary" :loading="parseLoading" @click="submitParse">开始解析</el-button><el-button :disabled="parseLoading" @click="cancelParse">取 消</el-button></div>
    </el-dialog>

    <!-- 保留原有 CRUD 编辑入口及接口。 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="任务名称" prop="taskName"><el-input v-model="form.taskName" placeholder="请输入任务名称" /></el-form-item>
        <el-form-item label="备注" prop="remark"><el-input v-model="form.remark" type="textarea" placeholder="请输入备注" /></el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer"><el-button type="primary" @click="submitForm">确 定</el-button><el-button @click="cancel">取 消</el-button></div>
    </el-dialog>

    <el-dialog title="文档解析结果" :visible.sync="resultOpen" width="760px" append-to-body>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="任务名称">{{ resultTask.taskName }}</el-descriptions-item><el-descriptions-item label="原始文件">{{ resultTask.originalFileName }}</el-descriptions-item>
        <el-descriptions-item label="解析状态">{{ formatStatus(resultTask.status) }}</el-descriptions-item><el-descriptions-item label="知识库状态">{{ formatKnowledgeStatus(resultTask.knowledgeStatus) }}</el-descriptions-item>
        <el-descriptions-item label="实体数">{{ resultTask.entityCount }}</el-descriptions-item><el-descriptions-item label="知识库任务">{{ resultTask.knowledgeIngestTaskId || '—' }}</el-descriptions-item>
        <el-descriptions-item label="模型">{{ resultTask.llmModel || '—' }}</el-descriptions-item><el-descriptions-item label="完成时间">{{ parseTime(resultTask.completedTime) }}</el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="resultTask.errorMessage" class="result-error" title="解析失败摘要" type="error" :description="resultTask.errorMessage" :closable="false" show-icon />
      <el-input v-if="resultJsonText" type="textarea" :rows="16" :value="resultJsonText" readonly />
      <div v-else-if="!resultTask.errorMessage" class="empty-result">当前任务没有可展示的解析 JSON。</div>
    </el-dialog>
  </div>
</template>

<script>
import { listPdfParse, getPdfParse, addPdfParse, updatePdfParse, delPdfParse, parsePdf, publishPdfKnowledge, retryPdfParse } from "@/api/business/data/pdf/pdfParse";

export default {
  name: "PdfParse",
  data() {
    return {
      loading: true, parseLoading: false, parseOpen: false, resultOpen: false, resultTask: {}, resultJsonText: "", fileList: [], pollTimer: null,
      ids: [], single: true, multiple: true, showSearch: true, total: 0, pdfParseList: [], title: "", open: false,
      queryParams: { pageNum: 1, pageSize: 10, taskName: undefined, status: undefined },
      parseForm: { taskName: "", file: null }, form: {},
      rules: { taskName: [{ required: true, message: "任务名称不能为空", trigger: "blur" }] }
    };
  },
  created() { this.getList(); },
  beforeDestroy() { this.stopPolling(); },
  methods: {
    getList() { this.loading = true; listPdfParse(this.queryParams).then(response => { this.pdfParseList = response.rows; this.total = response.total; this.syncPolling(); }).finally(() => { this.loading = false; }); },
    syncPolling() {
      const hasProcessing = this.pdfParseList.some(item => item.status === '1' || this.isKnowledgeActive(item));
      if (hasProcessing && !this.pollTimer) this.pollTimer = setInterval(() => this.getList(), 4000);
      if (!hasProcessing) this.stopPolling();
    },
    stopPolling() { if (this.pollTimer) { clearInterval(this.pollTimer); this.pollTimer = null; } },
    formatStatus(status) { return { '0': '待处理', '1': '处理中', '2': '成功', '3': '失败' }[status] || status; },
    formatKnowledgeStatus(status) { return { UNPUBLISHED: '未发布', QUEUED: '排队中', PUBLISHING: '发布中', PUBLISHED: '已发布', FAILED: '发布失败' }[status] || '未发布'; },
    knowledgeTagType(status) { return { UNPUBLISHED: 'info', QUEUED: 'warning', PUBLISHING: 'warning', PUBLISHED: 'success', FAILED: 'danger' }[status] || 'info'; },
    isKnowledgeActive(row) { return row.knowledgeStatus === 'QUEUED' || row.knowledgeStatus === 'PUBLISHING'; },
    canPublish(row) { return row.status === '2' && (row.knowledgeStatus === 'UNPUBLISHED' || row.knowledgeStatus === 'FAILED'); },
    reset() { this.form = { id: undefined, taskName: undefined, remark: undefined }; this.resetForm("form"); },
    resetParse() { this.parseForm = { taskName: "", file: null }; this.fileList = []; if (this.$refs.upload) this.$refs.upload.clearFiles(); },
    cancel() { this.open = false; this.reset(); },
    cancelParse() { this.parseOpen = false; this.resetParse(); },
    handleQuery() { this.queryParams.pageNum = 1; this.getList(); },
    resetQuery() { this.resetForm("queryForm"); this.handleQuery(); },
    handleSelectionChange(selection) { this.ids = selection.map(item => item.id); this.single = selection.length !== 1; this.multiple = !selection.length; },
    handleAdd() { this.resetParse(); this.parseOpen = true; },
    handleFileChange(file, fileList) { this.parseForm.file = file.raw; this.fileList = fileList.slice(-1); },
    handleFileRemove() { this.parseForm.file = null; this.fileList = []; },
    handleFileExceed() { this.$modal.msgWarning("一次只能上传一个文件"); },
    submitParse() {
      if (!this.parseForm.file) { this.$modal.msgWarning("请选择 PDF 或 PPTX 文件"); return; }
      const formData = new FormData(); formData.append("file", this.parseForm.file);
      if (this.parseForm.taskName && this.parseForm.taskName.trim()) formData.append("taskName", this.parseForm.taskName.trim());
      this.parseLoading = true;
      parsePdf(formData).then(() => { this.$modal.msgSuccess("上传成功，解析任务已开始"); this.parseOpen = false; this.resetParse(); this.getList(); }).finally(() => { this.parseLoading = false; });
    },
    handlePublishKnowledge(row) {
      const action = row.knowledgeStatus === 'FAILED' ? '重新发布' : '发布到知识库';
      this.$modal.confirm('是否确认' + action + '任务“' + row.taskName + '”？将使用已保存的解析结果，不会重新解析文件。').then(() => publishPdfKnowledge(row.id)).then(() => {
        this.$modal.msgSuccess('知识库发布任务已提交');
        this.getList();
      }).catch(() => {});
    },
    handleRetryParse(row) {
      this.$modal.confirm('是否确认重新解析任务“' + row.taskName + '”？将复用已保存的原始文件。').then(() => retryPdfParse(row.id)).then(() => {
        this.$modal.msgSuccess('解析任务已重新提交');
        this.getList();
      }).catch(() => {});
    },
    handleUpdate(row) { this.reset(); getPdfParse(row.id || this.ids).then(response => { this.form = response.data; this.open = true; this.title = "修改PDF解析"; }); },
    submitForm() { this.$refs.form.validate(valid => { if (valid) { const request = this.form.id !== undefined ? updatePdfParse(this.form) : addPdfParse(this.form); request.then(() => { this.$modal.msgSuccess(this.form.id !== undefined ? "修改成功" : "新增成功"); this.open = false; this.getList(); }); } }); },
    handleViewResult(row) { getPdfParse(row.id).then(response => { this.resultTask = response.data || {}; this.resultJsonText = this.prettyJson(this.resultTask.resultJson); this.resultOpen = true; }); },
    prettyJson(value) { if (!value) return ""; try { return JSON.stringify(JSON.parse(value), null, 2); } catch (e) { return value; } },
    handleDelete(row) { const ids = row.id || this.ids; this.$modal.confirm('是否确认删除编号为"' + ids + '"的数据项？').then(() => delPdfParse(ids)).then(() => { this.getList(); this.$modal.msgSuccess("删除成功"); }).catch(() => {}); },
    handleExport() { this.download('/business/data/pdf/export', { ...this.queryParams }, `PDF解析_${new Date().getTime()}.xlsx`); }
  }
};
</script>

<style scoped>
.result-error { margin: 16px 0; }
.empty-result { color: #909399; margin-top: 16px; }
.knowledge-stage { margin-top: 4px; color: #909399; font-size: 12px; white-space: nowrap; }
</style>
