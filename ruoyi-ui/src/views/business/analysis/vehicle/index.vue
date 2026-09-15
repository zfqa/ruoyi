<template>
  <div class="app-container market-dashboard">
    <el-card shadow="never" class="control-card">
      <div slot="header" class="head">
        <span><i class="el-icon-data-analysis" /> 整车市场分析</span>
        <div class="service-status">
          <el-tooltip :content="serviceBuild.service_root || '未取得后端代码路径'" placement="bottom"><el-tag :type="serviceOnline ? 'success' : 'danger'" size="small">{{ serviceOnline ? `Python分析引擎 v${serviceBuild.app_version || '-'}` : '分析引擎未连接或版本过旧' }}</el-tag></el-tooltip>
          <el-tooltip :content="llmStatusTip" placement="bottom">
            <el-tag :type="llmTagType" size="small">{{ llmTagText }}</el-tag>
          </el-tooltip>
          <el-button size="mini" plain :loading="llmTesting" :disabled="!llmState.enabled" @click="testLlm">测试模型连接</el-button>
        </div>
      </div>
      <el-form inline size="small" label-width="72px">
        <el-form-item label="分析文件" class="analysis-file-item">
          <el-upload ref="analysisUpload" action="#" multiple :auto-upload="false" :disabled="uploading || loading" :file-list="analysisFiles" :on-change="onAnalysisFileChange" :on-remove="onAnalysisFileRemove" accept=".xlsx,.xlsm,.csv">
            <el-button icon="el-icon-folder-opened" :disabled="uploading || loading">选择Excel/CSV文件</el-button>
            <div slot="tip" class="el-upload__tip">选择文件不会上传或分析；点击“执行分析”后才会开始上传、解析和计算。</div>
          </el-upload>
        </el-form-item>
        <el-form-item label="工作表">
          <el-select v-model="sheetName" clearable placeholder="首次执行后可选择" :disabled="!datasetId || uploading || loading" style="width:210px" @change="onSheetChange">
            <el-option v-for="sheet in sheets" :key="sheet" :label="sheet" :value="sheet" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button icon="el-icon-delete" :disabled="uploading || loading || (!analysisFiles.length && !datasetId)" @click="clearAnalysisInput">清空当前分析</el-button></el-form-item>
      </el-form>
      <el-form inline size="small" label-width="72px">
        <el-form-item label="分析周期">
          <el-select v-model="periodMode" :disabled="!datasetId || uploading || loading" style="width:130px" @change="markAnalysisStale">
            <el-option label="最新月" value="latest" /><el-option label="指定月份" value="single" />
            <el-option label="累计区间" value="range" /><el-option label="全年累计" value="year" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="periodMode === 'single'" label="月份"><el-select v-model="startPeriod" :disabled="uploading || loading" @change="markAnalysisStale"><el-option v-for="p in periods" :key="p" :label="p" :value="p" /></el-select></el-form-item>
        <template v-if="periodMode === 'range'">
          <el-form-item label="开始"><el-select v-model="startPeriod" :disabled="uploading || loading" @change="markAnalysisStale"><el-option v-for="p in periods" :key="p" :label="p" :value="p" /></el-select></el-form-item>
          <el-form-item label="结束"><el-select v-model="endPeriod" :disabled="uploading || loading" @change="markAnalysisStale"><el-option v-for="p in periods" :key="p" :label="p" :value="p" /></el-select></el-form-item>
        </template>
        <el-form-item v-if="periodMode === 'year'" label="年份"><el-select v-model="year" :disabled="uploading || loading" @change="markAnalysisStale"><el-option v-for="y in years" :key="y" :label="y" :value="y" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" icon="el-icon-refresh" :loading="uploading || loading" :disabled="!canExecuteAnalysis" @click="executeAnalysis">{{ uploadButtonText }}</el-button></el-form-item>
      </el-form>
      <div v-if="uploadJob" class="analysis-upload-progress">
        <el-steps :active="analysisStep" finish-status="success" :process-status="analysisPhase === 'failed' ? 'error' : 'process'" simple>
          <el-step title="上传并解析文件" />
          <el-step title="生成分析结果" />
        </el-steps>
        <div class="analysis-phase-message">
          <i v-if="['parsing', 'preparing', 'analyzing'].includes(analysisPhase)" class="el-icon-loading" />
          <i v-else-if="analysisPhase === 'completed'" class="el-icon-success analysis-phase-success" />
          <i v-else-if="analysisPhase === 'failed'" class="el-icon-error analysis-phase-error" />
          <span>{{ analysisPhaseText }}</span>
          <el-button v-if="['queued', 'processing'].includes(uploadJob.status)" size="mini" type="danger" plain @click="cancelAnalysisUpload">取消任务</el-button>
          <el-button v-if="['failed', 'cancelled'].includes(uploadJob.status)" size="mini" type="warning" plain @click="retryAnalysisUpload">重新解析</el-button>
        </div>
      </div>
    </el-card>

    <el-empty v-if="!analysis && !loading && !uploading && !['preparing', 'analyzing'].includes(analysisPhase)" description="请选择待分析的Excel/CSV文件，再点击“执行分析”" />
    <template v-if="analysis">
      <el-alert v-if="analysis.analysis_period" :title="periodTitle" type="success" show-icon :closable="false" class="space-top" />
      <el-alert :title="analysisScopeTitle" type="info" show-icon :closable="false" class="space-top analysis-scope-alert" />
      <el-row :gutter="14" class="space-top">
        <el-col v-for="item in summaryCards" :key="item.key" :xs="12" :sm="8" :lg="3">
          <div class="metric-card"><div class="metric-label">{{ item.label }}</div><div class="metric-value">{{ formatNumber(item.value) }}</div></div>
        </el-col>
      </el-row>

      <el-card shadow="never">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="市场概览" name="overview">
            <el-button size="mini" icon="el-icon-chat-dot-round" class="component-button" @click="selectComponent('market_summary', '市场核心指标表')">选中用于问答/报告</el-button>
            <dynamic-table :rows="analysis.overview_table || []" />
          </el-tab-pane>
          <el-tab-pane label="数据排名" name="rankings">
            <el-radio-group v-model="rankDimension" size="small" class="tab-tools">
              <el-radio-button label="market">市场</el-radio-button><el-radio-button label="oem">车企</el-radio-button>
              <el-radio-button label="brand">品牌</el-radio-button><el-radio-button label="model">车型</el-radio-button>
            </el-radio-group>
            <el-select v-model="rankLimit" size="small" class="rank-limit" style="width:120px">
              <el-option label="全部" :value="0" /><el-option label="Top10" :value="10" /><el-option label="Top20" :value="20" /><el-option label="Top50" :value="50" />
            </el-select>
            <el-button size="mini" icon="el-icon-chat-dot-round" class="component-button" @click="selectRankingComponent">选中当前排名用于问答/报告</el-button>
            <el-row :gutter="16"><el-col :md="12"><market-chart :option="rankingOption" /></el-col><el-col :md="12"><dynamic-table :rows="rankRows" height="360" /></el-col></el-row>
          </el-tab-pane>
          <el-tab-pane label="动力结构" name="power">
            <el-button size="mini" icon="el-icon-chat-dot-round" class="component-button" @click="selectComponent('power_structure', '动力类型结构占比')">选中用于问答/报告</el-button>
            <el-row :gutter="16"><el-col :md="12"><market-chart :option="powerOption" /></el-col><el-col :md="12"><dynamic-table :rows="analysis.power_share || []" height="360" /></el-col></el-row>
          </el-tab-pane>
          <el-tab-pane label="趋势图" name="trends">
            <el-select v-model="chartIndex" size="small" class="tab-tools" style="width:300px">
              <el-option v-for="(chart, index) in analysis.line_charts || []" :key="chart.title" :label="chart.title" :value="index" />
            </el-select>
            <el-popover v-if="isPowerTrendSelected" placement="bottom-start" width="330" trigger="click" popper-class="power-trend-settings">
              <div class="power-trend-settings-body">
                <div class="power-trend-settings-title">动力类型月度结构占比设置</div>
                <el-radio-group v-model="powerTrendMode" size="small">
                  <el-radio-button v-for="item in powerTrendModes" :key="item.value" :label="item.value">{{ item.label }}</el-radio-button>
                </el-radio-group>
                <p>{{ powerTrendModeHint }}</p>
              </div>
              <el-button slot="reference" size="mini" icon="el-icon-setting" class="component-button">设置</el-button>
            </el-popover>
            <el-button v-if="selectedLineChart" size="mini" icon="el-icon-chat-dot-round" class="component-button" @click="selectCurrentChart">选中当前图表用于问答/报告</el-button>
            <market-chart v-if="selectedLineChart" :option="lineOption" height="430px" />
            <el-empty v-else description="当前数据没有可生成的趋势图" />
          </el-tab-pane>
          <el-tab-pane label="数据可信度" name="audit">
            <div class="trust-page">
              <div class="trust-hero" :class="`is-${trustStatus.type}`">
                <div class="trust-hero-icon"><i :class="trustStatus.icon" /></div>
                <div class="trust-hero-content">
                  <div class="trust-hero-title">{{ trustStatus.title }}</div>
                  <div class="trust-hero-text">{{ trustConclusion }}</div>
                </div>
              </div>

              <el-row :gutter="12" class="trust-summary">
                <el-col :xs="12" :sm="6">
                  <div class="trust-summary-card"><span>当前状态</span><strong :class="`text-${trustStatus.type}`">{{ trustStatus.title }}</strong></div>
                </el-col>
                <el-col :xs="12" :sm="6">
                  <div class="trust-summary-card"><span>识别数据类型</span><strong>{{ trustDataType }}</strong></div>
                </el-col>
                <el-col :xs="12" :sm="6">
                  <div class="trust-summary-card"><span>可用指标</span><strong>{{ availableTrustMetrics.length }} 项</strong></div>
                </el-col>
                <el-col :xs="12" :sm="6">
                  <div class="trust-summary-card"><span>待处理问题</span><strong>{{ trustIssueCount }} 项</strong></div>
                </el-col>
              </el-row>

              <section class="trust-section">
                <div class="trust-section-head">
                  <div><h3>指标覆盖情况</h3><p>“文件未提供”表示当前文件没有该类数据，不代表解析失败。</p></div>
                  <el-radio-group v-model="trustMetricFilter" size="mini">
                    <el-radio-button label="all">全部</el-radio-button>
                    <el-radio-button label="available">已识别</el-radio-button>
                    <el-radio-button label="missing">文件未提供</el-radio-button>
                  </el-radio-group>
                </div>
                <el-table :data="filteredTrustMetrics" border stripe size="small" empty-text="暂无符合条件的指标">
                  <el-table-column prop="label" label="指标" min-width="130" />
                  <el-table-column label="数据状态" width="120" align="center">
                    <template slot-scope="scope"><el-tag :type="scope.row.available ? 'success' : 'info'" size="small">{{ scope.row.available ? '可用' : '文件未提供' }}</el-tag></template>
                  </el-table-column>
                  <el-table-column prop="sourceText" label="来源" min-width="220" show-overflow-tooltip />
                  <el-table-column prop="impact" label="对分析的影响" min-width="300" show-overflow-tooltip />
                </el-table>
              </section>

              <el-collapse v-model="trustOpenPanels" class="trust-collapse">
                <el-collapse-item name="sources">
                  <template slot="title"><span class="collapse-title"><i class="el-icon-document-copy" /> 数据来源</span></template>
                  <p class="trust-panel-help">展示当前分析实际采用的文件、工作表和指标口径。</p>
                  <el-table v-if="trustSourceRows.length" :data="trustSourceRows" border stripe size="small">
                    <el-table-column prop="metric" label="分析指标" min-width="120" />
                    <el-table-column prop="file" label="来源文件" min-width="260" show-overflow-tooltip />
                    <el-table-column prop="sheet" label="来源工作表" min-width="150" show-overflow-tooltip />
                    <el-table-column prop="sourceMetric" label="源指标口径" min-width="150" show-overflow-tooltip />
                    <el-table-column prop="aggregation" label="当前汇总方式" min-width="130" />
                    <el-table-column label="状态" width="100" align="center"><template><el-tag type="success" size="mini">已确认</el-tag></template></el-table-column>
                  </el-table>
                  <el-empty v-else description="当前没有可追溯的指标来源" />
                </el-collapse-item>

                <el-collapse-item name="dimensions">
                  <template slot="title"><span class="collapse-title"><i class="el-icon-s-grid" /> 数据完整性</span></template>
                  <p class="trust-panel-help">核对当前周期有值的业务对象是否完整进入排名和分析输出。</p>
                  <el-table v-if="trustDimensionRows.length" :data="trustDimensionRows" border stripe size="small">
                    <el-table-column prop="dimension" label="检查维度" min-width="130" />
                    <el-table-column prop="allCount" label="全周期对象" width="130" align="right" />
                    <el-table-column prop="currentCount" label="当前周期有值" width="140" align="right" />
                    <el-table-column prop="outputCount" label="已进入分析" width="130" align="right" />
                    <el-table-column label="状态" width="110" align="center">
                      <template slot-scope="scope"><el-tag :type="scope.row.complete ? 'success' : 'warning'" size="small">{{ scope.row.complete ? '完整' : '有缺失' }}</el-tag></template>
                    </el-table-column>
                    <el-table-column prop="note" label="说明" min-width="260" show-overflow-tooltip />
                  </el-table>
                  <el-empty v-else description="当前文件没有可检查的业务维度" />
                </el-collapse-item>

                <el-collapse-item name="calculation">
                  <template slot="title"><span class="collapse-title"><i class="el-icon-s-operation" /> 计算口径</span></template>
                  <p class="trust-panel-help">说明当前页面各项结果使用的分析范围、周期和计算方式。</p>
                  <el-descriptions :column="2" border size="small" class="trust-descriptions">
                    <el-descriptions-item label="分析范围">{{ trustCalculation.scope }}</el-descriptions-item>
                    <el-descriptions-item label="当前周期">{{ trustCalculation.period }}</el-descriptions-item>
                    <el-descriptions-item label="同比基期">{{ trustCalculation.comparison }}</el-descriptions-item>
                    <el-descriptions-item label="同比方式">{{ trustCalculation.comparisonRule }}</el-descriptions-item>
                    <el-descriptions-item label="指标汇总">{{ trustCalculation.aggregation }}</el-descriptions-item>
                    <el-descriptions-item label="趋势范围">{{ trustCalculation.trendScope }}</el-descriptions-item>
                    <el-descriptions-item label="缺失月份">{{ trustCalculation.missingPeriods }}</el-descriptions-item>
                    <el-descriptions-item label="缺失值处理">缺失数据显示为缺失，不按0计算</el-descriptions-item>
                  </el-descriptions>
                </el-collapse-item>

                <el-collapse-item name="issues">
                  <template slot="title"><span class="collapse-title"><i class="el-icon-warning-outline" /> 需要处理的问题 <el-badge v-if="trustIssueCount" :value="trustIssueCount" class="trust-badge" /></span></template>
                  <el-alert v-if="!trustIssueRows.length && !trustIncompleteDimensions.length" title="未发现影响当前分析的数据质量问题" type="success" :closable="false" show-icon />
                  <el-table v-else :data="trustIssueRowsWithDimensions" border stripe size="small">
                    <el-table-column label="级别" width="90" align="center">
                      <template slot-scope="scope"><el-tag :type="scope.row.type" size="mini">{{ scope.row.level }}</el-tag></template>
                    </el-table-column>
                    <el-table-column prop="problem" label="问题" min-width="300" show-overflow-tooltip />
                    <el-table-column prop="impact" label="影响" min-width="220" show-overflow-tooltip />
                    <el-table-column prop="suggestion" label="处理建议" min-width="260" show-overflow-tooltip />
                  </el-table>
                </el-collapse-item>

                <el-collapse-item name="anomalies">
                  <template slot="title"><span class="collapse-title"><i class="el-icon-data-line" /> 数据波动提醒（{{ trustAnomalies.length }}）</span></template>
                  <el-alert title="波动提醒表示同比变化超过基础阈值，需要结合业务原因复核；它不等同于数据解析错误。" type="info" :closable="false" show-icon class="warning" />
                  <dynamic-table v-if="trustAnomalies.length" :rows="trustAnomalies" />
                  <el-empty v-else description="当前周期未发现超过基础阈值的数据波动" />
                </el-collapse-item>
              </el-collapse>
            </div>
          </el-tab-pane>
          <el-tab-pane label="行业资料" name="context">
            <el-input v-model="contextText" type="textarea" :rows="6" placeholder="粘贴宏观政策、人事、战略、产业链或竞争动态等事实资料" />
            <el-input v-model="contextSource" size="small" placeholder="资料来源名称" class="context-source" />
            <el-button type="primary" size="small" :disabled="!contextText.trim()" @click="saveContext">添加资料</el-button>
            <el-upload action="#" multiple :auto-upload="false" :on-change="onContextChange" :on-remove="onContextRemove" :file-list="contextFiles" accept=".txt,.md,.docx,.pptx,.pdf,.xlsx,.xlsm,.csv" class="context-upload">
              <el-button size="small" icon="el-icon-folder-opened">选择资料文件</el-button>
            </el-upload>
            <el-button size="small" :disabled="!contextFiles.length" :loading="contextUploading" @click="submitContextFiles">上传并解析资料</el-button>
            <el-divider />
            <div class="context-toolbar">
              <span>已入库 <strong>{{ contextResult.total || 0 }}</strong> 条</span>
              <el-tag v-for="(count, key) in contextResult.counts || {}" :key="key" size="mini">{{ categoryLabel(key) }} {{ count }}</el-tag>
              <el-select v-model="contextCategory" clearable size="mini" placeholder="全部分类" style="width:140px"><el-option v-for="item in contextCategories" :key="item.value" :label="item.label" :value="item.value" /></el-select>
              <el-button size="mini" icon="el-icon-refresh" @click="loadContextItems">刷新</el-button>
              <el-button size="mini" type="danger" plain :disabled="!selectedContextIds.length" @click="removeSelectedContext">删除选中</el-button>
              <el-button size="mini" type="danger" plain :disabled="!contextResult.total" @click="removeAllContext">清空全部</el-button>
            </div>
            <el-table :data="filteredContextItems" border stripe size="mini" max-height="420" @selection-change="onContextSelection">
              <el-table-column type="selection" width="45" />
              <el-table-column label="分类" width="100"><template slot-scope="scope"><el-tag size="mini">{{ categoryLabel(scope.row.category) }}</el-tag></template></el-table-column>
              <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
              <el-table-column prop="content" label="内容" min-width="360" show-overflow-tooltip />
              <el-table-column prop="source_name" label="来源" width="160" show-overflow-tooltip />
              <el-table-column prop="locator" label="位置" width="130" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="周报与导出" name="report">
            <div class="report-actions">
              <el-button type="primary" :loading="reportLoading" @click="generateReport">生成/刷新周报</el-button>
              <el-button icon="el-icon-setting" @click="openReportConfig">可视化编排</el-button>
              <el-button v-for="fmt in ['xlsx','docx','pptx']" :key="fmt" :loading="exporting === fmt" @click="exportReport(fmt)">导出 {{ fmt.toUpperCase() }}</el-button>
            </div>
            <el-input v-model="planInstruction" type="textarea" :rows="3" placeholder="用自然语言调整报告，例如：只保留市场概览和Top品牌趋势，并把标题改为8月市场观察" />
            <div class="plan-actions"><el-button type="success" size="small" :disabled="!planInstruction.trim()" :loading="planLoading" @click="applyPlan">应用报告指令</el-button><el-button size="small" @click="resetPlan">清空 1.1 编排</el-button></div>
            <el-alert :title="reportPlanStatus" type="info" :closable="false" show-icon class="warning" />
            <el-collapse v-if="(reportPlan.market_observations || []).length" class="plan-overview">
              <el-collapse-item v-for="(observation, oi) in reportPlan.market_observations" :key="observation.id || oi" :title="`${oi + 1}. ${observation.custom_title || '市场观察'}${observation.id === reportPlan.active_observation_id ? '（当前编辑）' : ''}`" :name="observation.id || oi">
                <p><strong>组件：</strong>{{ componentNames(observation.components) || '无' }}</p>
                <p v-if="(observation.core_indicators || []).length"><strong>核心指标：</strong>{{ observation.core_indicators.map(item => item.display_name).join('、') }}</p>
                <p v-if="(observation.summary_segments || []).length"><strong>核心指标：</strong>{{ observation.summary_segments.join('、') }}</p>
                <p v-if="observation.analysis_period"><strong>独立周期：</strong>{{ pretty(observation.analysis_period) }}</p>
              </el-collapse-item>
            </el-collapse>
            <template v-if="report">
              <h2>{{ report.title }}</h2><p class="muted">{{ report.report_date }} ｜ {{ report.period_label }}</p>
              <el-alert :title="`已包含：${(report.included_dashboard_modules || []).join('、') || '暂无模块'}`" type="info" :closable="false" />
              <div v-for="(observation, oi) in report.market_observations || []" :key="`obs-${oi}`" class="report-section">
                <h3>{{ observation.number || `1.${oi + 1}` }} {{ observation.title || `市场观察 ${oi + 1}` }}</h3>
                <div v-if="observation.manual_content" class="manual-observation-content"><strong>业务补充：</strong><span>{{ observation.manual_content }}</span></div>
                <ul><li v-for="(item, i) in observation.insights || []" :key="i">{{ item }}</li></ul>
                <div v-for="(component, ci) in observation.components || []" :key="`comp-${ci}`" class="report-component">
                  <h4>{{ component.title }}</h4>
                  <market-chart v-if="component.chart" :option="chartOption(component.chart)" height="360px" />
                  <dynamic-table v-if="component.rows && component.rows.length" :rows="component.rows" />
                </div>
              </div>
              <template v-if="!(report.market_observations || []).length">
                <h3>{{ report.market_observation_title }}</h3>
                <ul><li v-for="(item, i) in report.market_key_insights || []" :key="i">{{ item }}</li></ul>
                <dynamic-table v-if="(report.overview_table || []).length" :rows="report.overview_table" />
                <div v-for="section in reportRankingSections" :key="section.key" v-if="section.rows.length" class="report-component">
                  <h4>{{ section.label }}</h4><el-row :gutter="16"><el-col :md="12"><market-chart :option="rankingChartOption(section.rows, section.label)" height="330px" /></el-col><el-col :md="12"><dynamic-table :rows="section.rows" height="330" /></el-col></el-row>
                </div>
                <div v-if="(report.power_share || []).length" class="report-component"><h4>动力结构</h4><el-row :gutter="16"><el-col :md="12"><market-chart :option="powerChartOption(report.power_share)" height="330px" /></el-col><el-col :md="12"><dynamic-table :rows="report.power_share" height="330" /></el-col></el-row></div>
                <dynamic-table v-if="(report.trend || []).length" :rows="report.trend" />
                <el-row :gutter="16"><el-col v-for="(chart, i) in report.line_charts || []" :key="`report-chart-${i}`" :md="12"><market-chart :option="chartOption(chart)" height="330px" /></el-col></el-row>
              </template>
              <div v-for="section in reportContextSections" :key="section.key" class="report-section" v-if="section.items.length">
                <h3>{{ section.number }} {{ section.label }}</h3>
                <el-card v-for="(item, i) in section.items" :key="i" shadow="never" class="event-card"><strong>{{ item.number }} {{ item.title }}</strong><p>{{ item.content || item.summary }}</p><p v-if="item.analysis" class="muted">分析：{{ item.analysis }}</p><small>{{ item.source || item.source_name }}</small></el-card>
              </div>
            </template>
          </el-tab-pane>
        </el-tabs>
      </el-card>

      <el-card shadow="never" class="chat-card">
        <div slot="header"><i class="el-icon-chat-dot-round" /> 数据问答与报告助手</div>
        <div v-if="selectedComponentId" class="selected-component"><el-tag closable @close="clearSelectedComponent">当前对象：{{ selectedComponentTitle }}</el-tag><span>后续问题和报告指令将基于该组件。</span></div>
        <div v-for="(message, i) in chatMessages" :key="i" :class="['message', message.role]">
          <strong>{{ message.role === 'user' ? '我' : 'AI' }}：</strong>{{ message.content }}
          <dynamic-table v-if="message.table && message.table.length" :rows="message.table" />
          <market-chart v-for="(chart, ci) in message.charts || []" :key="`chat-${i}-${ci}`" :option="chartOption(chart)" height="320px" />
          <el-alert v-for="(warning, wi) in message.warnings || []" :key="`cw-${i}-${wi}`" :title="warning" type="warning" :closable="false" class="warning" />
          <el-collapse v-if="message.evidence && message.evidence.length"><el-collapse-item title="证据链与计算依据"><ol><li v-for="(e, ei) in message.evidence" :key="ei">{{ e }}</li></ol></el-collapse-item></el-collapse>
        </div>
        <el-input v-model="question" type="textarea" :rows="2" placeholder="例如：本期销量最高的品牌是谁？这个结论来自哪个Sheet？" @keyup.ctrl.enter.native="ask" />
        <div class="chat-actions"><el-checkbox v-model="useLlm">启用大模型表述</el-checkbox><el-button type="primary" size="small" :loading="chatLoading" :disabled="!question.trim()" @click="ask">发送（Ctrl+Enter）</el-button></div>
      </el-card>
    </template>

    <el-dialog v-loading="configLoading" title="1.1 市场观察可视化编排" :visible.sync="reportConfigVisible" width="920px" top="5vh" :close-on-click-modal="!configLoading">
      <el-alert title="这里的配置会直接写入报告计划；页面预览、目录和 XLSX/Word/PPT 导出使用同一份组件顺序与数据周期。" type="info" :closable="false" show-icon />
      <el-alert v-if="configLoadError" :title="configLoadError" type="warning" :closable="false" show-icon class="config-load-error" />
      <el-form v-if="reportConfig && visualObservation" label-width="145px" size="small" class="visual-composer-form">
        <el-divider content-position="left">标题与数据口径</el-divider>
        <el-form-item label="整份报告标题"><el-input v-model="reportConfig.custom_title" placeholder="留空则使用“汽车行业市场洞察周报”" /></el-form-item>
        <el-form-item label="1.1 章节标题"><el-input v-model="visualObservation.custom_title" placeholder="留空将按地区和当前分析周期自动生成" /></el-form-item>
        <el-form-item label="当前数据口径"><el-tag type="success">{{ periodTitle }}</el-tag><span class="visual-hint"> 如需更换月份或区间，请先关闭窗口并在页面顶部重新执行分析。</span></el-form-item>
        <el-divider content-position="left">选择并排列 1.1 内容</el-divider>
        <el-form-item label="可用表格和图表">
          <div v-for="group in dashboardComponentGroups" :key="group.name" class="component-group">
            <div class="component-group-title">{{ group.name }}</div>
            <el-checkbox-group v-model="visualObservation.components">
              <el-checkbox v-for="item in group.items" :key="item.id" :label="item.id" border>{{ item.title }}</el-checkbox>
            </el-checkbox-group>
          </div>
          <el-empty v-if="!dashboardComponents.length" description="当前数据和周期没有可编排组件" :image-size="60" />
        </el-form-item>
        <el-form-item v-if="visualObservation.components.includes(powerChartId)" label="动力月度图配置">
          <el-checkbox-group v-model="visualObservation.component_options[powerChartId].series" :min="1">
            <el-checkbox label="new_energy">新能源车</el-checkbox>
            <el-checkbox label="fuel">燃油车</el-checkbox>
          </el-checkbox-group>
          <div class="visual-hint">新能源=EV/BEV+PHV/PHEV+FCV；燃油=ICE+HV/HEV+MHV/MHEV。即使只显示一条线，占比分母仍为当月全部动力类型数据。</div>
          <div v-if="(powerGroupCapabilities.mappings || []).length" class="power-mapping-list">
            <div class="indicator-filter-head"><span>Excel动力类型映射</span><span class="visual-hint">N/A、空白和混合标签默认未分类，可人工指定。</span></div>
            <el-row v-for="item in powerGroupCapabilities.mappings" :key="item.source_value" :gutter="10" class="indicator-row">
              <el-col :span="10"><el-input :value="item.source_value" disabled /></el-col>
              <el-col :span="14"><el-select v-model="visualObservation.component_options[powerChartId].power_type_mapping[item.source_value]" style="width:100%"><el-option v-for="group in powerGroupCapabilities.mapping_options" :key="group.value" :label="group.label" :value="group.value" /></el-select></el-col>
            </el-row>
          </div>
        </el-form-item>
        <el-form-item v-if="visualObservation.components.includes('market_summary')" label="市场概览表布局">
          <el-button size="mini" type="primary" plain :disabled="!indicatorCapabilities.metrics.length" @click="applyMatrixTemplate">采用二维可编辑表格</el-button>
          <el-button v-if="hasMatrixConfig" size="mini" @click="clearMatrixConfig">返回单指标列表</el-button>
          <span class="visual-hint">二维模式可分别编辑报告行和指标列，数值全部按当前Excel或业务公式计算。</span>
        </el-form-item>
        <div v-if="visualObservation.components.includes('market_summary') && hasMatrixConfig" class="matrix-editor-wrap">
          <div class="scope-source-card">
            <div class="scope-source-title"><i class="el-icon-document-checked" /> 当前统计口径的数据来源</div>
            <div class="scope-source-content">{{ segmentSourceSummary }}</div>
            <div class="visual-hint">统计口径由当前Excel的真实维度值确定，不要求原表存在名为“数据范围”的字段；报告预览和导出使用同一套规则。</div>
          </div>
          <div v-if="reviewSegmentMappingItems.length" class="scope-mapping-card">
            <div class="scope-source-title"><i class="el-icon-warning-outline" /> 待确认的原始分类值</div>
            <div class="visual-hint">这些值无法通过确定性规则归类。系统不会擅自推断；可人工指定，或者保留“未分类”。</div>
            <el-row v-for="item in reviewSegmentMappingItems" :key="`${item.dimension}-${item.source_value}`" :gutter="10" class="scope-mapping-row">
              <el-col :span="7"><el-input :value="item.source_value" disabled><template slot="prepend">{{ dimensionLabel(item.dimension) }}</template></el-input></el-col>
              <el-col :span="7"><el-select :value="segmentMappingValue(item)" style="width:100%" @change="setSegmentMapping(item, $event)"><el-option v-for="option in item.options" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-col>
              <el-col :span="10"><span class="visual-hint">来源：{{ segmentMappingSource(item) }}</span></el-col>
            </el-row>
          </div>
          <div class="matrix-section-title"><strong>报告行</strong><el-button type="text" @click="addMatrixRow">新增报告行</el-button></div>
          <div v-for="(row, rowIndex) in visualObservation.market_summary_rows" :key="row.id" class="indicator-editor compact-editor">
            <div class="indicator-editor-head"><strong>行 {{ rowIndex + 1 }}</strong><span><el-button type="text" :disabled="rowIndex === 0" @click="moveMatrixItem('market_summary_rows', rowIndex, -1)">上移</el-button><el-button type="text" :disabled="rowIndex === visualObservation.market_summary_rows.length - 1" @click="moveMatrixItem('market_summary_rows', rowIndex, 1)">下移</el-button><el-button type="text" class="danger-text" @click="visualObservation.market_summary_rows.splice(rowIndex, 1)">删除</el-button></span></div>
            <el-row :gutter="10"><el-col :span="12"><el-input v-model="row.display_name" placeholder="报告行名称"><template slot="prepend">名称</template></el-input></el-col><el-col :span="12"><div class="matrix-field-label">统计口径</div><el-select v-model="row.segment" placeholder="选择统计口径" style="width:100%"><el-option v-for="segment in matrixSegmentOptions" :key="segment.value" :label="segmentOptionLabel(segment)" :value="segment.value" :disabled="segment.disabled" /></el-select></el-col></el-row>
            <div class="scope-rule-line">
              <el-tag size="mini" :type="segmentStatusType(segmentCapability(row.segment))">{{ segmentStatusText(segmentCapability(row.segment)) }}</el-tag>
              <span>{{ segmentRuleText(row.segment) }}</span>
            </div>
            <template v-if="row.segment === 'custom'">
              <div class="indicator-filter-head"><span>Excel筛选条件</span><el-button type="text" @click="addMatrixRowFilter(row)">添加条件</el-button></div>
              <el-row v-for="(filter, filterIndex) in row.filters" :key="`${row.id}_filter_${filterIndex}`" :gutter="10" class="indicator-row"><el-col :span="8"><el-select v-model="filter.dimension" filterable placeholder="维度字段" style="width:100%" @change="resetIndicatorFilter(filter)"><el-option v-for="dimension in indicatorCapabilities.dimensions" :key="dimension.field" :label="`${dimension.label}（${dimension.field}）`" :value="dimension.field" /></el-select></el-col><el-col :span="13"><el-select v-model="filter.values" multiple filterable collapse-tags placeholder="选择Excel中的值" style="width:100%"><el-option v-for="value in indicatorDimensionValues(filter.dimension)" :key="value" :label="value" :value="value" /></el-select></el-col><el-col :span="3"><el-button type="text" class="danger-text" @click="row.filters.splice(filterIndex, 1)">删除</el-button></el-col></el-row>
            </template>
          </div>
          <div class="matrix-section-title"><strong>指标列</strong><el-button type="text" @click="addMatrixColumn">新增指标列</el-button></div>
          <div v-for="(column, columnIndex) in visualObservation.market_summary_columns" :key="column.id" class="indicator-editor compact-editor">
            <div class="indicator-editor-head"><strong>列 {{ columnIndex + 1 }}</strong><span><el-button type="text" :disabled="columnIndex === 0" @click="moveMatrixItem('market_summary_columns', columnIndex, -1)">左移</el-button><el-button type="text" :disabled="columnIndex === visualObservation.market_summary_columns.length - 1" @click="moveMatrixItem('market_summary_columns', columnIndex, 1)">右移</el-button><el-button type="text" class="danger-text" @click="visualObservation.market_summary_columns.splice(columnIndex, 1)">删除</el-button></span></div>
            <el-row :gutter="10"><el-col :span="9"><div class="matrix-field-label">指标口径</div><el-select v-model="column.metric" filterable placeholder="选择指标" style="width:100%" @change="onMatrixColumnMetricChange(column)"><el-option-group label="当前 Excel 可用"><el-option v-for="metric in indicatorCapabilities.metrics" :key="metric.field" :label="`${metric.label}${metric.derived ? '（公式）' : ''}`" :value="metric.field" /></el-option-group><el-option-group v-if="(indicatorCapabilities.unavailable_metrics || []).length" label="当前 Excel 不可用（不可选择）"><el-option v-for="metric in indicatorCapabilities.unavailable_metrics" :key="`unavailable_${metric.field}`" :label="`${metric.label}：${metric.reason}`" :value="`unavailable_${metric.field}`" disabled /></el-option-group></el-select></el-col><el-col :span="8"><div class="matrix-field-label">报告表头（自动对应）</div><el-input :value="matrixColumnLabel(column)" disabled /></el-col><el-col :span="7"><div class="matrix-field-label">汇总方式</div><el-select v-model="column.aggregation" :disabled="matrixMetricIsFixed(column)" style="width:100%"><el-option v-for="item in indicatorCapabilities.aggregations" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-col></el-row>
            <div class="matrix-data-rule">{{ matrixColumnRule(column) }}</div>
            <el-row :gutter="10" class="indicator-row"><el-col :span="10"><el-select v-model="column.comparisons" multiple collapse-tags clearable placeholder="同比/环比（可多选）" style="width:100%"><el-option v-for="item in indicatorCapabilities.comparisons" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-col><el-col :span="8"><el-input v-model="column.unit" placeholder="单位"><template slot="prepend">单位</template></el-input></el-col><el-col :span="6"><el-input-number v-model="column.decimals" :min="0" :max="6" controls-position="right" style="width:100%" /></el-col></el-row>
          </div>
        </div>
        <el-form-item v-if="visualObservation.components.includes('market_summary') && !hasMatrixConfig" label="核心指标行">
          <div class="indicator-actions">
            <el-button size="mini" type="primary" plain :disabled="!indicatorCapabilities.metrics.length" @click="addCoreIndicator">新增指标</el-button>
            <el-button size="mini" :disabled="!indicatorCapabilities.suggested_indicators.length" @click="applySuggestedIndicators">采用当前Excel推荐指标</el-button>
            <span class="visual-hint">指标字段和筛选值全部来自当前 Excel，可自行增删、组合和排序。</span>
          </div>
          <el-empty v-if="!visualObservation.core_indicators.length" description="未自定义时沿用当前市场概览表；也可以新增或采用推荐指标" :image-size="52" />
          <div v-for="(indicator, indicatorIndex) in visualObservation.core_indicators" :key="indicator.id" class="indicator-editor">
            <div class="indicator-editor-head">
              <strong>指标 {{ indicatorIndex + 1 }}</strong>
              <span>
                <el-button type="text" :disabled="indicatorIndex === 0" @click="moveCoreIndicator(indicatorIndex, -1)">上移</el-button>
                <el-button type="text" :disabled="indicatorIndex === visualObservation.core_indicators.length - 1" @click="moveCoreIndicator(indicatorIndex, 1)">下移</el-button>
                <el-button type="text" class="danger-text" @click="removeCoreIndicator(indicatorIndex)">删除</el-button>
              </span>
            </div>
            <el-row :gutter="10">
              <el-col :span="8"><el-input v-model="indicator.display_name" placeholder="报告中的指标名称"><template slot="prepend">名称</template></el-input></el-col>
              <el-col :span="8"><el-select v-model="indicator.metric" filterable placeholder="选择数值字段" style="width:100%" @change="onIndicatorMetricChange(indicator)"><el-option v-for="metric in indicatorCapabilities.metrics" :key="metric.field" :label="`${metric.label}（${metric.field}）`" :value="metric.field" /></el-select></el-col>
              <el-col :span="8"><el-select v-model="indicator.aggregation" style="width:100%"><el-option v-for="item in indicatorCapabilities.aggregations" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-col>
            </el-row>
            <el-row :gutter="10" class="indicator-row">
              <el-col :span="7"><el-select v-model="indicator.comparisons" multiple collapse-tags clearable placeholder="同比/环比（可多选）" style="width:100%"><el-option v-for="item in indicatorCapabilities.comparisons" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-col>
              <el-col :span="5"><el-checkbox v-model="indicator.show_share">显示总体占比</el-checkbox></el-col>
              <el-col :span="6"><el-input v-model="indicator.unit" placeholder="辆、万元、%"><template slot="prepend">单位</template></el-input></el-col>
              <el-col :span="6"><el-input-number v-model="indicator.decimals" :min="0" :max="6" controls-position="right" style="width:100%" /></el-col>
            </el-row>
            <div class="indicator-filter-head"><span>筛选条件</span><el-button type="text" @click="addIndicatorFilter(indicator)">添加条件</el-button></div>
            <el-row v-for="(filter, filterIndex) in indicator.filters" :key="`${indicator.id}_filter_${filterIndex}`" :gutter="10" class="indicator-row">
              <el-col :span="8"><el-select v-model="filter.dimension" filterable placeholder="维度字段" style="width:100%" @change="resetIndicatorFilter(filter)"><el-option v-for="dimension in indicatorCapabilities.dimensions" :key="dimension.field" :label="`${dimension.label}（${dimension.field}）`" :value="dimension.field" /></el-select></el-col>
              <el-col :span="13"><el-select v-model="filter.values" multiple filterable collapse-tags placeholder="选择当前Excel中的值" style="width:100%"><el-option v-for="value in indicatorDimensionValues(filter.dimension)" :key="value" :label="value" :value="value" /></el-select></el-col>
              <el-col :span="3"><el-button type="text" class="danger-text" @click="indicator.filters.splice(filterIndex, 1)">删除</el-button></el-col>
            </el-row>
          </div>
        </el-form-item>
        <el-form-item label="报告输出顺序">
          <el-table :data="selectedVisualComponents" border size="mini" empty-text="请从上方至少选择一个组件">
            <el-table-column type="index" label="#" width="48" align="center" />
            <el-table-column prop="title" label="组件" min-width="280" />
            <el-table-column prop="group" label="来源模块" width="145" />
            <el-table-column label="排序" width="205" align="center">
              <template slot-scope="scope">
                <el-button type="text" :disabled="scope.$index === 0" @click="moveVisualComponent(scope.$index, -1)">上移</el-button>
                <el-button type="text" :disabled="scope.$index === selectedVisualComponents.length - 1" @click="moveVisualComponent(scope.$index, 1)">下移</el-button>
                <el-button type="text" class="danger-text" @click="removeVisualComponent(scope.row.id)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-form-item>
        <el-form-item label="趋势回看月份"><el-input-number v-model="visualObservation.lookback_months" :min="2" :max="36" /></el-form-item>

        <el-divider content-position="left">自定义 1.1 内容</el-divider>
        <el-form-item label="人工补充正文"><el-input v-model="visualObservation.manual_content" type="textarea" :rows="4" placeholder="可选。这里填写的业务判断会原样进入 1.1 正文，适合补充程序数据无法表达的内容。" /></el-form-item>
        <el-form-item label="其他报告内容"><el-checkbox v-model="reportConfig.include_weekly_content">行业资料章节</el-checkbox><el-checkbox v-model="reportConfig.include_anomalies">异常提示</el-checkbox></el-form-item>
      </el-form>
      <span slot="footer"><el-button :disabled="configLoading" @click="clearVisualForm">清空本节选择</el-button><el-button @click="reportConfigVisible=false">取消</el-button><el-button type="primary" :disabled="configLoading" :loading="configSaving" @click="saveVisualConfig">保存并重新生成</el-button></span>
    </el-dialog>
  </div>
