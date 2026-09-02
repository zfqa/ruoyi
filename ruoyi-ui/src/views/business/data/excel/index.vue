<template>
  <div class="app-container">
    <el-card class="mb8" shadow="never">
      <div slot="header" class="clearfix">
        <span>上传解析</span>
      </div>
      <el-row :gutter="12">
        <el-col :span="6">
          <div class="upload-label">当前文件（4Q25 with 3Q25 Results）</div>
          <file-upload
            v-model="uploadForm.fileName"
            action="/business/data/excel/upload"
            :limit="1"
            :fileSize="10"
            :fileType="['xlsx', 'xlsm', 'csv']"
            :drag="false"
          />
        </el-col>
        <el-col :span="6">
          <div class="upload-label">Y22基准文件（1Q25 with 4Q24 Results）</div>
          <file-upload
            v-model="uploadForm.baselineFileName"
            action="/business/data/excel/upload"
            :limit="1"
            :fileSize="10"
            :fileType="['xlsx', 'xlsm']"
            :drag="false"
          />
        </el-col>
        <el-col :span="6">
          <div class="upload-label">客户/区域文件（Supply Chain 4Q25）</div>
          <file-upload
            v-model="uploadForm.supplyChainFileName"
            action="/business/data/excel/upload"
            :limit="1"
            :fileSize="10"
            :fileType="['xlsx', 'xlsm']"
            :drag="false"
          />
        </el-col>
        <el-col :span="6" class="upload-actions">
          <el-button type="primary" icon="el-icon-magic-stick" :loading="parsing" :disabled="!uploadForm.fileName" @click="handleParseUploaded">解析已上传文件</el-button>
          <div class="upload-tip">未上传Y22基准文件时，第二部分的Y22显示为缺失。</div>
        </el-col>
      </el-row>
    </el-card>

    <el-form :inline="true" size="small" class="parse-form">
      <el-form-item label="Excel/CSV文件">
        <el-input
          v-model="parseForm.filePath"
          clearable
          style="width: 720px"
          placeholder="请输入导入目录内的 .xlsx/.xlsm/.csv 文件路径"
        />
      </el-form-item>
      <el-form-item label="Y22基准文件">
        <el-input
          v-model="parseForm.baselineFilePath"
          clearable
          style="width: 720px"
          placeholder="可选：1Q25 with 4Q24 Results文件路径"
        />
      </el-form-item>
      <el-form-item label="Supply Chain">
        <el-input
          v-model="parseForm.supplyChainFilePath"
          clearable
          style="width: 720px"
          placeholder="可选：Supply Chain 4Q25 with 3Q25 Results文件路径"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-cpu" :loading="parsing" @click="handleParse">解析</el-button>
      </el-form-item>
      <el-form-item label="单元格层">
        <el-checkbox v-model="parseOptions.includeRawCells">输出</el-checkbox>
      </el-form-item>
      <el-form-item label="结构识别">
        <el-checkbox v-model="parseOptions.useLlm">LLM增强</el-checkbox>
      </el-form-item>
      <el-form-item v-if="parseOptions.includeRawCells" label="范围">
        <el-select v-model="parseOptions.rawCellMode" style="width: 120px">
          <el-option label="非空" value="non-empty" />
          <el-option label="全部" value="all" />
        </el-select>
      </el-form-item>
    </el-form>

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
    <el-button
      v-if="parseResult && parseResult.report_id"
      class="mb8"
      type="success"
      plain
      icon="el-icon-document"
      @click="viewGeneratedReport"
    >查看自动生成的第一份报告</el-button>

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
import { listExcelImport, getExcelImport, getExcelImportResult, getExcelImportStatus, addExcelImport, updateExcelImport, delExcelImport, exportExcelImport, parseLocalExcel, parseUploadExcel } from "@/api/business/data/excel/excelImport";

export default {
  name: "ExcelImport",
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
      taskProgress: 0,
      taskRemark: "",
      pollTimer: null,
      parseResult: null,
      uploadForm: {
        fileName: "",
        baselineFileName: "",
        supplyChainFileName: ""
      },
      parseOptions: {
        includeRawCells: false,
        rawCellMode: "non-empty",
        useLlm: true
      },
      parseForm: {
        filePath: "",
        baselineFilePath: "",
        supplyChainFilePath: ""
      },
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
    parseSummary() {
      if (!this.parseResult) {
        return "";
      }
      if (this.parseResult.sheet_count !== undefined) {
        const quality = this.parseResult.quality || {};
        const llm = quality.llm_used ? `，LLM增强 ${quality.llm_success_count || 0} 个候选表` : (quality.llm_requested ? '，LLM不可用或已降级为规则解析' : '');
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
    handleParse() {
      if (!this.parseForm.filePath) {
        this.$modal.msgError("请输入Excel文件路径");
        return;
      }
      this.parsing = true;
      this.taskProgress = 0;
      this.taskRemark = "正在提交解析任务";
      parseLocalExcel(this.parseForm.filePath, {
        ...this.parseOptions,
        baselineFilePath: this.parseForm.baselineFilePath || undefined,
        supplyChainFilePath: this.parseForm.supplyChainFilePath || undefined
      }).then(response => {
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
    handleParseUploaded() {
      if (!this.uploadForm.fileName) {
        this.$modal.msgError("请先上传Excel文件");
        return;
      }
      this.parsing = true;
      this.taskProgress = 0;
      this.taskRemark = "正在提交解析任务";
      parseUploadExcel(this.uploadForm.fileName, {
        ...this.parseOptions,
        baselineFileName: this.uploadForm.baselineFileName || undefined,
        supplyChainFileName: this.uploadForm.supplyChainFileName || undefined
      }).then(response => {
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
.parse-form {
  margin-bottom: 12px;
}
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
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}
.upload-label {
  margin-bottom: 8px;
  color: #606266;
  font-size: 13px;
}
.upload-tip {
  color: #909399;
  font-size: 12px;
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
