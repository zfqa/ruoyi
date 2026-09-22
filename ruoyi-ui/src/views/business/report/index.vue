<template>
  <div class="app-container">
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
        <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd" v-hasPermi="['business:report:add']">新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="success" plain icon="el-icon-edit" size="mini" :disabled="single" @click="handleUpdate" v-hasPermi="['business:report:edit']">修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="danger" plain icon="el-icon-delete" size="mini" :disabled="multiple" @click="handleDelete" v-hasPermi="['business:report:remove']">删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="warning" plain icon="el-icon-download" size="mini" @click="handleExport" v-hasPermi="['business:report:export']">导出</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="aiReportList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="主键" align="center" prop="id" />
      <el-table-column label="任务名称" align="center" prop="taskName" :show-overflow-tooltip="true" />
      <el-table-column label="报告类型" align="center" prop="reportType" width="180" />
      <el-table-column label="生成模式" align="center" prop="generationMode" width="190" />
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
          <el-button v-if="String(scope.row.status) === '2'" size="mini" type="text" icon="el-icon-view" @click="handleView(scope.row)">查看报告</el-button>
          <el-button v-if="String(scope.row.status) === '2'" size="mini" type="text" icon="el-icon-document" @click="handleOfficeExport(scope.row, 'word')" v-hasPermi="['business:report:export']">Word</el-button>
          <el-button v-if="String(scope.row.status) === '2'" size="mini" type="text" icon="el-icon-data-analysis" @click="handleOfficeExport(scope.row, 'ppt')" v-hasPermi="['business:report:export']">PPT</el-button>
          <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)" v-hasPermi="['business:report:edit']">修改</el-button>
          <el-button size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)" v-hasPermi="['business:report:remove']">删除</el-button>
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

    <!-- 添加或修改AI分析报告对话框 -->
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

    <el-dialog title="竞争社洞察报告" :visible.sync="reportOpen" width="90%" top="4vh" append-to-body>
      <div v-if="reportData" class="report-viewer">
        <h2>{{ reportData.title }}</h2>
        <div class="report-export-actions" v-hasPermi="['business:report:export']">
          <el-button type="primary" size="small" icon="el-icon-document" :loading="exportingFormat === 'word'" @click="handleOfficeExport(currentReport, 'word')">导出 Word</el-button>
          <el-button type="success" size="small" icon="el-icon-data-analysis" :loading="exportingFormat === 'ppt'" @click="handleOfficeExport(currentReport, 'ppt')">导出 PPT</el-button>
        </div>
        <el-alert
          v-if="reportData.quality && reportData.quality.data_gaps && reportData.quality.data_gaps.length"
          type="warning"
          :closable="false"
          :title="`数据缺口：${reportData.quality.data_gaps.join('；')}`"
          show-icon
        />
        <el-alert
          v-if="reportData.quality && reportData.quality.warnings && reportData.quality.warnings.length"
          type="info"
          :closable="false"
          :title="`生成提示：${reportData.quality.warnings.join('；')}`"
          show-icon
        />
        <el-collapse v-if="methodologyNotes.length" class="methodology-panel">
          <el-collapse-item title="数据口径与计算说明" name="methodology">
            <ul><li v-for="(item, index) in methodologyNotes" :key="`method-${index}`">{{ item }}</li></ul>
            <div v-if="methodologyLimitations.length" class="table-note">
              限制说明：{{ methodologyLimitations.join('；') }}
            </div>
          </el-collapse-item>
        </el-collapse>
        <h3>管理层摘要</h3>
        <ul><li v-for="(item, index) in reportData.executive_summary || []" :key="`summary-${index}`">{{ cleanNarrative(item) }}</li></ul>
        <h3>{{ marketSummaryTitle }}</h3>
        <el-table v-if="marketMetricRows.length" :data="marketMetricRows" size="mini" border class="metric-table">
          <el-table-column label="范围" min-width="120"><template>市场合计</template></el-table-column>
          <el-table-column :label="marketPriorColumnLabel" align="right"><template slot-scope="scope">{{ metricValue(scope.row, '2024') }}</template></el-table-column>
          <el-table-column :label="marketCurrentColumnLabel" align="right"><template slot-scope="scope">{{ metricValue(scope.row, '2025') }}</template></el-table-column>
          <el-table-column label="同比" align="right"><template slot-scope="scope">{{ formatPercent(scope.row.yoy_2025_vs_2024) }}</template></el-table-column>
        </el-table>
        <el-table v-if="summaryMatrixDisplayRows.length" :data="summaryMatrixDisplayRows" size="mini" border class="summary-matrix" :span-method="summarySpanMethod" :row-class-name="summaryRowClassName">
          <el-table-column label="产品线" prop="label" fixed width="110"><template slot-scope="scope"><span>{{ scope.row.label }}</span></template></el-table-column>
          <el-table-column label="市场" align="center">
            <el-table-column label="YoY" width="78" align="right"><template slot-scope="scope">{{ formatPercent((scope.row.market || {}).yoy_2025_vs_2024, 0) }}</template></el-table-column>
            <el-table-column label="细分占比" width="88" align="right"><template slot-scope="scope">{{ formatPercent(((scope.row.market || {}).total_market_share || {})['2025'], 0) }}</template></el-table-column>
          </el-table-column>
          <el-table-column v-for="makerName in summaryMakers" :key="makerName" :label="makerName" align="center">
            <el-table-column label="YoY" width="78" align="right"><template slot-scope="scope">{{ formatPercent(summaryMakerMetric(scope.row, makerName).yoy_2025_vs_2024, 0) }}</template></el-table-column>
            <el-table-column label="内部占比" width="88" align="right"><template slot-scope="scope">{{ formatPercent((summaryMakerMetric(scope.row, makerName).internal_share || {})['2025'], 0) }}</template></el-table-column>
            <el-table-column label="细分市场占比" width="108" align="right"><template slot-scope="scope">{{ formatPercent((summaryMakerMetric(scope.row, makerName).same_size_market_share || {})['2025'], 1) }}</template></el-table-column>
          </el-table-column>
        </el-table>
        <ul><li v-for="(item, index) in (reportData.market_summary || {}).insights || []" :key="`market-${index}`">{{ cleanNarrative(item) }}</li></ul>
        <el-tabs v-if="availableMakerSections.length" v-model="activeMaker" type="card" class="maker-tabs" @tab-click="handleMakerTabChange">
          <el-tab-pane v-for="makerName in availableMakerSections" :key="makerName" :label="`${makerName} 洞察`" :name="makerName" />
        </el-tabs>
        <section v-if="tianmaHistoryAvailable" class="history-section">
          <h3>{{ activeMaker }}前装出货、面积及市占率</h3>
          <el-table :data="tianmaHistoryRows" size="mini" border class="metric-table">
            <el-table-column label="指标" prop="label" min-width="160" fixed />
            <el-table-column v-for="period in historyPeriods" :key="period" :label="periodDisplayLabel(period)" min-width="105" align="right">
              <template slot-scope="scope">{{ historyValue(scope.row, period) }}</template>
            </el-table-column>
            <el-table-column label="Y25F YoY（全年）" min-width="130" align="right">
              <template slot-scope="scope">{{ formatPercent(scope.row.metric.standard_y25f_yoy, 1) }}</template>
            </el-table-column>
            <el-table-column :label="isFullYearReport ? '前三季度完成率' : '前三季度/全年预测'" min-width="145" align="right">
              <template slot-scope="scope">{{ formatPercent(scope.row.metric.forecast_completion_y25_q1_q3, 1) }}</template>
            </el-table-column>
          </el-table>
          <el-row :gutter="20">
            <el-col :span="12"><div ref="shipmentVolumeChart" class="history-chart" /></el-col>
            <el-col :span="12"><div ref="displayAreaVolumeChart" class="history-chart" /></el-col>
          </el-row>
          <el-row :gutter="20">
            <el-col :span="12"><div ref="shipmentShareChart" class="history-chart" /></el-col>
            <el-col :span="12"><div ref="displayAreaShareChart" class="history-chart" /></el-col>
          </el-row>
          <div v-for="(group, groupName) in (tianmaHistory.insights || {})" :key="groupName">
            <ul><li v-for="(item, index) in group || []" :key="`${groupName}-${index}`">{{ cleanNarrative(item) }}</li></ul>
          </div>
        </section>
        <section v-if="tianmaProductAvailable" class="history-section">
          <h3>{{ activeMaker }}增长点分析一：产品线</h3>
          <div v-for="(group, groupName) in (tianmaProduct.insights || {})" :key="`product-${groupName}`">
            <ul><li v-for="(item, index) in group || []" :key="`${groupName}-${index}`">{{ cleanNarrative(item) }}</li></ul>
          </div>
          <el-row :gutter="12" class="product-dashboard">
            <el-col :span="7"><div ref="technologyHistoryChart" class="product-chart product-chart--main" /></el-col>
            <el-col :span="9"><div ref="sizeDistributionChart" class="product-chart product-chart--main" /></el-col>
            <el-col :span="8">
              <div ref="ltpsSizeGrowthChart" class="product-chart product-chart--small" />
              <div ref="asiSizeGrowthChart" class="product-chart product-chart--small" />
            </el-col>
          </el-row>
        </section>
        <section v-if="tianmaCustomerAvailable" class="history-section">
          <h3>{{ activeMaker }}增长点分析二：客户/区域</h3>
          <div v-for="(group, groupName) in (tianmaCustomer.insights || {})" :key="`customer-${groupName}`">
            <ul><li v-for="(item, index) in group || []" :key="`${groupName}-${index}`">{{ cleanNarrative(item) }}</li></ul>
          </div>
          <el-row :gutter="18">
            <el-col :span="12">
              <div ref="customerChart" class="growth-chart" />
              <el-table :data="customerClientRows" size="mini" border class="metric-table compact-table">
                <el-table-column label="客户" prop="client" min-width="105" />
                <el-table-column :label="summaryPeriodLabel" align="right"><template slot-scope="scope">{{ formatQty(clientPrimaryQty(scope.row)) }}</template></el-table-column>
                <el-table-column label="同比" align="right"><template slot-scope="scope">{{ formatPercent(clientPrimaryYoy(scope.row), 0) }}</template></el-table-column>
                <el-table-column label="内部占比" align="right"><template slot-scope="scope">{{ formatPercent(clientPrimaryShare(scope.row), 0) }}</template></el-table-column>
                <el-table-column label="份额变化" align="right"><template slot-scope="scope">{{ formatPoints(scope.row.share_change_points) }}</template></el-table-column>
                <el-table-column label="增长贡献" align="right"><template slot-scope="scope">{{ formatPercent(clientPrimaryGrowth(scope.row), 0) }}</template></el-table-column>
              </el-table>
            </el-col>
            <el-col :span="12">
              <h4>{{ activeMaker }} 区域别占比情况（客户决策地）</h4>
              <el-table :data="customerRegionRows" size="mini" border class="metric-table compact-table">
                <el-table-column label="区域" prop="region" width="72" />
                <el-table-column label="Y24出货量" align="right"><template slot-scope="scope">{{ formatQty((scope.row.annual || {}).Y24) }}</template></el-table-column>
                <el-table-column label="同比" align="right"><template slot-scope="scope">{{ formatPercent((scope.row.annual || {}).yoy_2024_vs_2023, 0) }}</template></el-table-column>
                <el-table-column :label="summaryPeriodLabel" align="right"><template slot-scope="scope">{{ formatQty(regionPrimaryQty(scope.row)) }}</template></el-table-column>
                <el-table-column label="同比" align="right"><template slot-scope="scope">{{ formatPercent(regionPrimaryYoy(scope.row), 0) }}</template></el-table-column>
              </el-table>
              <el-table :data="customerRegionRows" size="mini" border class="metric-table compact-table">
                <el-table-column label="区域" prop="region" width="72" />
                <el-table-column label="Y24 LTPS" align="right"><template slot-scope="scope">{{ directionValue(scope.row, 'Y24', 'LTPS') }}</template></el-table-column>
                <el-table-column label="Y24 a-Si" align="right"><template slot-scope="scope">{{ directionValue(scope.row, 'Y24', 'a-Si') }}</template></el-table-column>
                <el-table-column :label="`${summaryPeriodLabel} LTPS`" align="right"><template slot-scope="scope">{{ directionValue(scope.row, regionTechPeriodKey, 'LTPS') }}</template></el-table-column>
                <el-table-column :label="`${summaryPeriodLabel} a-Si`" align="right"><template slot-scope="scope">{{ directionValue(scope.row, regionTechPeriodKey, 'a-Si') }}</template></el-table-column>
              </el-table>
            </el-col>
          </el-row>
        </section>
        <section v-if="tianmaApplicationAvailable" class="history-section">
          <h3>{{ activeMaker }}增长点分析三：应用</h3>
          <div v-for="(group, groupName) in (tianmaApplication.insights || {})" :key="`application-${groupName}`">
            <ul><li v-for="(item, index) in group || []" :key="`${groupName}-${index}`">{{ cleanNarrative(item) }}</li></ul>
          </div>
          <el-row :gutter="18">
            <el-col :span="12">
              <div ref="applicationChart" class="growth-chart" />
              <el-table :data="applicationSeries" size="mini" border class="metric-table compact-table">
                <el-table-column label="YoY" prop="application" width="82" />
                <el-table-column v-for="period in historyPeriods" :key="`app-yoy-${period}`" :label="periodDisplayLabel(period)" align="right">
                  <template slot-scope="scope">{{ formatPercent((scope.row.yoy_periods || {})[period], 0) }}</template>
                </el-table-column>
                <el-table-column :label="isFullYearReport ? 'Y25全年占比' : 'Y25占比'" align="right"><template slot-scope="scope">{{ formatPercent(appPrimaryShare(scope.row), 0) }}</template></el-table-column>
                <el-table-column label="增长贡献" align="right"><template slot-scope="scope">{{ formatPercent(appPrimaryGrowth(scope.row), 0) }}</template></el-table-column>
                <el-table-column label="面积占比" align="right"><template slot-scope="scope">{{ formatPercent(appAreaPrimaryShare(scope.row), 0) }}</template></el-table-column>
              </el-table>
            </el-col>
            <el-col :span="12">
              <h4>{{ activeMaker }} 应用别重点尺寸 {{ summaryPeriodLabel }}出货占比情况</h4>
              <el-table :data="applicationKeySizeRows" size="mini" border class="metric-table compact-table">
                <el-table-column label="应用" prop="application" width="72" />
                <el-table-column label="尺寸" prop="size" width="68" align="right" />
                <el-table-column label="技术" prop="technology" width="92" />
                <el-table-column label="出货量" align="right"><template slot-scope="scope">{{ formatQty(scope.row.shipment) }}</template></el-table-column>
                <el-table-column label="占比" align="right"><template slot-scope="scope">{{ formatPercent(scope.row.share, 0) }}</template></el-table-column>
                <el-table-column label="同比" align="right"><template slot-scope="scope">{{ formatPercent(scope.row.yoy_primary != null ? scope.row.yoy_primary : scope.row.yoy_2025_q1_q3_vs_2024_q1_q3, 0) }}</template></el-table-column>
              </el-table>
              <div class="table-note">*展示口径：按终稿约定的应用、尺寸及Technology组合，从字段匹配记录汇总；不依赖工作表单元格位置。</div>
            </el-col>
          </el-row>
        </section>
        <el-card v-for="maker in reportData.makers || []" :key="maker.maker" class="maker-card" shadow="never">
          <div slot="header"><strong>{{ maker.maker }} 洞察</strong></div>
          <p v-for="(item, index) in maker.overview || []" :key="`overview-${index}`">{{ cleanNarrative(item) }}</p>
          <el-table v-if="makerShipmentRows(maker).length" :data="makerShipmentRows(maker)" size="mini" border class="metric-table">
            <el-table-column :label="isFullYearReport ? 'Y24全年（千片）' : 'Y24 Q1-Q3（千片）'" align="right"><template slot-scope="scope">{{ metricValue(scope.row, '2024') }}</template></el-table-column>
            <el-table-column :label="marketCurrentColumnLabel" align="right"><template slot-scope="scope">{{ metricValue(scope.row, '2025') }}</template></el-table-column>
            <el-table-column label="同比" align="right"><template slot-scope="scope">{{ formatPercent(scope.row.yoy_2025_vs_2024) }}</template></el-table-column>
            <el-table-column :label="isFullYearReport ? 'Y25全年市场份额' : 'Y25市场份额'" align="right"><template slot-scope="scope">{{ formatPercent((scope.row.market_share || {})['2025']) }}</template></el-table-column>
          </el-table>
          <el-row :gutter="16" class="insight-row">
            <el-col :span="6"><h4>全局态势</h4><ul><li v-for="(item, index) in (maker.global_trend || {}).insights || []" :key="`g-${index}`">{{ cleanNarrative(item) }}</li></ul></el-col>
            <el-col :span="6"><h4>技术与尺寸</h4><ul><li v-for="(item, index) in (maker.product_line || {}).insights || []" :key="`t-${index}`">{{ cleanNarrative(item) }}</li></ul></el-col>
            <el-col :span="6"><h4>客户与区域</h4><ul><li v-for="(item, index) in (maker.customer_region || {}).insights || []" :key="`r-${index}`">{{ cleanNarrative(item) }}</li></ul></el-col>
            <el-col :span="6"><h4>应用</h4><ul><li v-for="(item, index) in (maker.application || {}).insights || []" :key="`i-${index}`">{{ cleanNarrative(item) }}</li></ul></el-col>
          </el-row>
          <div v-if="hasDriverNarratives(maker)" class="driver-narratives">
            <h4>出货变化背后的主要因素</h4>
            <p v-if="(maker.driver_narratives || {}).product"><strong>驱动力一（产品）：</strong>{{ cleanNarrative(maker.driver_narratives.product) }}</p>
            <p v-if="(maker.driver_narratives || {}).customer"><strong>驱动力二（客户）：</strong>{{ cleanNarrative(maker.driver_narratives.customer) }}</p>
            <p v-if="(maker.driver_narratives || {}).application"><strong>驱动力三（应用）：</strong>{{ cleanNarrative(maker.driver_narratives.application) }}</p>
          </div>
          <el-row :gutter="16">
            <el-col :span="8"><h4>产品驱动力要点</h4><ul><li v-for="(item, index) in shortDriverBullets(maker, 'product')" :key="`p-${index}`">{{ cleanNarrative(item) }}</li></ul></el-col>
            <el-col :span="8"><h4>客户驱动力要点</h4><ul><li v-for="(item, index) in shortDriverBullets(maker, 'customer')" :key="`c-${index}`">{{ cleanNarrative(item) }}</li></ul></el-col>
            <el-col :span="8"><h4>应用驱动力要点</h4><ul><li v-for="(item, index) in shortDriverBullets(maker, 'application')" :key="`a-${index}`">{{ cleanNarrative(item) }}</li></ul></el-col>
          </el-row>
        </el-card>
        <el-collapse v-if="(reportData.narrative_sources || []).length" class="narrative-sources">
          <el-collapse-item title="洞察结论引用来源" name="narrative-sources">
            <div v-for="source in reportData.narrative_sources" :key="source.citation_label" class="narrative-source-row">
              <div><el-tag size="mini" type="success">[{{ source.citation_label }}]</el-tag> {{ source.conclusion }}</div>
              <div class="source-file">源文件：{{ source.source_file || '未记录文件名' }}</div>
              <div v-for="metric in source.metrics || []" :key="metric.metric_id" class="metric-source">
                <code>{{ metric.metric_id }}</code>
                <div v-for="(location, locationIndex) in evidenceLocations(metric.evidence)" :key="`${metric.metric_id}-${locationIndex}`">
                  {{ location }}
                </div>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
        <el-collapse>
          <el-collapse-item title="完整结构化报告 JSON" name="json"><pre class="report-json">{{ JSON.stringify(reportData, null, 2) }}</pre></el-collapse-item>
        </el-collapse>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listAiReport, getAiReport, addAiReport, updateAiReport, delAiReport, exportAiReport, exportAiReportOffice } from "@/api/business/report/aiReport";