</template>

<script>
import { saveAs } from 'file-saver'
import DynamicTable from './components/DynamicTable'
import MarketChart from './components/MarketChart'
import { marketHealth, llmStatus, testLlmConnection, createMarketUploadJob, getMarketUploadJob, cancelMarketUploadJob, retryMarketUploadJob, getSheets, getPeriodOptions, getMarketAnalysis, getDashboardComponents, addContextText, getContext, deleteContextItems, clearContext, uploadContextFiles, askMarketAgent, getMarketReport, exportMarketReport, downloadMarketReport, getReportPlan, updateReportPlan, saveVisualReportPlan, resetReportPlan, getReportConfig } from '@/api/business/market/marketAgent'

const metricLabels = { production: '产量', sales: '销量', retail_sales: '零售销量', wholesale: '批发销量', domestic_sales: '国内销量', domestic_wholesale: '国内批发销量', export: '出口', inventory: '库存量' }
const trustMetricKeys = ['production', 'sales', 'retail_sales', 'wholesale', 'domestic_sales', 'domestic_wholesale', 'export', 'inventory']
const metricImpacts = {
  production: '用于产量规模、产销关系等分析', sales: '用于市场概览、排名、动力结构和销量趋势', retail_sales: '用于零售市场规模及趋势分析',
  wholesale: '用于批发市场规模及趋势分析', domestic_sales: '用于国内销量规模分析', domestic_wholesale: '用于国内批发销量分析',
  export: '用于出口规模及趋势分析', inventory: '用于期末库存和库存变化分析'
}
const dimensionLabels = { market: '市场/车种', vehicle_type: '车辆类型', oem: '车企/OEM', brand: '品牌', model: '车型', power_type: '动力类型', region: '国家/地区', size_class: '尺寸级别', tech_route: '技术路线' }
const rankingComponents = { market: ['market_top10', '市场/车种销量排名TOP10'], oem: ['oem_top10', 'OEM销量排名TOP10'], brand: ['brand_top10', '品牌销量排名TOP10'], model: ['model_top10', '车型销量排名TOP10'] }
const contextCategories = [{ value: 'macro_policy', label: '宏观政策' }, { value: 'personnel', label: '人事调整' }, { value: 'strategy', label: '战略布局' }, { value: 'industry_chain', label: '产业链' }, { value: 'competition', label: '竞争动态' }, { value: 'other', label: '其他' }]
const requiredApiContract = 'dynamic-indicator-content-fidelity-v1'
const powerChartId = 'line_chart:动力类型月度结构占比'
const powerTrendModes = [
  { value: 'all', label: '所有动力类型', hint: '按当前 Excel 中的原始动力类型分别绘制折线，不做归类。' },
  { value: 'new_energy', label: '新能源车', hint: '仅展示 EV/BEV、PHV/PHEV、FCV 汇总后的新能源车月度结构占比。' },
  { value: 'fuel', label: '燃油车', hint: '仅展示 ICE、HV/HEV、MHV/MHEV 汇总后的燃油车月度结构占比。' },
  { value: 'both', label: '新能源车 + 燃油车', hint: '同时展示新能源车与燃油车两条汇总折线。' }
]
const matrixSegments = [
  { value: 'total_market', label: '总体市场' },
  { value: 'passenger_vehicle', label: '乘用车' },
  { value: 'commercial_vehicle', label: '商用车' },
  { value: 'new_energy_vehicle', label: '新能源车' },
  { value: 'ice_vehicle', label: '燃油车' },
  { value: 'custom', label: '自定义筛选' }
]

export default {
  // 动态菜单的路由名由 path=vehicle 生成为 Vehicle。与路由缓存名保持
  // 一致后，切换页签不会销毁已选择文件、上传任务和分析结果；右上角
  // “刷新”会主动移除该缓存并创建一个新的初始页面。
  name: 'Vehicle', components: { DynamicTable, MarketChart },
  data() {
    return {
      datasetId: '', analysisFiles: [], uploadJob: null, uploadTimer: null, uploading: false, analysisPhase: 'idle', serviceOnline: false, serviceBuild: {}, loading: false, sheets: [], sheetName: '', periodMode: 'latest', periods: [], years: [], startPeriod: '', endPeriod: '', year: '',
      llmState: { enabled: false, model: '', base_url: '', api_key_configured: false, proxy_configured: false, network_mode: 'direct' }, llmTesting: false, llmConnectionState: 'unknown', llmLastError: '',
      analysis: null, activeTab: 'overview', rankDimension: 'market', rankLimit: 20, chartIndex: 0, powerTrendMode: 'both', powerTrendModes,
      trustMetricFilter: 'all', trustOpenPanels: ['sources', 'dimensions', 'issues'],
      contextText: '', contextSource: '手工补充文本', contextFiles: [], contextUploading: false, contextResult: { total: 0, counts: {}, items: [] }, contextCategory: '', selectedContextIds: [], contextCategories,
      question: '', useLlm: true, chatLoading: false, chatMessages: [],
      report: null, reportLoading: false, exporting: '', planInstruction: '', planLoading: false, reportPlan: { market_observations: [], revision: 0 },
      dashboardComponents: [], dashboardDimensions: {}, indicatorCapabilities: { metrics: [], dimensions: [], aggregations: [], comparisons: [], suggested_indicators: [], matrix_row_templates: [], matrix_column_templates: [], segment_capabilities: [], segment_mapping_items: [], schema_hash: '' }, powerGroupCapabilities: { groups: [], mapping_options: [], mappings: [] }, selectedComponentId: '', selectedComponentTitle: '',
      reportConfigVisible: false, reportConfig: null, configSaving: false, configLoading: false, configLoadError: '', dashboardComponentsPromise: null, dashboardComponentScope: '',
      visualObservation: { id: '', custom_title: '', components: [], summary_segments: [], core_indicators: [], market_summary_rows: [], market_summary_columns: [], component_options: {}, manual_content: '', lookback_months: 12 },
      powerChartId, matrixSegments
    }
  },
  computed: {
    queryParams() {
      const value = { period_mode: this.periodMode }
      if (this.sheetName) value.sheet_name = this.sheetName
      if (this.periodMode === 'single') value.start_period = this.startPeriod
      if (this.periodMode === 'range') { value.start_period = this.startPeriod; value.end_period = this.endPeriod }
      if (this.periodMode === 'year') value.year = this.year
      return value
    },
    periodTitle() { const p = this.analysis.analysis_period || {}; return `当前口径：${p.display_label || '-'}；同比基期：${p.comparison_label || '无'}` },
    analysisScopeTitle() {
      const scope = this.analysis.analysis_scope || {}
      const sheet = scope.sheet_name || '全部工作表（综合分析）'
      return scope.strict_sheet ? `当前分析范围：工作表“${sheet}”。市场概览、排名和趋势图均只使用此表数据。` : `当前分析范围：${sheet}。`
    },
    llmTagType() {
      if (!this.llmState.enabled) return 'info'
      if (this.llmConnectionState === 'connected') return 'success'
      if (this.llmConnectionState === 'failed') return 'danger'
      return 'warning'
    },
    llmTagText() {
      if (!this.llmState.enabled) return '大模型未配置（规则模式可用）'
      if (this.llmConnectionState === 'connected') return `大模型：${this.llmState.model || '连接正常'}`
      if (this.llmConnectionState === 'failed') return '大模型连接失败（规则模式可用）'
      return `大模型：${this.llmState.model || '已配置'}（未验证）`
    },
    llmStatusTip() {
      if (!this.llmState.enabled) return '未配置 LLM_API_KEY / LLM_BASE_URL / LLM_MODEL；确定性解析、统计和规则问答仍可正常使用。'
      const base = `接口：${this.llmState.base_url || '-'}；模型：${this.llmState.model || '-'}；网络：${this.llmState.proxy_configured ? '已配置授权代理' : '直连'}`
      return this.llmConnectionState === 'failed' ? `${base}；最近失败原因：${this.llmLastError || '请点击“测试模型连接”查看详情'}` : base
    },
    reportPlanStatus() { const observations = this.reportPlan.market_observations || []; return `动态报告计划 v${this.reportPlan.revision || 0}：${observations.length ? `已编排 ${observations.length} 个市场观察` : '1.1 市场观察尚未编排'}` },
    summaryCards() { return Object.keys((this.analysis && this.analysis.summary) || {}).filter(k => this.analysis.summary[k] !== null).map(key => ({ key, label: metricLabels[key] || key, value: this.analysis.summary[key] })) },
    canExecuteAnalysis() { return !this.uploading && !this.loading && (!!this.datasetId || this.analysisFiles.length > 0) },
    uploadButtonText() { return this.uploading ? '正在上传并解析' : (this.loading ? '正在执行分析' : '执行分析') },
    analysisStep() {
      if (this.analysisPhase === 'completed' && this.analysis) return 2
      if (['preparing', 'analyzing', 'failed'].includes(this.analysisPhase) && this.uploadJob && this.uploadJob.status === 'success') return 1
      return 0
    },
    analysisPhaseText() {
      if (this.analysisPhase === 'parsing') return (this.uploadJob && this.uploadJob.message) || '正在上传并解析文件'
      if (this.analysisPhase === 'preparing') return '文件解析完成，正在读取工作表和分析周期'
      if (this.analysisPhase === 'analyzing') return '文件解析完成，正在生成市场概览、排名、趋势图和数据可信度'
      if (this.analysisPhase === 'completed') return '分析结果已生成'
      if (this.analysisPhase === 'failed') return (this.uploadJob && this.uploadJob.status === 'failed' && this.uploadJob.message) || '分析结果生成失败，请根据提示重试'
      return (this.uploadJob && this.uploadJob.message) || ''
    },
    allRankRows() { return ((this.analysis && this.analysis.rankings) || {})[this.rankDimension] || [] },
    rankRows() { return this.rankLimit ? this.allRankRows.slice(0, this.rankLimit) : this.allRankRows },
    rankingOption() {
      const rows = this.rankRows.slice(0, 20), names = rows.map(x => x['对象']), values = rows.map(x => x['销量/数值'])
      return {
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, confine: true },
        grid: { left: 16, right: 32, top: 18, bottom: 42, containLabel: true },
        xAxis: {
          type: 'value',
          minInterval: 1,
          axisLabel: { hideOverlap: true, formatter: value => this.formatAxisNumber(value) },
          splitLine: { lineStyle: { color: '#ebeef5' } }
        },
        yAxis: {
          type: 'category',
          data: names.slice().reverse(),
          axisTick: { show: false },
          axisLabel: { width: 118, overflow: 'truncate', margin: 10 }
        },
        series: [{ type: 'bar', data: values.slice().reverse(), barMaxWidth: 22, itemStyle: { color: '#409EFF', borderRadius: [0, 3, 3, 0] } }]
      }
    },
    powerOption() {
      const rows = (this.analysis && this.analysis.power_share) || []
      return {
        tooltip: { trigger: 'item', formatter: '{b}<br/>{c}（{d}%）', confine: true },
        legend: { type: 'scroll', orient: 'vertical', right: 12, top: 'middle', itemWidth: 16, itemHeight: 10 },
        series: [{
          type: 'pie',
          radius: ['38%', '68%'],
          center: ['36%', '50%'],
          minAngle: 2,
          avoidLabelOverlap: true,
          itemStyle: { borderColor: '#fff', borderWidth: 2 },
          label: { show: true, formatter: '{b}\n{d}%', overflow: 'truncate', width: 86 },
          labelLine: { length: 10, length2: 8 },
          emphasis: { label: { fontWeight: 'bold' }, scaleSize: 6 },
          data: rows.map(x => ({ name: x['动力类型'], value: x['销量/数值'] }))
        }],
        media: [{
          query: { maxWidth: 520 },
          option: {
            legend: { orient: 'horizontal', left: 'center', right: 'auto', top: 'auto', bottom: 0 },
            series: [{ center: ['50%', '42%'], radius: ['32%', '58%'], label: { show: false } }]
          }
        }]
      }
    },
    isPowerTrendSelected() { return !!(this.analysis && (this.analysis.line_charts || [])[this.chartIndex] && (this.analysis.line_charts || [])[this.chartIndex].title === '动力类型月度结构占比') },
    powerTrendModeHint() {
      const item = this.powerTrendModes.find(item => item.value === this.powerTrendMode)
      return item ? item.hint : ''
    },
    selectedLineChart() {
      const chart = this.analysis && (this.analysis.line_charts || [])[this.chartIndex]
      if (!chart) return null
      if (chart.title !== '动力类型月度结构占比') return chart
      return ((this.analysis && this.analysis.power_trend_variants) || {})[this.powerTrendMode] || chart
    },
    lineOption() { return this.chartOption(this.selectedLineChart || {}) },
    trustMetricCoverage() {
      const sources = (this.analysis && this.analysis.metric_sources) || {}
      return trustMetricKeys.map(key => {
        const source = sources[key]
        const label = metricLabels[key] || key
        return {
          key, label, available: Boolean(source),
          sourceText: source ? `${source['文件'] || '当前文件'} / ${source.Sheet || '当前工作表'}` : '-',
          impact: source ? metricImpacts[key] : `当前不展示${label}相关结果，不影响其他已识别指标`
        }
      })
    },
    filteredTrustMetrics() {
      if (this.trustMetricFilter === 'available') return this.trustMetricCoverage.filter(item => item.available)
      if (this.trustMetricFilter === 'missing') return this.trustMetricCoverage.filter(item => !item.available)
      return this.trustMetricCoverage
    },
    availableTrustMetrics() { return this.trustMetricCoverage.filter(item => item.available) },
    trustDataType() {
      const labels = this.availableTrustMetrics.map(item => item.label)
      if (!labels.length) return '暂未识别'
      if (labels.length === 1) return `${labels[0]}数据`
      if (labels.length <= 3) return labels.join('、')
      return `${labels.length}类指标数据`
    },
    trustSourceRows() {
      const sources = (this.analysis && this.analysis.metric_sources) || {}
      return trustMetricKeys.filter(key => sources[key]).map(key => {
        const source = sources[key]
        return {
          metric: metricLabels[key] || source['指标'] || key,
          file: source['文件'] || '-', sheet: source.Sheet || '-',
          sourceMetric: source['源指标'] || source['指标'] || metricLabels[key] || '-',
          aggregation: source['聚合规则'] || '-'
        }
      })
    },
    trustDimensionRows() {
      const dimensions = (this.analysis && this.analysis.dimension_integrity) || {}
      return Object.keys(dimensions).map(key => {
        const item = dimensions[key] || {}
        const missing = item['当前分析周期未进入输出'] || item['最新周期未进入输出'] || []
        const complete = item['状态'] === '完整' && !missing.length
        return {
          key, dimension: dimensionLabels[key] || key,
          allCount: Number(item['全周期标准化对象数'] || 0).toLocaleString('zh-CN'),
          currentCount: Number(item['当前分析周期有值对象数'] != null ? item['当前分析周期有值对象数'] : item['最新周期有值对象数'] || 0).toLocaleString('zh-CN'),
          outputCount: Number(item['排名/分析输出对象数'] || 0).toLocaleString('zh-CN'),
          complete,
          note: complete ? '当前周期有值对象已完整进入分析' : `${missing.length}个对象未进入分析${missing.length ? `：${missing.slice(0, 5).join('、')}${missing.length > 5 ? '等' : ''}` : ''}`
        }
      })
    },
    trustIncompleteDimensions() { return this.trustDimensionRows.filter(item => !item.complete) },
    trustIssueRows() {
      return ((this.analysis && this.analysis.warnings) || []).filter(message => !this.isTrustInformation(message)).map(message => {
        const isError = /无法执行|没有可识别|没有可用数据|无法计算当前/.test(message)
        return {
          level: isError ? '错误' : '警告', type: isError ? 'danger' : 'warning', problem: message,
          impact: isError ? '当前分析的部分或全部结果无法生成' : '可能影响部分指标或时间范围的完整性',
          suggestion: this.trustWarningSuggestion(message)
        }
      })
    },
    trustIssueRowsWithDimensions() {
      const dimensions = this.trustIncompleteDimensions.map(item => ({
        level: '警告', type: 'warning', problem: `${item.dimension}存在未进入分析的对象`, impact: item.note, suggestion: '核对该维度的空值、名称和当前周期数值'
      }))
      return this.trustIssueRows.concat(dimensions)
    },
    trustIssueCount() { return this.trustIssueRowsWithDimensions.length },
    trustStatus() {
      const hasBlocking = this.trustIssueRows.some(item => item.type === 'danger')
      if (!this.availableTrustMetrics.length || hasBlocking) return { title: '无法正常分析', type: 'danger', icon: 'el-icon-circle-close' }
      if (this.trustIssueCount) return { title: '部分数据需确认', type: 'warning', icon: 'el-icon-warning-outline' }
      return { title: '数据可用', type: 'success', icon: 'el-icon-circle-check' }
    },
    trustConclusion() {
      const available = this.availableTrustMetrics.map(item => item.label)
      const missing = this.trustMetricCoverage.filter(item => !item.available).map(item => item.label)
      if (!available.length) return '当前文件没有识别到可用于计算的核心指标，请检查文件内容、工作表名称和字段名称。'
      const main = `当前已识别${available.join('、')}，可以正常生成对应的概览、排名、结构或趋势结果。`
      const missingText = missing.length ? `文件未提供${missing.join('、')}，这些指标不会展示，但不影响已识别指标。` : '核心指标均已提供。'
      const issueText = this.trustIssueCount ? `另有${this.trustIssueCount}项内容需要确认。` : '未发现影响当前分析的数据质量问题。'
      return main + missingText + issueText
    },
    trustCalculation() {
      const period = (this.analysis && this.analysis.analysis_period) || {}
      const scope = (this.analysis && this.analysis.analysis_scope) || {}
      const audit = (this.analysis && this.analysis.calculation_audit) || {}
      const isRange = Boolean(period.is_range || ['range', 'year'].includes(period.mode))
      const missing = audit['缺失趋势月份'] || []
      return {
        scope: scope.strict_sheet ? `仅工作表“${scope.sheet_name || this.sheetName || '-'}”` : (scope.sheet_name || '全部工作表（综合分析）'),
        period: period.display_label || audit['快照周期'] || '-', comparison: period.comparison_label || '无可用同比基期',
        comparisonRule: isRange ? '所选区间累计值与去年同期累计值比较' : '当前月份与去年同月比较',
        aggregation: isRange ? '销量、产量等流量指标累计求和；库存取区间期末值' : '当前月份单月值',
        trendScope: audit['趋势周期'] || period.trend_scope_label || '-',
        missingPeriods: missing.length ? missing.join('、') : '无'
      }
    },
    trustAnomalies() { return (this.analysis && this.analysis.anomalies) || [] },
    filteredContextItems() { return (this.contextResult.items || []).filter(item => !this.contextCategory || item.category === this.contextCategory) },
    availableLineCharts() { return ((this.analysis && this.analysis.line_charts) || []).map(chart => ({ title: chart.title })) },
    dashboardComponentGroups() {
      const groups = []
      const components = this.dashboardComponents || []
      components.forEach(item => {
        const name = item.group || '其他'
        let group = groups.find(x => x.name === name)
        if (!group) { group = { name, items: [] }; groups.push(group) }
        group.items.push(item)
      })
      return groups
    },
    selectedVisualComponents() {
      const catalog = this.dashboardComponents || []
      return (this.visualObservation.components || []).map(id => catalog.find(item => item.id === id) || { id, title: this.componentNames([id]), group: '当前不可用' })
    },
    hasMatrixConfig() { return !!(this.visualObservation.market_summary_rows.length && this.visualObservation.market_summary_columns.length) },
    matrixSegmentOptions() {
      const capabilities = this.indicatorCapabilities.segment_capabilities || []
      if (!capabilities.length) return this.matrixSegments.map(item => ({ ...item, disabled: false, status: 'reliable' }))
      return capabilities.map(item => ({
        value: item.id,
        label: item.label,
        disabled: !item.available,
        status: item.status,
        reason: item.reason,
        recordCoverage: item.record_coverage,
        valueCoverage: item.value_coverage
      }))
    },
    reviewSegmentMappingItems() {
      return (this.indicatorCapabilities.segment_mapping_items || []).filter(item => item.requires_review)
    },
    segmentSourceSummary() {
      const total = (this.indicatorCapabilities.segment_capabilities || []).find(item => item.id === 'total_market') || {}
      const files = (total.source_files || []).join('、') || '当前上传文件'
      const sheets = (total.source_sheets || []).join('、') || (this.sheetName || '当前分析工作表')
      const metric = total.source_metric_label || total.source_metric || '当前主销量'
      const period = (this.analysis && this.analysis.analysis_period && this.analysis.analysis_period.display_label) || '-'
      const rowRange = total.source_row_start && total.source_row_end ? `；源表行：第${total.source_row_start}—${total.source_row_end}行（共${total.record_count || 0}条有效记录）` : ''
      return `文件：${files}；工作表：${sheets}；主统计字段：${metric}；周期：${period}${rowRange}`
    },
    reportContextSections() {
      if (!this.report) return []
      const numbers = this.report.section_numbers || {}
      return this.contextCategories.map(item => ({ key: item.value, label: item.label, number: numbers[item.value] || '', items: this.report[item.value] || [] }))
    },
    reportRankingSections() { if (!this.report) return []; return [{ key: 'market', label: '市场排名', rows: this.report.top_market || [] }, { key: 'oem', label: '车企排名', rows: this.report.top_oem || [] }, { key: 'brand', label: '品牌排名', rows: this.report.top_brand || [] }, { key: 'model', label: '车型排名', rows: this.report.top_model || [] }] }
  },
  created() {
    this.loadLlmStatus()
    marketHealth().then(data => {
      this.serviceBuild = data || {}
      if (data.api_contract !== requiredApiContract) {
        this.serviceOnline = false
        this.$modal.msgError(`Python服务版本过旧（当前 ${data.api_contract || '未知'}），请重启 D:\\项目\\ruoyi-master\\ruoyi-business\\market-agent 后端服务`)
        return
      }
      this.serviceOnline = true
    }).catch(() => { this.serviceOnline = false })
  },
  beforeDestroy() { if (this.uploadTimer) clearTimeout(this.uploadTimer) },
  methods: {
    loadLlmStatus() {
      return llmStatus().then(data => {
        this.llmState = data
        this.llmConnectionState = data.enabled ? 'unknown' : 'disabled'
        this.llmLastError = ''
      }).catch(() => {
        this.llmState = { enabled: false, model: '', base_url: '', api_key_configured: false, proxy_configured: false, network_mode: 'direct' }
        this.llmConnectionState = 'disabled'
      })
    },
    testLlm() {
      this.llmTesting = true
      testLlmConnection().then(data => {
        if (data.success) {
          this.llmConnectionState = 'connected'
          this.llmLastError = ''
          this.$modal.msgSuccess(`大模型连接正常：${data.message || ''}`)
        } else {
          this.llmConnectionState = 'failed'
          this.llmLastError = data.message || '未返回结果'
          this.$modal.msgError(`大模型连接失败：${this.llmLastError}`)
        }
      }).catch(error => {
        this.llmConnectionState = 'failed'
        this.llmLastError = error && error.message ? error.message : '模型连接请求未完成'
        this.$modal.msgError(`大模型连接失败：${this.llmLastError}`)
      }).finally(() => { this.llmTesting = false })
    },
    clearAnalysisPresentation() {
      this.analysis = null
      this.trustMetricFilter = 'all'
      this.report = null
      this.dashboardComponents = []
      this.dashboardDimensions = {}
      this.dashboardComponentScope = ''
      this.selectedComponentId = ''
      this.selectedComponentTitle = ''
      this.chartIndex = 0
      this.chatMessages = []
      this.question = ''
      this.contextResult = { total: 0, counts: {}, items: [] }
      this.selectedContextIds = []
    },
    resetForNewFile() {
      this.datasetId = ''
      this.sheets = []
      this.sheetName = ''
      this.periods = []
      this.years = []
      this.startPeriod = ''
      this.endPeriod = ''
      this.year = ''
      this.uploadJob = null
      this.analysisPhase = 'idle'
      if (this.uploadTimer) { clearTimeout(this.uploadTimer); this.uploadTimer = null }
      this.clearAnalysisPresentation()
    },
    onAnalysisFileChange(file, files) { this.analysisFiles = files; this.resetForNewFile() },
    onAnalysisFileRemove(file, files) { this.analysisFiles = files; this.resetForNewFile() },
    clearAnalysisInput() {
      this.analysisFiles = []
      this.$refs.analysisUpload && this.$refs.analysisUpload.clearFiles()
      this.resetForNewFile()
    },
    markAnalysisStale() { if (this.datasetId) this.clearAnalysisPresentation() },
    executeAnalysis() {
      if (!this.datasetId) {
        if (!this.analysisFiles.length) return this.$modal.msgWarning('请先选择待分析的Excel/CSV文件')
        const invalidFile = this.analysisFiles.find(file => !(file && file.raw instanceof Blob))
        if (invalidFile) {
          // 浏览器刷新后 File 二进制对象无法恢复。若缓存里只剩文件名，
          // FormData 会成为无效请求并在进入业务控制器前失败。
          this.clearAnalysisInput()
          return this.$modal.msgError('所选文件已失效，请重新选择Excel/CSV文件后再执行分析')
        }
        return this.createAnalysisUploadJob()
      }
      return this.runAnalysis()
    },
    createAnalysisUploadJob() {
      this.uploading = true
      this.analysisPhase = 'parsing'
      this.clearAnalysisPresentation()
      const files = this.analysisFiles.map(file => file.raw)
      return createMarketUploadJob(files).then(job => {
        this.uploadJob = job
        this.pollAnalysisUploadJob()
      }).catch(error => {
        this.uploading = false
        this.analysisPhase = 'failed'
        console.error('创建市场分析上传任务失败', error)
      })
    },
    pollAnalysisUploadJob() {
      if (!this.uploadJob || !this.uploadJob.job_id) return
      getMarketUploadJob(this.uploadJob.job_id).then(job => {
        this.uploadJob = job
        if (job.status === 'success') {
          const result = job.result || {}
          if (!result.dataset_id) {
            this.uploading = false
            this.analysisPhase = 'failed'
            return this.$modal.msgError('文件已解析，但未取得可分析的数据集，请重新执行分析')
          }
          this.datasetId = result.dataset_id
          this.uploading = false
          this.analysisPhase = 'preparing'
          this.loadDataset().then(() => this.runAnalysis()).catch(error => {
            this.analysisPhase = 'failed'
            console.error('读取工作表或分析周期失败', error)
          })
        } else if (['failed', 'cancelled'].includes(job.status)) {
          this.uploading = false
          this.analysisPhase = 'failed'
          if (job.status === 'failed') this.$modal.msgError(job.message || '文件解析失败')
        } else {
          this.uploadTimer = setTimeout(this.pollAnalysisUploadJob, 1200)
        }
      }).catch(error => {
        this.uploading = false
        this.analysisPhase = 'failed'
        console.error('读取市场分析上传任务失败', error)
      })
    },
    cancelAnalysisUpload() {
      if (!this.uploadJob || !this.uploadJob.job_id) return
      cancelMarketUploadJob(this.uploadJob.job_id).then(job => { this.uploadJob = job; this.$modal.msgSuccess('已提交取消请求') })
    },
    retryAnalysisUpload() {
      if (!this.uploadJob || !this.uploadJob.job_id) return
      this.uploading = true
      this.analysisPhase = 'parsing'
      retryMarketUploadJob(this.uploadJob.job_id).then(job => { this.uploadJob = job; this.pollAnalysisUploadJob() }).catch(() => { this.uploading = false; this.analysisPhase = 'failed' })
    },
    loadDataset() {
      if (!this.datasetId) return Promise.resolve()
      return getSheets(this.datasetId).then(data => {
        this.sheets = data.sheets || []
        if (this.sheetName && !this.sheets.includes(this.sheetName)) this.sheetName = ''
        this.loadContextItems()
        this.loadReportState()
        return this.loadPeriodOptions()
      })
    },
    loadPeriodOptions() {
      if (!this.datasetId) return Promise.resolve()
      const selectedSheet = this.sheetName
      return getPeriodOptions(this.datasetId, selectedSheet ? { sheet_name: selectedSheet } : {}).then(data => {
        if (selectedSheet !== this.sheetName) return
        this.periods = data.available_periods || []; this.years = data.available_years || []
        this.startPeriod = data.latest_period || ''; this.endPeriod = data.latest_period || ''; this.year = this.years[this.years.length - 1] || ''
      })
    },
    onSheetChange() {
      // 切换范围只更新可选周期；必须由用户再次点击“执行分析”才会计算图表。
      this.clearAnalysisPresentation()
      return this.loadPeriodOptions()
    },
    runAnalysis() {
      const requestScope = JSON.stringify({ datasetId: this.datasetId, ...this.queryParams })
      this.loading = true
      this.analysisPhase = 'analyzing'
      return getMarketAnalysis(this.datasetId, this.queryParams).then(data => {
        if (requestScope !== JSON.stringify({ datasetId: this.datasetId, ...this.queryParams })) return
        this.analysis = data
        this.analysisPhase = 'completed'
        this.chartIndex = 0
        const variants = data.power_trend_variants || {}
        if (!variants[this.powerTrendMode]) this.powerTrendMode = variants.both ? 'both' : (Object.keys(variants)[0] || 'both')
        const hasEmbeddedCatalog = Array.isArray(data.dashboard_components)
        this.dashboardComponents = hasEmbeddedCatalog ? data.dashboard_components : []
        this.dashboardDimensions = data.dashboard_dimensions || {}
        this.indicatorCapabilities = data.indicator_capabilities || this.indicatorCapabilities
        this.powerGroupCapabilities = data.power_group_capabilities || this.powerGroupCapabilities
        // 兼容尚未重启的旧版 Python 服务：旧响应没有目录时，打开窗口会回退请求专用接口。
        this.dashboardComponentScope = hasEmbeddedCatalog ? JSON.stringify({ datasetId: this.datasetId, ...this.queryParams }) : ''
      }).catch(error => {
        if (requestScope === JSON.stringify({ datasetId: this.datasetId, ...this.queryParams })) this.analysisPhase = 'failed'
        console.error('生成市场分析结果失败', error)
      }).finally(() => {
        if (requestScope === JSON.stringify({ datasetId: this.datasetId, ...this.queryParams })) this.loading = false
      })
    },
    saveContext() {
      addContextText(this.datasetId, { text: this.contextText, source_name: this.contextSource || '手工补充文本' }).then(data => { this.contextText = ''; this.contextResult = data; this.loadContextItems(); this.$modal.msgSuccess(`已添加 ${data.added} 条资料`) })
    },
    onContextChange(file, files) { this.contextFiles = files }, onContextRemove(file, files) { this.contextFiles = files },
    submitContextFiles() {
      this.contextUploading = true
      uploadContextFiles(this.datasetId, this.contextFiles).then(data => {
        this.contextFiles = []
        this.contextResult = data
        this.loadContextItems()
        if (data.added > 0) {
          this.$modal.msgSuccess(`解析完成，新增 ${data.added} 条资料，当前共 ${data.total} 条`)
        } else {
          this.$modal.msgSuccess(`解析完成：所选资料已存在，未重复入库，当前共 ${data.total} 条`)
        }
      }).finally(() => { this.contextUploading = false })
    },
    loadContextItems() { if (!this.datasetId) return; return getContext(this.datasetId).then(data => { this.contextResult = data; this.selectedContextIds = [] }) },
    onContextSelection(rows) { this.selectedContextIds = rows.map(row => row.id) },
    removeSelectedContext() {
      this.$confirm(`确定删除选中的 ${this.selectedContextIds.length} 条行业资料吗？`, '删除确认', { type: 'warning' }).then(() => deleteContextItems(this.datasetId, this.selectedContextIds)).then(() => { this.$modal.msgSuccess('已删除选中资料'); this.loadContextItems() }).catch(() => {})
    },
    removeAllContext() {
      this.$confirm('确定清空当前数据集的全部行业资料吗？该操作不可恢复。', '清空确认', { type: 'warning' }).then(() => clearContext(this.datasetId)).then(() => { this.$modal.msgSuccess('行业资料已清空'); this.loadContextItems() }).catch(() => {})
    },
    categoryLabel(value) { const item = this.contextCategories.find(x => x.value === value); return item ? item.label : value },
    loadDashboardComponents(force = false) {
      if (!this.datasetId) return Promise.resolve()
      const scope = JSON.stringify({ datasetId: this.datasetId, ...this.queryParams })
      if (!force && this.dashboardComponentScope === scope) return Promise.resolve(this.dashboardComponents)
      if (this.dashboardComponentsPromise && this.dashboardComponentScope === scope) return this.dashboardComponentsPromise
      this.dashboardComponentScope = scope
      const pending = getDashboardComponents(this.datasetId, this.queryParams).then(data => {
        if (this.dashboardComponentScope !== scope) return data.components || []
        this.dashboardComponents = data.components || []
        this.dashboardDimensions = data.dimensions || {}
        this.indicatorCapabilities = data.indicator_capabilities || this.indicatorCapabilities
        this.powerGroupCapabilities = data.power_group_capabilities || this.powerGroupCapabilities
        return this.dashboardComponents
      }).catch(error => {
        if (this.dashboardComponentScope === scope) {
          this.dashboardComponents = []
          this.dashboardDimensions = {}
          this.indicatorCapabilities = { metrics: [], dimensions: [], aggregations: [], comparisons: [], suggested_indicators: [], matrix_row_templates: [], matrix_column_templates: [], segment_capabilities: [], segment_mapping_items: [], schema_hash: '' }
          this.powerGroupCapabilities = { groups: [], mapping_options: [], mappings: [] }
          this.dashboardComponentScope = ''
        }
        throw error
      }).finally(() => {
        if (this.dashboardComponentsPromise === pending) this.dashboardComponentsPromise = null
      })
      this.dashboardComponentsPromise = pending
      return pending
    },
    selectComponent(id, title) {
      if (this.dashboardComponents.length && !this.dashboardComponents.some(item => item.id === id)) return this.$modal.msgWarning('当前数据和周期无法生成该组件')
      this.selectedComponentId = id; this.selectedComponentTitle = title; this.$modal.msgSuccess(`已选中：${title}`)
    },
    selectRankingComponent() { const value = rankingComponents[this.rankDimension]; this.selectComponent(value[0], value[1]) },
    selectCurrentChart() { if (this.selectedLineChart) this.selectComponent(`line_chart:${this.selectedLineChart.title}`, this.selectedLineChart.title) },
    clearSelectedComponent() { this.selectedComponentId = ''; this.selectedComponentTitle = '' },
    ask() {
      const text = this.question.trim(); if (!text) return
      this.chatMessages.push({ role: 'user', content: text }); this.question = ''; this.chatLoading = true
      const history = this.chatMessages.slice(-10).map(x => ({ role: x.role, content: x.content }))
      askMarketAgent({ dataset_id: this.datasetId, question: text, use_llm: this.useLlm, history, selected_component_id: this.selectedComponentId || null, selected_component_title: this.selectedComponentTitle || null, selected_chart_title: this.selectedComponentId.startsWith('line_chart:') ? this.selectedComponentTitle : null, ...this.queryParams }).then(data => {
        this.chatMessages.push({ role: 'assistant', content: data.answer, table: data.table || [], charts: data.charts || [], evidence: data.evidence_chain || [], warnings: data.warnings || [] })
        if (data.report_updated) { this.$modal.msgSuccess('报告编排已根据对话更新'); this.loadReportState(); this.generateReport() }
      }).finally(() => { this.chatLoading = false })
    },
    generateReport() {
      this.reportLoading = true
      return getMarketReport(this.datasetId, { ...this.queryParams, use_llm: this.useLlm }).then(data => { this.report = data }).finally(() => { this.reportLoading = false })
    },
    applyPlan() {
      this.planLoading = true
      updateReportPlan(this.datasetId, { instruction: this.planInstruction, use_llm: this.useLlm, selected_component_id: this.selectedComponentId || null, selected_component_title: this.selectedComponentTitle || null, selected_chart_title: this.selectedComponentId.startsWith('line_chart:') ? this.selectedComponentTitle : null, ...this.queryParams }).then(data => { this.reportPlan = data.plan || data; this.planInstruction = ''; this.$modal.msgSuccess('报告编排已更新'); this.generateReport() }).finally(() => { this.planLoading = false })
    },
    resetPlan() { resetReportPlan(this.datasetId).then(data => { this.reportPlan = data; this.$modal.msgSuccess('已清空 1.1 市场观察编排'); this.generateReport() }) },
    exportReport(format) {
      const observations = (this.reportPlan && this.reportPlan.market_observations) || []
      if (!observations.length || observations.some(item => !(item.components || []).length)) {
        this.$modal.msgWarning('请先完成 1.1 市场观察编排，并至少选择一个表格或图表组件')
        this.openReportConfig()
        return
      }
      this.exporting = format
      exportMarketReport(this.datasetId, format, this.queryParams).then(result => downloadMarketReport(result.file_name).then(blob => saveAs(new Blob([blob]), result.file_name))).finally(() => { this.exporting = '' })
    },
    loadReportState() {
      if (!this.datasetId) return Promise.resolve()
      return Promise.all([getReportConfig(this.datasetId), getReportPlan(this.datasetId)]).then(([config, plan]) => {
        this.reportConfig = config; this.reportPlan = plan
      })
    },
    prepareVisualObservation() {
      const observations = (this.reportPlan && this.reportPlan.market_observations) || []
      const active = observations.find(item => item.id === this.reportPlan.active_observation_id) || observations[0] || {}
      const availableIds = new Set((this.dashboardComponents || []).map(item => item.id))
      this.visualObservation = {
        id: active.id || '',
        custom_title: active.custom_title || '',
        components: (active.components || []).filter(id => availableIds.has(id)),
        summary_segments: [...(active.summary_segments || [])],
        core_indicators: (active.core_indicators || []).map(item => this.newIndicator(item)),
        market_summary_rows: (active.market_summary_rows || []).map(item => this.newMatrixRow(item)),
        market_summary_columns: (active.market_summary_columns || []).map(item => this.newMatrixColumn(item)),
        component_options: JSON.parse(JSON.stringify(active.component_options || {})),
        manual_content: active.manual_content || '',
        lookback_months: active.lookback_months || 12
      }
      this.ensurePowerChartOptions()
      this.ensureSegmentMappings()
    },
    openReportConfig() {
      if (!this.datasetId || !this.analysis) return this.$modal.msgWarning('请先载入数据集并执行分析')
      // 先显示窗口，再在窗口内加载；避免慢接口期间用户误以为按钮没有反应。
      this.reportConfig = this.reportConfig || { custom_title: '', include_weekly_content: true, include_anomalies: true }
      this.reportConfigVisible = true
      this.configLoading = true
      this.configLoadError = ''
      const failures = []
      const stateRequest = this.loadReportState().catch(() => { failures.push('报告配置') })
      // 分析完成时已加载过组件目录，点击按钮不再重复执行整套图表计算。
      const componentRequest = this.loadDashboardComponents().catch(() => { failures.push('图表组件') })
      Promise.all([stateRequest, componentRequest]).then(() => {
        this.prepareVisualObservation()
        if (failures.length) this.configLoadError = `${failures.join('、')}加载失败，请检查 Python 分析服务后重试。`
      }).finally(() => { this.configLoading = false })
    },
    saveVisualConfig() {
      if (!(this.visualObservation.components || []).length) return this.$modal.msgWarning('请至少选择一个表格或图表组件')
      const indicators = this.visualObservation.core_indicators || []
      if (indicators.some(item => !String(item.display_name || '').trim() || !item.metric || !item.aggregation)) return this.$modal.msgWarning('请完整填写每个核心指标的名称、数值字段和聚合方式')
      if (indicators.some(item => (item.filters || []).some(filter => !filter.dimension || !(filter.values || []).length))) return this.$modal.msgWarning('核心指标中存在未完成的筛选条件，请补充筛选值或删除该条件')
      const matrixRows = this.visualObservation.market_summary_rows || []
      const matrixColumns = this.visualObservation.market_summary_columns || []
      if ((matrixRows.length && !matrixColumns.length) || (!matrixRows.length && matrixColumns.length)) return this.$modal.msgWarning('二维市场概览至少需要一个报告行和一个指标列')
      if (matrixRows.some(item => !String(item.display_name || '').trim())) return this.$modal.msgWarning('请填写每个市场概览行的名称')
      if (matrixRows.some(item => item.segment === 'custom' && (item.filters || []).some(filter => !filter.dimension || !(filter.values || []).length))) return this.$modal.msgWarning('自定义报告行中存在未完成的筛选条件')
      const hasScopeCapabilities = (this.indicatorCapabilities.segment_capabilities || []).length > 0
      const unsupportedScope = hasScopeCapabilities && matrixRows.find(row => row.segment !== 'custom' && !(this.segmentCapability(row.segment) || {}).available)
      if (unsupportedScope) return this.$modal.msgWarning(`“${unsupportedScope.display_name}”的统计口径无法由当前Excel计算，请更换口径或补充数据`)
      if (matrixColumns.some(item => !String(item.display_name || '').trim() || !item.metric || !item.aggregation)) return this.$modal.msgWarning('请完整填写二维市场概览的指标列')
      this.configSaving = true
      const payload = {
        report_title: this.reportConfig.custom_title || '',
        observation_id: this.visualObservation.id || null,
        observation_title: this.visualObservation.custom_title || '',
        component_ids: [...this.visualObservation.components],
        summary_segments: [...this.visualObservation.summary_segments],
        core_indicators: JSON.parse(JSON.stringify(this.visualObservation.core_indicators || [])),
        market_summary_rows: JSON.parse(JSON.stringify(matrixRows)),
        market_summary_columns: JSON.parse(JSON.stringify(matrixColumns)),
        component_options: JSON.parse(JSON.stringify(this.visualObservation.component_options || {})),
        manual_content: this.visualObservation.manual_content || '',
        lookback_months: this.visualObservation.lookback_months || 12,
        include_weekly_content: !!this.reportConfig.include_weekly_content,
        include_anomalies: !!this.reportConfig.include_anomalies,
        ...this.queryParams
      }
      saveVisualReportPlan(this.datasetId, payload).then(data => {
        this.reportPlan = data.plan
        this.reportConfig = data.config
        this.reportConfigVisible = false
        this.$modal.msgSuccess('1.1 市场观察已保存，目录、预览和导出已同步')
        return this.generateReport()
      }).finally(() => { this.configSaving = false })
    },
    clearVisualForm() {
      this.visualObservation.components = []
      this.visualObservation.summary_segments = []
      this.visualObservation.core_indicators = []
      this.visualObservation.market_summary_rows = []
      this.visualObservation.market_summary_columns = []
      this.visualObservation.component_options = {}
      this.visualObservation.manual_content = ''
    },
    moveVisualComponent(index, offset) {
      const target = index + offset
      if (target < 0 || target >= this.visualObservation.components.length) return
      const ordered = [...this.visualObservation.components]
      const current = ordered[index]
      ordered.splice(index, 1)
      ordered.splice(target, 0, current)
      this.visualObservation.components = ordered
    },
    removeVisualComponent(id) { this.visualObservation.components = this.visualObservation.components.filter(item => item !== id) },
    indicatorDimensionValues(field) {
      const dimension = (this.indicatorCapabilities.dimensions || []).find(item => item.field === field)
      return dimension ? dimension.values || [] : []
    },
    dimensionLabel(field) { return dimensionLabels[field] || field || '字段' },
    segmentCapability(segmentId) {
      return (this.indicatorCapabilities.segment_capabilities || []).find(item => item.id === segmentId) || null
    },
    segmentStatusText(capability) {
      if (!capability) return '待校验'
      return { reliable: '可信', review: '待确认', unavailable: '不可用' }[capability.status] || '待校验'
    },
    segmentStatusType(capability) {
      if (!capability) return 'info'
      return { reliable: 'success', review: 'warning', unavailable: 'danger' }[capability.status] || 'info'
    },
    scopePercent(value) {
      const number = Number(value)
      return Number.isFinite(number) ? `${(number * 100).toFixed(1)}%` : '-'
    },
    segmentOptionLabel(option) {
      if (option.value === 'custom') return option.label
      const suffix = { reliable: '可信', review: '待确认', unavailable: '不可用' }[option.status]
      return suffix ? `${option.label}（${suffix}）` : option.label
    },
    segmentRuleText(segmentId) {
      const capability = this.segmentCapability(segmentId)
      if (!capability) return segmentId === 'custom' ? '按当前Excel真实存在的字段和值筛选' : '保存时将由服务端校验当前Excel是否支持该口径'
      const source = capability.source_dimension ? `字段：${this.dimensionLabel(capability.source_dimension)}` : '当前主销量全部有效明细'
      const sourceRows = capability.source_row_start && capability.source_row_end ? `；源表第${capability.source_row_start}—${capability.source_row_end}行，共${capability.record_count || 0}条` : ''
      const coverage = `记录覆盖率${this.scopePercent(capability.record_coverage)}，数值覆盖率${this.scopePercent(capability.value_coverage)}`
      const unclassified = (capability.unclassified_values || []).length ? `；未分类：${capability.unclassified_values.slice(0, 6).join('、')}${capability.unclassified_values.length > 6 ? '等' : ''}` : ''
      return `${capability.reason || ''}；${source}${sourceRows}；${coverage}${unclassified}`
    },
    ensureSegmentMappings() {
      if (!this.visualObservation.component_options) this.$set(this.visualObservation, 'component_options', {})
      let options = this.visualObservation.component_options.market_summary
      if (!options) {
        options = { segment_mappings: {} }
        this.$set(this.visualObservation.component_options, 'market_summary', options)
      }
      if (!options.segment_mappings) this.$set(options, 'segment_mappings', {})
    },
    segmentMappingValue(item) {
      const marketOptions = (this.visualObservation.component_options || {}).market_summary || {}
      const mapping = (marketOptions.segment_mappings || {})[item.dimension] || {}
      return mapping[item.source_value] || item.selected_segment || item.default_segment || 'unclassified'
    },
    setSegmentMapping(item, value) {
      this.ensureSegmentMappings()
      const mappings = this.visualObservation.component_options.market_summary.segment_mappings
      if (!mappings[item.dimension]) this.$set(mappings, item.dimension, {})
      this.$set(mappings[item.dimension], item.source_value, value)
    },
    segmentMappingSource(item) {
      const related = (this.indicatorCapabilities.segment_capabilities || []).find(capability => capability.source_dimension === item.dimension) || {}
      return `${(related.source_sheets || []).join('、') || this.sheetName || '当前工作表'} / ${this.dimensionLabel(item.dimension)}`
    },
    ensurePowerChartOptions() {
      if (!this.visualObservation.component_options) this.$set(this.visualObservation, 'component_options', {})
      let options = this.visualObservation.component_options[this.powerChartId]
      if (!options) {
        options = { series: ['new_energy', 'fuel'], power_type_mapping: {} }
        this.$set(this.visualObservation.component_options, this.powerChartId, options)
      }
      if (!Array.isArray(options.series) || !options.series.length) this.$set(options, 'series', ['new_energy', 'fuel'])
      if (!options.power_type_mapping) this.$set(options, 'power_type_mapping', {})
      ;(this.powerGroupCapabilities.mappings || []).forEach(item => {
        if (!Object.prototype.hasOwnProperty.call(options.power_type_mapping, item.source_value)) {
          this.$set(options.power_type_mapping, item.source_value, item.default_group)
        }
      })
    },
    powerChartOptions() {
      this.ensurePowerChartOptions()
      return this.visualObservation.component_options[this.powerChartId]
    },
    newMatrixRow(source) {
      const base = source || {}
      return {
        id: base.id || `summary_row_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`,
        display_name: base.display_name || '自定义行',
        segment: base.segment || 'custom',
        filters: JSON.parse(JSON.stringify(base.filters || []))
      }
    },
    applyMatrixTemplate() {
      this.visualObservation.market_summary_rows = (this.indicatorCapabilities.matrix_row_templates || []).map(item => this.newMatrixRow(item))
      this.visualObservation.market_summary_columns = (this.indicatorCapabilities.matrix_column_templates || []).map(item => this.newMatrixColumn(item))
      this.visualObservation.core_indicators = []
      this.visualObservation.summary_segments = []
    },
    clearMatrixConfig() {
      this.visualObservation.market_summary_rows = []
      this.visualObservation.market_summary_columns = []
    },
    addMatrixRow() { this.visualObservation.market_summary_rows.push(this.newMatrixRow()) },
    addMatrixColumn() {
      if (!(this.indicatorCapabilities.metrics || []).length) return this.$modal.msgWarning('当前Excel没有可计算的指标字段')
      this.visualObservation.market_summary_columns.push(this.newMatrixColumn())
    },
    addMatrixRowFilter(row) { row.filters.push({ dimension: '', values: [] }) },
    moveMatrixItem(listName, index, offset) {
      const list = this.visualObservation[listName]
      const target = index + offset
      if (!Array.isArray(list) || target < 0 || target >= list.length) return
      list.splice(target, 0, list.splice(index, 1)[0])
    },
    newIndicator(source) {
      const metric = (this.indicatorCapabilities.metrics || [])[0] || {}
      const base = source || {}
      const rawComparisons = Array.isArray(base.comparisons) ? base.comparisons : [base.comparison]
      const comparisons = [...new Set(rawComparisons.filter(item => item === 'yoy' || item === 'mom'))]
      return {
        id: base.id || `indicator_${Date.now()}_${Math.random().toString(16).slice(2, 8)}`,
        display_name: base.display_name || metric.label || '自定义指标',
        metric: base.metric || metric.field || '',
        aggregation: base.aggregation || metric.default_aggregation || 'sum',
        filters: JSON.parse(JSON.stringify(base.filters || [])),
        comparisons,
        show_share: !!base.show_share,
        unit: base.unit == null ? (metric.default_unit || '') : base.unit,
        decimals: Number.isInteger(base.decimals) ? base.decimals : 0
      }
    },
    newMatrixColumn(source) {
      const column = this.newIndicator(source)
      const metric = (this.indicatorCapabilities.metrics || []).find(item => item.field === column.metric)
      if (metric) {
        column.display_name = metric.label
        column.aggregation = metric.default_aggregation || 'sum'
        column.unit = metric.default_unit || ''
        column.label_locked = true
      }
      return column
    },
    matrixMetric(column) { return (this.indicatorCapabilities.metrics || []).find(item => item.field === (column || {}).metric) || {} },
    matrixColumnLabel(column) { const metric = this.matrixMetric(column); return metric.label || (column || {}).metric || '请先选择指标' },
    matrixMetricIsFixed(column) { const metric = this.matrixMetric(column); return !!metric.derived || metric.field === 'inventory' },
    matrixColumnRule(column) {
      const metric = this.matrixMetric(column)
      if (!metric.field) return '请选择当前 Excel 中可计算的指标。'
      if (metric.source_kind === 'formula') return `计算公式：${metric.data_rule}；依赖数据不足时将显示“缺失”，不会推测。`
      const sheets = (metric.source_sheets || []).filter(Boolean)
      return `${metric.data_rule || '当前Excel字段'}${sheets.length ? `；来源工作表：${sheets.join('、')}` : ''}`
    },
    onMatrixColumnMetricChange(column) {
      const metric = this.matrixMetric(column)
      if (!metric.field) return
      column.display_name = metric.label
      column.aggregation = metric.default_aggregation || 'sum'
      column.unit = metric.default_unit || ''
      column.label_locked = true
    },
    addCoreIndicator() {
      if (!(this.indicatorCapabilities.metrics || []).length) return this.$modal.msgWarning('当前Excel没有可计算的数值字段')
      this.visualObservation.core_indicators.push(this.newIndicator())
      this.visualObservation.summary_segments = []
    },
    applySuggestedIndicators() {
      this.visualObservation.core_indicators = (this.indicatorCapabilities.suggested_indicators || []).map(item => this.newIndicator(item))
      this.visualObservation.summary_segments = []
    },
    removeCoreIndicator(index) { this.visualObservation.core_indicators.splice(index, 1) },
    moveCoreIndicator(index, offset) {
      const target = index + offset
      if (target < 0 || target >= this.visualObservation.core_indicators.length) return
      const rows = [...this.visualObservation.core_indicators]
      rows.splice(target, 0, rows.splice(index, 1)[0])
      this.visualObservation.core_indicators = rows
    },
    addIndicatorFilter(indicator) { indicator.filters.push({ dimension: '', values: [] }) },
    resetIndicatorFilter(filter) { filter.values = [] },
    onIndicatorMetricChange(indicator) {
      const metric = (this.indicatorCapabilities.metrics || []).find(item => item.field === indicator.metric)
      if (!metric) return
      indicator.aggregation = metric.default_aggregation || 'sum'
      indicator.unit = metric.default_unit || ''
      if (!indicator.display_name) indicator.display_name = metric.label
    },
    chartOption(chart) {
      const series = chart.series || []
      const periods = chart.periods || [...new Set(series.reduce((all, s) => all.concat((s.points || []).map(p => p['时间'])), []))]
      return {
        title: { text: chart.title || '', textStyle: { fontSize: 15 } }, tooltip: { trigger: 'axis', confine: true }, legend: { top: 30, type: 'scroll' },
        grid: { left: 18, right: 28, top: 78, bottom: 38, containLabel: true },
        xAxis: { type: 'category', data: periods, axisLabel: { hideOverlap: true } },
        yAxis: { type: 'value', axisLabel: { formatter: chart.y_format === 'percent' ? value => `${(value * 100).toFixed(0)}%` : value => this.formatAxisNumber(value) } },
        series: series.map(s => ({ name: s.name, type: 'line', smooth: true, connectNulls: false, showSymbol: periods.length < 36, data: periods.map(period => { const point = (s.points || []).find(p => p['时间'] === period); return point ? point['数值'] : null }) }))
      }
    },
    rankingChartOption(rows, title) {
      const values = rows.slice(0, 12).reverse()
      return { title: { text: title, textStyle: { fontSize: 15 } }, tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, confine: true }, grid: { left: 15, right: 25, top: 48, bottom: 30, containLabel: true }, xAxis: { type: 'value', axisLabel: { formatter: value => this.formatAxisNumber(value) } }, yAxis: { type: 'category', data: values.map(x => x['对象']), axisLabel: { width: 110, overflow: 'truncate' } }, series: [{ type: 'bar', data: values.map(x => x['销量/数值']), barMaxWidth: 20 }] }
    },
    powerChartOption(rows) {
      return { tooltip: { trigger: 'item', formatter: '{b}: {c}（{d}%）' }, legend: { orient: 'vertical', right: 5, top: 'middle' }, series: [{ type: 'pie', radius: ['35%', '65%'], center: ['36%', '50%'], data: rows.map(x => ({ name: x['动力类型'], value: x['销量/数值'] })) }] }
    },
    isTrustInformation(message) {
      const text = String(message || '')
      return /^未发现可用于计算/.test(text) || /^已通过Sheet名/.test(text) || /^当前分析周期/.test(text) || /^检测到标准月度序列/.test(text) || /^v\d+确定性规则/.test(text)
    },
    trustWarningSuggestion(message) {
      const text = String(message || '')
      if (/冲突值|重复/.test(text)) return '核对相同业务对象和周期的重复记录，确认正确数值口径'
      if (/缺失：|缺月|月份/.test(text)) return '补充缺失月份，或调整分析周期后重新执行分析'
      if (/没有可识别|time_period|时间/.test(text)) return '检查日期字段名称和格式，建议统一为YYYYMM或YYYY-MM'
      if (/没有可用数据|当前选择周期/.test(text)) return '确认所选周期在当前工作表中存在有效数值'
      if (/库存|期末/.test(text)) return '核对所选区间末月是否存在库存数据'
      return '根据提示核对原始文件、工作表和字段内容后重新执行分析'
    },
    formatAxisNumber(value) {
      const number = Number(value)
      if (!Number.isFinite(number)) return value
      if (Math.abs(number) >= 100000000) return `${(number / 100000000).toFixed(number % 100000000 === 0 ? 0 : 1)}亿`
      if (Math.abs(number) >= 10000) return `${(number / 10000).toFixed(number % 10000 === 0 ? 0 : 1)}万`
      return number.toLocaleString('zh-CN')
    },
    formatNumber(value) { return typeof value === 'number' ? value.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) : (value == null ? '-' : value) },
    pretty(value) { return JSON.stringify(value || {}, null, 2) },
    componentNames(ids) {
      const labels = { market_summary: '市场核心指标表', market_top10: '市场/车种销量排名TOP10', oem_top10: 'OEM销量排名TOP10', brand_top10: '品牌销量排名TOP10', model_top10: '车型销量排名TOP10', power_structure: '动力类型结构占比', monthly_trend: '核心指标月度趋势', nev_share_trend: '新能源汽车销量占有率', nev_vs_ice_yoy: '新能源车与燃油车销量同比变化', all_line_charts: '全部可用折线图' }
      return (ids || []).map(id => String(id).startsWith('line_chart:') ? String(id).slice(11) : (labels[id] || id)).join('、')
    }
  }
}
</script>