import { blobValidate } from '@/utils/ruoyi';
import * as echarts from 'echarts';
require('echarts/theme/macarons');

export default {
  name: "AiReport",
  data() {
    return {
      loading: true,
      ids: [],
      single: true,
      multiple: true,
      showSearch: true,
      total: 0,
      aiReportList: [],
      title: "",
      open: false,
      reportOpen: false,
      reportData: null,
      currentReport: null,
      exportingFormat: '',
      activeMaker: 'Tianma',
      shipmentVolumeChart: null,
      displayAreaVolumeChart: null,
      shipmentShareChart: null,
      displayAreaShareChart: null,
      technologyHistoryChart: null,
      sizeDistributionChart: null,
      ltpsSizeGrowthChart: null,
      asiSizeGrowthChart: null,
      customerChart: null,
      applicationChart: null,
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
  created() {
    this.getList();
    if (this.$route.query.reportId) {
      this.openReportById(this.$route.query.reportId);
    }
  },
  beforeDestroy() {
    this.disposeHistoryCharts();
  },
  computed: {
    summaryMakers() {
      const report = this.reportData || {};
      const configured = (((report.methodology || {}).scope || {}).makers || []);
      const sections = Object.keys(report.maker_sections || {});
      const discovered = [...configured, ...sections].filter(Boolean);
      const defaults = ['Tianma', 'AUO', 'CSOT', 'BOE'];
      return [...new Set([...defaults, ...discovered])];
    },
    methodologyNotes() {
      return (((this.reportData || {}).methodology || {}).notes || []);
    },
    methodologyLimitations() {
      return (((this.reportData || {}).methodology || {}).limitations || []);
    },
    reportScope() {
      return (((this.reportData || {}).methodology || {}).scope || {});
    },
    isFullYearReport() {
      const scope = this.reportScope;
      // Explicit non-full-year from backend must win (pivot may still contain forecast Q4).
      if (scope.full_year === false || scope.full_year_2025 === false) {
        return String(scope.report_horizon || '').endsWith('_full_year')
          || String(scope.summary_mode || '').endsWith('full_year')
          || String(scope.header_period_label || '').includes('全年');
      }
      const makerScopes = [
        ((this.activeMakerDetail || {}).history || {}).scope,
        ((this.activeMakerDetail || {}).product || {}).scope,
        ((this.activeMakerDetail || {}).customer || {}).scope,
        ((this.activeMakerDetail || {}).application || {}).scope
      ].filter(Boolean);
      const makerFullYear = makerScopes.some(item => item.full_year || String(item.primary_period || '') === 'Y25F');
      return Boolean(
        scope.full_year
        || scope.full_year_2025
        || makerFullYear
        || String(scope.report_horizon || '').endsWith('_full_year')
        || String(scope.summary_mode || '').endsWith('full_year')
        || String(scope.header_period_label || '').includes('全年')
        || String((this.reportData || {}).title || '').includes('全年')
      );
    },
    summaryPeriodLabel() {
      const label = this.reportScope.header_period_label
      if (label) return label
      return this.isFullYearReport ? 'Y25全年' : 'Y25前三季度'
    },
    summaryPeriodKey() {
      return this.isFullYearReport ? 'Y25F' : 'Y25Q1-Q3';
    },
    regionTechPeriodKey() {
      return this.isFullYearReport ? 'Y25F' : 'Y25Q1-Q3';
    },
    makerChartPeriods() {
      const candidates = [
        (((this.tianmaHistory || {}).scope || {}).period_order) || [],
        (((this.tianmaProduct || {}).scope || {}).period_order) || [],
        (((this.tianmaCustomer.top_clients || {}).period_order) || []),
        (((this.tianmaApplication.application_history || {}).period_order) || []),
        this.reportScope.focus_periods || []
      ];
      let periods = [];
      candidates.forEach(list => {
        (list || []).forEach(period => {
          if (period && !periods.includes(period)) periods.push(period);
        });
      });
      if (!periods.length) {
        periods = ['Y22', 'Y23', 'Y24', 'Y25F', 'Y25Q1-Q3'];
      }
      if (this.isFullYearReport) {
        periods = periods.filter(period => period !== 'Y25F');
        const q1q3Index = periods.findIndex(period => period === 'Y25Q1-Q3');
        if (q1q3Index >= 0) periods.splice(q1q3Index, 0, 'Y25F');
        else {
          const y24Index = periods.findIndex(period => period === 'Y24');
          if (y24Index >= 0) periods.splice(y24Index + 1, 0, 'Y25F');
          else periods.push('Y25F');
        }
      }
      return periods;
    },
    historyPeriods() {
      return this.makerChartPeriods;
    },
    marketSummaryTitle() {
      if (this.reportScope.omdia_data_through_label && this.isFullYearReport) {
        return `Y25全年总览（数据截止 ${this.reportScope.omdia_data_through_label}）`;
      }
      if (this.isFullYearReport) return 'Y25全年总览';
      return 'Y25前三季度总览';
    },
    marketPriorColumnLabel() {
      return this.isFullYearReport ? 'Y24全年（千片）' : 'Y24前三季度（千片）';
    },
    marketCurrentColumnLabel() {
      return this.isFullYearReport ? 'Y25全年（千片）' : 'Y25前三季度（千片）';
    },
    marketMetricRows() {
      const rows = ((this.reportData || {}).market_summary || {}).rows || [];
      return rows.filter(Boolean);
    },
    summaryMatrixRows() {
      return ((((this.reportData || {}).market_summary || {}).summary_matrix || {}).rows || []);
    },
    summaryMatrixDisplayRows() {
      const displayRows = [];
      let currentTechnology = null;
      this.summaryMatrixRows.forEach(row => {
        const rowKey = row && row.row_key;
        const technology = (row && row.technology)
          || (rowKey && rowKey.indexOf('ltps.') === 0 ? 'LTPS' : null)
          || (rowKey && rowKey.indexOf('a_si.') === 0 ? 'a-Si' : null);
        if (technology && technology !== currentTechnology) {
          displayRows.push({
            _section: true,
            label: technology === 'a-Si' ? 'A-Si' : technology
          });
          currentTechnology = technology;
        }
        displayRows.push(row);
      });
      return displayRows;
    },
    makerSectionMap() {
      const report = this.reportData || {};
      if (report.maker_sections && Object.keys(report.maker_sections).length) return report.maker_sections;
      return {
        Tianma: {
          history: report.tianma_history || {},
          product: report.tianma_product || {},
          customer: report.tianma_customer || {},
          application: report.tianma_application || {}
        }
      };
    },
    availableMakerSections() {
      return this.summaryMakers.filter(name => Boolean(this.makerSectionMap[name]));
    },
    activeMakerDetail() {
      return this.makerSectionMap[this.activeMaker] || {};
    },
    tianmaHistory() {
      return this.activeMakerDetail.history || {};
    },
    tianmaHistoryAvailable() {
      return Boolean(this.tianmaHistory.shipment || this.tianmaHistory.display_area);
    },
    tianmaProduct() {
      return this.activeMakerDetail.product || {};
    },
    tianmaProductAvailable() {
      return Boolean(
        this.tianmaProduct.technology_history
        || this.tianmaProduct.size_distribution
        || this.tianmaProduct.y25q1_q3_size_distribution
      );
    },
    tianmaCustomer() {
      return this.activeMakerDetail.customer || {};
    },
    tianmaCustomerAvailable() {
      return Boolean(((this.tianmaCustomer.top_clients || {}).clients || []).length);
    },
    customerRegionRows() {
      return ((this.tianmaCustomer.regions || {}).rows || []);
    },
    customerClientRows() {
      return ((this.tianmaCustomer.top_clients || {}).clients || []);
    },
    tianmaApplication() {
      return this.activeMakerDetail.application || {};
    },
    tianmaApplicationAvailable() {
      return Boolean(((this.tianmaApplication.application_history || {}).series || []).length);
    },
    applicationSeries() {
      return ((this.tianmaApplication.application_history || {}).series || []);
    },
    applicationKeySizeRows() {
      return ((this.tianmaApplication.key_sizes || {}).rows || []);
    },
    tianmaHistoryRows() {
      const history = this.tianmaHistory;
      return [
        { label: 'Shipment（千片）', metric: history.shipment || {} },
        { label: 'Display area（m²）', metric: history.display_area || {} }
      ];
    }
  },
  methods: {
    periodDisplayLabel(period) {
      if (period === 'Y25F') return this.isFullYearReport ? 'Y25全年' : 'Y25F';
      if (period === 'Y25Q1-Q3') return this.isFullYearReport ? 'Y25前三季度（过程）' : 'Y25前三季度';
      return period;
    },
    cleanNarrative(value) {
      if (typeof value !== 'string') return value;
      const text = value.replace(/\s*\[.*\]\s*$/, '').trim();
      const labels = ((this.reportData || {}).narrative_sources || [])
        .filter(item => item.conclusion === text)
        .map(item => `[${item.citation_label}]`);
      return labels.length ? `${text} ${labels.join('')}` : text;
    },
    hasDriverNarratives(maker) {
      const essays = (maker && maker.driver_narratives) || {};
      return Boolean(essays.product || essays.customer || essays.application);
    },
    shortDriverBullets(maker, key) {
      const items = (((maker || {}).drivers || {})[key]) || [];
      return items.filter(item => {
        const text = String(item || '');
        return text && !/^驱动力[一二三]/.test(text);
      });
    },
    evidenceLocations(evidence) {
      const locations = [];
      const visit = (value, path) => {
        if (!value) return;
        if (Array.isArray(value)) {
          value.forEach((item, index) => visit(item, `${path}[${index}]`));
          return;
        }
        if (typeof value !== 'object') return;
        const period = path ? `${path}：` : '';
        if (value.sheet || value.cell || (value.cells && value.cells.length)) {
          const refs = value.cell ? [value.cell] : (value.cells || []);
          locations.push(`${period}${value.sheet || 'Excel'} · ${refs.length ? refs.join('、') : '聚合记录'}${value.source_record_count ? ` · ${value.source_record_count}条` : ''}`);
        }
        if (value.source_refs && value.source_refs.length) {
          locations.push(`${period}${(value.source_roles || []).join('、') || 'Pivot Cache'} · ${value.source_refs.slice(0, 12).join('、')}${value.source_record_count ? ` · ${value.source_record_count}条` : ''}`);
        }
        Object.keys(value).forEach(key => {
          if (!['sheet', 'cell', 'cells', 'source_refs', 'source_roles', 'source_record_count', 'aggregation'].includes(key)) {
            visit(value[key], path ? `${path}.${key}` : key);
          }
        });
      };
      visit(evidence, '');
      return [...new Set(locations)];
    },
    metricValue(metric, year) {
      const value = ((metric || {}).values || {})[year];
      return value === null || value === undefined ? '--' : Number(value).toLocaleString('zh-CN');
    },
    formatPercent(value, digits = 2) {
      return value === null || value === undefined ? '--' : `${(Number(value) * 100).toFixed(digits)}%`;
    },
    formatPoints(value, digits = 1) {
      if (value === null || value === undefined) return '--';
      const points = Number(value) * 100;
      return `${points >= 0 ? '+' : ''}${points.toFixed(digits)}pct`;
    },
    formatQty(value) {
      return value === null || value === undefined ? '--' : `${Number(value).toLocaleString('zh-CN')}K`;
    },
    directionValue(row, period, technology) {
      const metric = ((((row || {}).technology || {})[period] || {})[technology]) || {};
      if (metric.value === null || metric.value === undefined) return '--';
      const arrow = metric.yoy === null || metric.yoy === undefined ? '' : (Number(metric.yoy) >= 0 ? '↑' : '↓');
      return `${Number(metric.value).toLocaleString('zh-CN')}K${arrow}`;
    },
    historyValue(row, period) {
      const value = (((row || {}).metric || {}).periods || {})[period];
      return value === null || value === undefined ? '--' : Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
    },
    makerShipmentRows(maker) {
      const rows = ((maker || {}).global_trend || {}).shipment || [];
      if (!rows.length || !rows[0]) return [];
      const shipment = rows[0];
      const comparison = shipment.comparison_periods || {};
      const periods = (shipment.periods || {});
      const share = (((maker || {}).global_trend || {}).shipment_share || {}).periods || {};
      if (this.isFullYearReport) {
        return [{
          values: {
            '2024': comparison.Y24 || periods.Y24 || comparison['Y24Q1-Q3'],
            '2025': comparison.Y25F || periods.Y25F || comparison['Y25Q1-Q3']
          },
          yoy_2025_vs_2024: shipment.standard_y25f_yoy != null
            ? shipment.standard_y25f_yoy
            : ((shipment.yoy_periods || {}).Y25F != null
              ? (shipment.yoy_periods || {}).Y25F
              : shipment.yoy_2025_q1_q3_vs_2024_q1_q3),
          market_share: { '2025': share.Y25F != null ? share.Y25F : share['Y25Q1-Q3'] }
        }];
      }
      return [{
        values: {
          '2024': comparison['Y24Q1-Q3'],
          '2025': comparison['Y25Q1-Q3']
        },
        yoy_2025_vs_2024: shipment.yoy_2025_q1_q3_vs_2024_q1_q3,
        market_share: { '2025': share['Y25Q1-Q3'] }
      }];
    },
    clientPrimaryQty(row) {
      const periods = (row || {}).periods || {};
      if (this.isFullYearReport && periods.Y25F != null) return periods.Y25F;
      return periods['Y25Q1-Q3'];
    },
    clientPrimaryYoy(row) {
      if (this.isFullYearReport) {
        return row.yoy_primary != null ? row.yoy_primary
          : (row.yoy_2025_full_vs_2024_full != null ? row.yoy_2025_full_vs_2024_full : row.yoy_2025_q1_q3_vs_2024_q1_q3);
      }
      return row.yoy_2025_q1_q3_vs_2024_q1_q3;
    },
    clientPrimaryShare(row) {
      if (this.isFullYearReport) {
        return row.share_primary != null ? row.share_primary
          : (row.share_y25_full != null ? row.share_y25_full : row.share_y25_q1_q3);
      }
      return row.share_y25_q1_q3;
    },
    clientPrimaryGrowth(row) {
      if (this.isFullYearReport) {
        return row.growth_contribution_primary != null ? row.growth_contribution_primary
          : (row.growth_contribution_y25_full != null ? row.growth_contribution_y25_full : row.growth_contribution_y25_q1_q3);
      }
      return row.growth_contribution_y25_q1_q3;
    },
    regionPrimaryQty(row) {
      if (this.isFullYearReport && ((row.full_year || {}).Y25 != null)) return (row.full_year || {}).Y25;
      return ((row.q1_q3 || {})['Y25Q1-Q3']);
    },
    regionPrimaryYoy(row) {
      if (this.isFullYearReport && ((row.full_year || {}).yoy_2025_vs_2024 != null)) {
        return (row.full_year || {}).yoy_2025_vs_2024;
      }
      return ((row.q1_q3 || {}).yoy_2025_vs_2024);
    },
    appPrimaryShare(row) {
      if (this.isFullYearReport) {
        return row.share_primary != null ? row.share_primary
          : (row.share_y25_full != null ? row.share_y25_full : row.share_y25_q1_q3);
      }
      return row.share_y25_q1_q3;
    },
    appPrimaryGrowth(row) {
      if (this.isFullYearReport) {
        return row.growth_contribution_primary != null ? row.growth_contribution_primary
          : (row.growth_contribution_y25_full != null ? row.growth_contribution_y25_full : row.growth_contribution_y25_q1_q3);
      }
      return row.growth_contribution_y25_q1_q3;
    },
    appAreaPrimaryShare(row) {
      const area = (row || {}).display_area || {};
      if (this.isFullYearReport) {
        return area.share_primary != null ? area.share_primary : area.share_y25_q1_q3;
      }
      return area.share_y25_q1_q3;
    },
    summaryMakerMetric(row, makerName) {
      return (((row || {}).makers || {})[makerName]) || {};
    },
    summarySpanMethod({ row, columnIndex }) {
      if (!row || !row._section) return [1, 1];
      const columnCount = 3 + this.summaryMakers.length * 3;
      return columnIndex === 0 ? [1, columnCount] : [0, 0];
    },
    summaryRowClassName({ row }) {
      return row && row._section ? 'summary-section-row' : '';
    },
    handleMakerTabChange(tab) {
      if (tab && tab.name) {
        this.activeMaker = tab.name;
      }
      this.$nextTick(() => this.renderHistoryCharts());
    },
    getList() {
      this.loading = true;
      listAiReport(this.queryParams).then(response => {
        this.aiReportList = response.rows;
        this.total = response.total;
        this.loading = false;
      });
    },
    formatStatus(status) {
      const map = { '0': '待处理', '1': '处理中', '2': '成功', '3': '失败' };
      return map[status] || status;
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
      this.title = "添加AI分析报告";
    },
    handleUpdate(row) {
      this.reset();
      const id = row.id || this.ids;
      getAiReport(id).then(response => {
        this.form = response.data;
        this.open = true;
        this.title = "修改AI分析报告";
      });
    },
    handleView(row) {
      this.openReportById(row.id);
    },
    openReportById(id) {
      getAiReport(id).then(response => {
        this.currentReport = response.data;
        const content = response.data && response.data.reportContent;
        this.reportData = typeof content === 'string' ? JSON.parse(content) : content;
        const sectionNames = Object.keys((this.reportData || {}).maker_sections || {});
        this.activeMaker = sectionNames.includes('Tianma') ? 'Tianma' : (sectionNames[0] || 'Tianma');
        this.reportOpen = true;
        this.$nextTick(() => this.renderHistoryCharts());
      }).catch(() => this.$modal.msgError('报告内容读取失败'));
    },
    renderHistoryCharts() {
      this.disposeHistoryCharts();
      if (!this.tianmaHistoryAvailable && !this.tianmaProductAvailable && !this.tianmaCustomerAvailable && !this.tianmaApplicationAvailable) return;
      if (this.tianmaHistoryAvailable) {
        this.shipmentVolumeChart = this.renderVolumeChart(
          'shipmentVolumeChart',
          this.tianmaHistory.shipment,
          `${this.activeMaker}前装出货情况（Kpcs）`,
          'Kpcs',
          false
        );
        this.displayAreaVolumeChart = this.renderVolumeChart(
          'displayAreaVolumeChart',
          this.tianmaHistory.display_area,
          `${this.activeMaker}前装出货面积情况（㎡）`,
          '㎡',
          true
        );
        this.shipmentShareChart = this.renderShareChart('shipmentShareChart', this.tianmaHistory.shipment_share, `${this.activeMaker}前装出货量市占率`);
        this.displayAreaShareChart = this.renderShareChart('displayAreaShareChart', this.tianmaHistory.display_area_share, `${this.activeMaker}前装出货面积市占率`);
      }
      if (this.tianmaProductAvailable) {
        this.technologyHistoryChart = this.renderTechnologyHistoryChart();
        this.sizeDistributionChart = this.renderSizeDistributionChart();
        this.ltpsSizeGrowthChart = this.renderTechnologySizeChart('ltpsSizeGrowthChart', 'LTPS');
        this.asiSizeGrowthChart = this.renderTechnologySizeChart('asiSizeGrowthChart', 'a-Si');
      }
      if (this.tianmaCustomerAvailable) {
        this.customerChart = this.renderCustomerChart();
      }
      if (this.tianmaApplicationAvailable) {
        this.applicationChart = this.renderApplicationChart();
      }
    },
    renderVolumeChart(refName, metric, title, unitLabel, isArea) {
      const element = this.$refs[refName];
      if (!element || !metric) return null;
      const chart = echarts.init(element, 'macarons');
      const periods = this.historyPeriods || [];
      const values = periods.map(period => {
        const value = (metric.periods || {})[period];
        return value === null || value === undefined ? null : Number(value);
      });
      const yoys = periods.map(period => {
        const value = (metric.yoy_periods || {})[period];
        return value === null || value === undefined ? null : Number((Number(value) * 100).toFixed(2));
      });
      chart.setOption({
        title: { text: title, left: 'center', textStyle: { fontSize: 15 } },
        tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
        legend: { bottom: 0, data: [this.activeMaker, `${this.activeMaker} YoY`] },
        grid: { left: 58, right: 52, top: 55, bottom: 48 },
        xAxis: {
          type: 'category',
          data: periods.map(period => this.periodDisplayLabel(period)),
          axisLabel: { interval: 0, fontSize: 10 }
        },
        yAxis: [
          {
            type: 'value',
            name: unitLabel,
            axisLabel: {
              fontSize: 9,
              formatter: value => isArea
                ? (Number(value) >= 1000 ? `${(Number(value) / 1000).toFixed(1)}K` : Number(value).toLocaleString('zh-CN'))
                : Number(value).toLocaleString('zh-CN')
            }
          },
          {
            type: 'value',
            name: 'YoY',
            axisLabel: { formatter: '{value}%', fontSize: 9 },
            splitLine: { show: false }
          }
        ],
        series: [
          {
            name: this.activeMaker,
            type: 'bar',
            barMaxWidth: 36,
            data: values,
            label: {
              show: true,
              position: 'top',
              fontSize: 9,
              formatter: params => {
                if (params.value === null || params.value === undefined) return '';
                const number = Number(params.value);
                if (isArea) return number >= 1000 ? `${(number / 1000).toFixed(1)}K` : number.toLocaleString('zh-CN');
                return number.toLocaleString('zh-CN');
              }
            }
          },
          {
            name: `${this.activeMaker} YoY`,
            type: 'line',
            yAxisIndex: 1,
            smooth: false,
            symbolSize: 7,
            connectNulls: false,
            data: yoys,
            label: {
              show: true,
              fontSize: 9,
              formatter: params => (params.value === null || params.value === undefined ? '' : `${Number(params.value).toFixed(1)}%`)
            }
          }
        ]
      });
      return chart;
    },
    renderShareChart(refName, metric, title) {
      const element = this.$refs[refName];
      if (!element || !metric) return null;
      const chart = echarts.init(element, 'macarons');
      chart.setOption({
        title: { text: title, left: 'center', textStyle: { fontSize: 15 } },
        tooltip: { trigger: 'axis', valueFormatter: value => value === null ? '--' : `${Number(value).toFixed(2)}%` },
        grid: { left: 55, right: 24, top: 55, bottom: 40 },
        xAxis: { type: 'category', data: this.historyPeriods.map(period => this.periodDisplayLabel(period)) },
        yAxis: { type: 'value', axisLabel: { formatter: '{value}%' } },
        series: [{
          name: this.activeMaker,
          type: 'line',
          smooth: false,
          connectNulls: false,
          symbolSize: 8,
          data: this.historyPeriods.map(period => {
            const value = (metric.periods || {})[period];
            return value === null || value === undefined ? null : Number((Number(value) * 100).toFixed(4));
          })
        }]
      });
      return chart;
    },
    renderTechnologyHistoryChart() {
      const element = this.$refs.technologyHistoryChart;
      const history = this.tianmaProduct.technology_history || {};
      if (!element || (!history.LTPS && !history['a-Si'])) return null;
      const colors = { 'a-Si': '#d9a441', LTPS: '#f3c86a', 'a-Si YoY': '#6f7782', 'LTPS YoY': '#f2a43a' };
      const chart = echarts.init(element, 'macarons');
      const value = (technology, field, period) => {
        const metric = history[technology] || {};
        const item = (metric[field] || {})[period];
        return item === null || item === undefined ? null : Number(item);
      };
      chart.setOption({
        color: [colors['a-Si'], colors.LTPS, colors['a-Si YoY'], colors['LTPS YoY']],
        title: { text: `${this.activeMaker} 技术别出货情况（Kpcs）`, left: 'center', textStyle: { fontSize: 14 } },
        tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
        legend: { bottom: 0, itemWidth: 12, textStyle: { fontSize: 10 } },
        grid: { left: 52, right: 48, top: 48, bottom: 54 },
        xAxis: { type: 'category', data: this.historyPeriods.map(period => this.periodDisplayLabel(period)), axisLabel: { interval: 0, fontSize: 10 } },
        yAxis: [
          { type: 'value', name: 'Kpcs', axisLabel: { fontSize: 9 } },
          { type: 'value', name: 'YoY', axisLabel: { formatter: '{value}%', fontSize: 9 }, splitLine: { show: false } }
        ],
        series: [
          {
            name: 'a-Si', type: 'bar', stack: 'shipment', barMaxWidth: 32,
            data: this.historyPeriods.map(period => value('a-Si', 'periods', period)),
            label: { show: true, position: 'inside', fontSize: 9, formatter: p => p.value ? Number(p.value).toLocaleString('zh-CN') : '' }
          },
          {
            name: 'LTPS', type: 'bar', stack: 'shipment', barMaxWidth: 32,
            data: this.historyPeriods.map(period => value('LTPS', 'periods', period)),
            label: { show: true, position: 'inside', fontSize: 9, formatter: p => p.value ? Number(p.value).toLocaleString('zh-CN') : '' }
          },
          {
            name: 'a-Si YoY', type: 'line', yAxisIndex: 1, smooth: true, symbolSize: 6,
            data: this.historyPeriods.map(period => {
              const item = value('a-Si', 'yoy_periods', period);
              return item === null ? null : Number((item * 100).toFixed(2));
            }),
            label: { show: true, formatter: p => p.value === null ? '' : `${Number(p.value).toFixed(1)}%`, fontSize: 9 }
          },
          {
            name: 'LTPS YoY', type: 'line', yAxisIndex: 1, smooth: true, symbolSize: 6,
            data: this.historyPeriods.map(period => {
              const item = value('LTPS', 'yoy_periods', period);
              return item === null ? null : Number((item * 100).toFixed(2));
            }),
            label: { show: true, formatter: p => p.value === null ? '' : `${Number(p.value).toFixed(1)}%`, fontSize: 9 }
          }
        ]
      });
      return chart;
    },
    renderSizeDistributionChart() {
      const element = this.$refs.sizeDistributionChart;
      const metric = this.tianmaProduct.size_distribution || this.tianmaProduct.y25q1_q3_size_distribution || {};
      const points = metric.points || [];
      if (!element || !points.length) return null;
      const maxShipment = Math.max(...points.map(point => Number(point.shipment || 0)), 1);
      const chart = echarts.init(element, 'macarons');
      const title = metric.label || `${this.activeMaker} ${this.summaryPeriodLabel}尺寸别分布情况`;
      chart.setOption({
        title: { text: title.startsWith(this.activeMaker) ? title : `${this.activeMaker} ${title}`, left: 'center', textStyle: { fontSize: 14 } },
        tooltip: {
          formatter: params => `${params.data[0]}英寸<br/>Shipment：${Number(params.data[1]).toLocaleString('zh-CN')} 千片`
        },
        grid: { left: 58, right: 22, top: 50, bottom: 45 },
        xAxis: { type: 'value', name: 'Size（英寸）', nameLocation: 'middle', nameGap: 32 },
        yAxis: { type: 'value', name: 'Shipment（千片）' },
        series: [{
          name: 'Shipment',
          type: 'scatter',
          data: points.map(point => [Number(point.size), Number(point.shipment), point.size]),
          symbolSize: value => 12 + 34 * Math.sqrt(Math.max(value[1], 0) / maxShipment),
          itemStyle: { color: '#fff8e8', borderColor: '#f2a43a', borderWidth: 2, opacity: 0.9 },
          label: { show: true, position: 'inside', formatter: p => p.data[2], color: '#303133', fontSize: 9 }
        }]
      });
      return chart;
    },
    renderTechnologySizeChart(refName, technology) {
      const element = this.$refs[refName];
      const metric = ((this.tianmaProduct.technology_size_growth || {})[technology]) || {};
      const series = metric.series || [];
      if (!element || !series.length) return null;
      const chart = echarts.init(element, 'macarons');
      chart.setOption({
        title: { text: `${this.activeMaker} ${technology} 尺寸别增长情况（Kpcs）`, left: 'center', textStyle: { fontSize: 13 } },
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        legend: { bottom: 0, itemWidth: 10, textStyle: { fontSize: 9 } },
        grid: { left: 52, right: 18, top: 48, bottom: 48 },
        xAxis: { type: 'category', data: this.historyPeriods.map(period => this.periodDisplayLabel(period)), axisLabel: { interval: 0, fontSize: 9 } },
        yAxis: { type: 'value', name: 'Kpcs', axisLabel: { fontSize: 9 } },
        series: series.map(item => ({
          name: item.label,
          type: 'bar',
          stack: technology,
          emphasis: { focus: 'series' },
          label: { show: true, position: 'inside', fontSize: 8, formatter: p => p.value ? Number(p.value).toLocaleString('zh-CN') : '' },
          data: this.historyPeriods.map(period => {
            const value = (item.periods || {})[period];
            return value === null || value === undefined ? 0 : Number(value);
          })
        }))
      });
      return chart;
    },
    renderCustomerChart() {
      const element = this.$refs.customerChart;
      const metric = this.tianmaCustomer.top_clients || {};
      const clients = metric.clients || [];
      let periods = (this.makerChartPeriods || []).slice();
      (metric.period_order || []).forEach(period => {
        if (period && !periods.includes(period)) periods.push(period);
      });
      if (!periods.length) periods = ['Y22', 'Y23', 'Y24', 'Y25F', 'Y25Q1-Q3'];
      if (this.isFullYearReport && !periods.includes('Y25F')) {
        const idx = periods.findIndex(period => period === 'Y25Q1-Q3');
        if (idx >= 0) periods.splice(idx, 0, 'Y25F');
        else periods.push('Y25F');
      }
      if (!element || !clients.length) return null;
      const chart = echarts.init(element, 'macarons');
      chart.setOption({
        title: { text: `${this.activeMaker} ${this.isFullYearReport ? '客户年度别出货（Y25全年）' : '前三季度出货前六大客户年度别出货情况'}（Kpcs）`, left: 'center', textStyle: { fontSize: 14 } },
        tooltip: { trigger: 'axis' },
        legend: { top: 30, type: 'scroll', textStyle: { fontSize: 10 } },
        grid: { left: 58, right: 24, top: 76, bottom: 42 },
        xAxis: { type: 'category', data: periods.map(period => this.periodDisplayLabel(period)) },
        yAxis: { type: 'value', name: 'Kpcs' },
        series: clients.map(client => ({
          name: client.client,
          type: 'line',
          symbolSize: 7,
          connectNulls: false,
          data: periods.map(period => {
            const value = (client.periods || {})[period];
            return value === null || value === undefined ? null : Number(value);
          })
        }))
      });
      return chart;
    },
    renderApplicationChart() {
      const element = this.$refs.applicationChart;
      const metric = this.tianmaApplication.application_history || {};
      const series = metric.series || [];
      const periods = this.makerChartPeriods;
      if (!element || !series.length) return null;
      const chart = echarts.init(element, 'macarons');
      chart.setOption({
        title: { text: `${this.activeMaker} ${this.isFullYearReport ? '应用别出货（含Y25全年）' : '应用别出货情况'}（Kpcs）`, left: 'center', textStyle: { fontSize: 14 } },
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        legend: { bottom: 0, itemWidth: 12, textStyle: { fontSize: 10 } },
        grid: { left: 58, right: 22, top: 52, bottom: 54 },
        xAxis: { type: 'category', data: periods.map(period => this.periodDisplayLabel(period)), axisLabel: { interval: 0 } },
        yAxis: { type: 'value', name: 'Kpcs' },
        series: series.map(item => ({
          name: item.application,
          type: 'bar',
          stack: 'application',
          emphasis: { focus: 'series' },
          label: { show: true, position: 'inside', fontSize: 9, formatter: p => p.value ? Number(p.value).toLocaleString('zh-CN') : '' },
          data: periods.map(period => {
            const value = (item.periods || {})[period];
            return value === null || value === undefined ? 0 : Number(value);
          })
        }))
      });
      return chart;
    },
    disposeHistoryCharts() {
      ['shipmentVolumeChart', 'displayAreaVolumeChart', 'shipmentShareChart', 'displayAreaShareChart', 'technologyHistoryChart', 'sizeDistributionChart', 'ltpsSizeGrowthChart', 'asiSizeGrowthChart', 'customerChart', 'applicationChart'].forEach(name => {
        if (this[name]) {
          this[name].dispose();
          this[name] = null;
        }
      });
    },
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id !== undefined) {
            updateAiReport(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addAiReport(this.form).then(response => {
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
        return delAiReport(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => {});
    },
    handleExport() {
      this.download('/business/report/export', {
        ...this.queryParams
      }, `AI分析报告_${new Date().getTime()}.xlsx`)
    },
    handleOfficeExport(report, format) {
      if (!report || !report.id || this.exportingFormat) return;
      this.exportingFormat = format;
      exportAiReportOffice(report.id, format).then(blob => {
        if (!blobValidate(blob) || ((blob.type || '').toLowerCase().includes('json'))) {
          return this.$download.printErrMsg(blob);
        }
        const suffix = format === 'word' ? 'docx' : 'pptx';
        const baseName = (report.taskName || '车载市场分析报告').replace(/[\\/:*?"<>|\r\n]+/g, '_').slice(0, 80);
        this.$download.saveAs(blob, `${baseName}.${suffix}`);
        this.$modal.msgSuccess(`${format === 'word' ? 'Word' : 'PPT'} 导出成功`);
      }).finally(() => { this.exportingFormat = ''; });
    }
  }
};
</script>

<style scoped>
.report-viewer { max-height: 78vh; overflow: auto; padding: 0 12px 20px; }
.report-export-actions { position: sticky; top: 0; z-index: 6; padding: 10px 0; background: #fff; border-bottom: 1px solid #ebeef5; }
.maker-card { margin: 16px 0; }
.metric-table { margin: 12px 0 16px; }
.summary-matrix { margin: 12px 0 16px; }
.summary-matrix ::v-deep .el-table__row td { padding: 4px 0; }
.summary-matrix ::v-deep .summary-section-row td { background: #05628e; color: #fff; font-weight: 700; text-align: center; }
.maker-tabs { margin-top: 22px; position: sticky; top: 0; z-index: 4; background: #fff; padding-top: 8px; }
.history-section { margin: 20px 0; }
.history-chart { width: 100%; height: 300px; margin-top: 12px; }
.product-dashboard { min-width: 1120px; }
.product-chart { width: 100%; margin-top: 12px; }
.product-chart--main { height: 520px; }
.product-chart--small { height: 254px; }
.growth-chart { width: 100%; height: 390px; margin-top: 8px; }
.compact-table ::v-deep .cell { padding-left: 5px; padding-right: 5px; font-size: 12px; }
.table-note { color: #606266; font-size: 12px; margin-top: 6px; }
.insight-row { margin-top: 8px; }
.driver-narratives {
  margin: 12px 0 8px;
  padding: 12px 14px;
  background: #f8fafc;
  border-left: 3px solid #334155;
  line-height: 1.7;
}
.driver-narratives p { margin: 0 0 10px; }
.driver-narratives p:last-child { margin-bottom: 0; }
.narrative-sources { margin: 18px 0; }
.narrative-source-row { padding: 12px 4px; border-bottom: 1px solid #ebeef5; line-height: 1.8; }
.source-file { margin-left: 8px; color: #606266; font-size: 13px; }
.metric-source { margin: 6px 0 0 28px; padding: 8px 10px; background: #f7f9fc; border-left: 3px solid #67c23a; color: #606266; font-size: 13px; }
.report-json { max-height: 420px; overflow: auto; padding: 12px; background: #f6f8fa; white-space: pre-wrap; }
</style>