<style scoped>
.market-dashboard { background:#f5f7fa;min-height:calc(100vh - 84px); }.control-card,.market-dashboard>.el-card { border:none; }.head { display:flex;justify-content:space-between;align-items:center;font-size:16px;font-weight:600; }
.space-top { margin-top:14px; }.service-status { display:flex;align-items:center;gap:8px;flex-wrap:wrap;justify-content:flex-end; }.metric-card { background:#fff;border-radius:6px;padding:18px 14px;margin-bottom:14px;border-left:3px solid #409eff;box-shadow:0 1px 3px rgba(0,0,0,.05); }
.analysis-file-item ::v-deep .el-upload__tip { margin-top:4px;line-height:1.4; }
.analysis-upload-progress { max-width:760px;margin:4px 0 0 72px;color:#606266;font-size:13px; }
.analysis-upload-progress .el-steps { margin-bottom:8px; }
.analysis-phase-message { display:flex;align-items:center;gap:8px;min-height:28px; }
.analysis-phase-success { color:#67c23a; }
.analysis-phase-error { color:#f56c6c; }
.metric-label { color:#909399;font-size:13px; }.metric-value { color:#303133;font-size:22px;font-weight:600;margin-top:7px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis; }
.tab-tools { margin:0 8px 14px 0; }.rank-limit { margin:0 0 14px; }.warning { margin-bottom:8px; }.context-source { width:300px;margin:12px 8px 12px 0; }.context-upload { display:inline-block;margin:12px 8px 0 0; }
.report-actions,.plan-actions,.chat-actions { display:flex;gap:8px;align-items:center;margin-bottom:14px; }.plan-actions { margin-top:10px; }.muted { color:#909399; }
.chat-card { margin-top:14px; }.message { margin-bottom:10px;padding:10px 12px;border-radius:5px;line-height:1.65; }.message.user { background:#ecf5ff; }.message.assistant { background:#f4f4f5; }.chat-actions { justify-content:space-between;margin-top:10px;margin-bottom:0; }
.component-button { margin:0 0 14px 8px; }.context-toolbar { display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:12px; }.selected-component { display:flex;gap:10px;align-items:center;margin-bottom:12px;color:#606266; }
.report-section { margin-top:20px;padding-top:4px;border-top:1px solid #ebeef5; }.report-component { padding:10px 0; }.event-card { margin-bottom:8px; }.event-card p { margin:8px 0;line-height:1.65;white-space:pre-wrap; }.event-card small { color:#909399; }
.danger-text { color:#f56c6c; }
.plan-overview { margin-bottom:14px; }.plan-overview p { margin:6px 0;line-height:1.6; }
.manual-observation-content { white-space:pre-wrap;line-height:1.75;padding:10px 0;color:#303133; }
.visual-composer-form { max-height:70vh;overflow-y:auto;padding:0 12px 8px 0;margin-top:10px; }.component-group { margin-bottom:12px; }.component-group-title { color:#606266;font-weight:600;margin:0 0 7px; }.component-group .el-checkbox { margin:0 8px 8px 0; }.component-group .el-checkbox.is-bordered + .el-checkbox.is-bordered { margin-left:0; }.visual-hint { color:#909399;font-size:12px;line-height:1.6;margin-left:8px; }
.indicator-actions { display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-bottom:10px; }.indicator-editor { border:1px solid #dcdfe6;border-radius:5px;padding:12px;margin:10px 0;background:#fafafa; }.indicator-editor-head,.indicator-filter-head { display:flex;justify-content:space-between;align-items:center;margin-bottom:10px; }.indicator-filter-head { margin:9px 0 3px;color:#606266; }.indicator-row { margin-top:9px; }
.matrix-editor-wrap { margin:4px 0 18px;padding:12px;border:1px solid #ebeef5;border-radius:6px;background:#fff; }.matrix-section-title { display:flex;align-items:center;justify-content:space-between;margin:4px 0 8px;color:#303133; }.compact-editor { background:#fcfcfd; }.matrix-field-label { margin:0 0 4px;color:#606266;font-size:12px;line-height:16px; }.matrix-data-rule { margin:8px 0 0;padding:7px 9px;color:#606266;font-size:12px;line-height:18px;background:#f5f7fa;border-radius:4px; }.power-mapping-list { margin:8px 0 16px;padding:10px 12px;border:1px solid #ebeef5;border-radius:5px;background:#fafafa; }
.scope-source-card,.scope-mapping-card { margin:0 0 12px;padding:11px 13px;border:1px solid #d9ecff;border-radius:5px;background:#f4f9ff; }.scope-mapping-card { border-color:#faecd8;background:#fdf6ec; }.scope-source-title { color:#303133;font-weight:600;margin-bottom:6px; }.scope-source-title i { margin-right:6px;color:#409eff; }.scope-source-content { color:#606266;font-size:13px;line-height:1.7; }.scope-mapping-row { margin-top:9px;display:flex;align-items:center; }.scope-mapping-card .visual-hint,.scope-source-card .visual-hint { margin-left:0; }.scope-mapping-row .visual-hint { display:inline-block;padding-top:7px; }.scope-data-rule { color:#606266; }.scope-audit-tag { margin-right:6px; }.scope-rule { color:#606266; }.scope-rule strong { color:#303133; }.scope-rule small { color:#909399; }.scope-rule-warning { color:#e6a23c; }.scope-rule-error { color:#f56c6c; }.scope-rule-success { color:#67c23a; }.scope-rule-muted { color:#909399; }.scope-rule-line { display:flex;align-items:flex-start;gap:7px;margin-top:8px;padding:7px 9px;color:#606266;font-size:12px;line-height:18px;background:#f5f7fa;border-radius:4px; }.scope-rule-line .el-tag { flex:none;margin-top:1px; }
.power-trend-settings-body { line-height:1.55; }.power-trend-settings-title { margin-bottom:10px;font-weight:600;color:#303133; }.power-trend-settings-body .el-radio-button { margin:0 4px 7px 0; }.power-trend-settings-body p { margin:4px 0 0;color:#909399;font-size:12px; }
.trust-page { padding:2px 2px 10px; }
.trust-hero { display:flex;align-items:flex-start;gap:14px;padding:17px 19px;border:1px solid;border-radius:7px; }
.trust-hero.is-success { color:#387d18;background:#f0f9eb;border-color:#c2e7b0; }.trust-hero.is-warning { color:#9c6814;background:#fdf6ec;border-color:#f5dab1; }.trust-hero.is-danger { color:#b83b3b;background:#fef0f0;border-color:#fbc4c4; }
.trust-hero-icon { flex:none;font-size:25px;line-height:28px; }.trust-hero-content { min-width:0; }.trust-hero-title { font-size:16px;font-weight:600;line-height:26px; }.trust-hero-text { margin-top:3px;line-height:1.7;color:#606266; }
.trust-summary { margin-top:12px; }.trust-summary-card { display:flex;flex-direction:column;justify-content:center;min-height:78px;padding:13px 16px;margin-bottom:12px;background:#f7f9fc;border:1px solid #ebeef5;border-radius:6px; }
.trust-summary-card span { color:#909399;font-size:12px; }.trust-summary-card strong { margin-top:7px;color:#303133;font-size:17px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis; }.trust-summary-card .text-success { color:#67c23a; }.trust-summary-card .text-warning { color:#e6a23c; }.trust-summary-card .text-danger { color:#f56c6c; }
.trust-section { margin-top:4px;padding:17px 18px;border:1px solid #ebeef5;border-radius:6px;background:#fff; }.trust-section-head { display:flex;align-items:flex-start;justify-content:space-between;gap:15px;margin-bottom:13px; }.trust-section-head h3 { margin:0;color:#303133;font-size:15px; }.trust-section-head p,.trust-panel-help { margin:6px 0 0;color:#909399;font-size:12px;line-height:1.6; }
.trust-collapse { margin-top:14px;border:1px solid #ebeef5;border-radius:6px;padding:0 16px; }.trust-collapse ::v-deep .el-collapse-item__header { font-weight:600;font-size:14px; }.trust-collapse ::v-deep .el-collapse-item__content { padding-bottom:18px; }
.collapse-title { display:inline-flex;align-items:center;gap:7px;color:#303133; }.collapse-title i { color:#409eff;font-size:16px; }.trust-panel-help { margin:0 0 12px; }.trust-descriptions { margin-bottom:2px; }.trust-badge { margin-left:5px; }
@media (max-width: 768px) { .trust-section-head { flex-direction:column; }.trust-hero { padding:14px; }.trust-collapse { padding:0 10px; } }
pre { white-space:pre-wrap;word-break:break-word;background:#f7f8fa;padding:12px;border-radius:4px;max-height:480px;overflow:auto; }
</style>
