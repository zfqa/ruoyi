<template>
  <div class="app-container kb-page">
    <el-tabs v-model="activeTab" @tab-click="onTabClick">
      <el-tab-pane label="固定资料" name="sources">
        <div class="knowledge-category-filter">
          <span class="category-title">知识分类</span>
          <el-radio-group v-model="queryParams.sourceType" size="small" @change="handleCategoryChange">
            <el-radio-button label="">全部</el-radio-button>
            <el-radio-button v-for="t in sourceTypes" :key="t.value" :label="t.value">{{ t.label }}</el-radio-button>
          </el-radio-group>
        </div>
        <el-form :model="queryParams" ref="queryForm" size="small" :inline="true">
          <el-form-item label="资料名称"><el-input v-model="queryParams.sourceName" clearable @keyup.enter.native="getList" /></el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" @click="getList">查询</el-button><el-button @click="resetQuery">重置</el-button></el-form-item>
        </el-form>
        <el-row :gutter="10" class="mb8">
          <el-col :span="1.5"><el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd" v-hasPermi="['business:knowledge:add']">登记资料</el-button></el-col>
        </el-row>
        <el-table v-loading="loading" :data="sourceList">
          <el-table-column label="编码" prop="sourceCode" width="130" />
          <el-table-column label="资料名称" prop="sourceName" min-width="220" show-overflow-tooltip />
          <el-table-column label="知识分类" prop="sourceType" width="120"><template slot-scope="s"><el-tag :type="sourceTypeTag(s.row.sourceType)" size="mini">{{ sourceTypeLabel(s.row.sourceType) }}</el-tag></template></el-table-column>
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
          <el-form-item label="知识分类"><el-select v-model="searchForm.sourceType" clearable><el-option v-for="t in sourceTypes" :key="t.value" :label="t.label" :value="t.value" /></el-select></el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-search" :loading="searching" @click="doSearch">检索</el-button></el-form-item>
        </el-form>
        <el-empty v-if="searched && !searchResults.length" description="没有命中当前有效版本" />
        <el-card v-for="(item,index) in searchResults" :key="item.id" class="result-card" shadow="hover">
          <div slot="header" class="result-head"><span><b>[S{{ index+1 }}]</b> {{ item.sourceName }}</span><el-tag :type="sourceTypeTag(item.sourceType)" size="mini">{{ sourceTypeLabel(item.sourceType) }}</el-tag></div>
          <p class="snippet">{{ item.sourceSnippet || item.content }}</p>
          <div class="source-meta">
            <span>版本：{{ item.versionNo }}</span><span v-if="item.originalName">原始文件：{{ item.originalName }}</span><span v-if="item.pageStart">PDF 第 {{ item.pageStart }} 页</span>
            <span v-if="item.metricId">指标：{{ item.metricId }}</span><a v-if="item.sourceUrl" :href="item.sourceUrl" target="_blank" rel="noopener noreferrer">查看来源原文</a>
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
            <el-select v-model="qaForm.sourceType" clearable placeholder="留空：综合报告、新闻、政策和PDF"><el-option v-for="t in sourceTypes" :key="t.value" :label="t.label" :value="t.value" /></el-select>
            <el-checkbox v-model="qaForm.includeNews" :disabled="!!qaForm.sourceType" style="margin-left:16px">结合新闻/政策解释</el-checkbox>
            <el-button type="primary" icon="el-icon-chat-dot-round" :loading="asking" style="margin-left:12px" @click="doAsk">提问</el-button>
            <el-button icon="el-icon-setting" @click="openLlmConfig" v-hasPermi="['business:knowledge:edit']">LLM 配置</el-button>
          </el-form-item>
        </el-form>
        <el-collapse class="qa-history">
          <el-collapse-item title="最近问答记录（服务重启后仍可恢复）" name="history">
            <el-button size="mini" icon="el-icon-refresh" :loading="qaHistoryLoading" @click="loadQaHistory">刷新</el-button>
            <el-table :data="qaHistory" size="mini" style="margin-top:10px">
              <el-table-column label="问题" prop="question" min-width="260" show-overflow-tooltip />
              <el-table-column label="状态" prop="status" width="100" />
              <el-table-column label="进度" prop="progress" width="80"><template slot-scope="s">{{ s.row.progress }}%</template></el-table-column>
              <el-table-column label="创建时间" prop="createdAt" width="170" />
              <el-table-column label="操作" width="90"><template slot-scope="s"><el-button type="text" size="mini" @click="restoreQaTask(s.row)">查看</el-button></template></el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
        <el-card v-if="asking && qaTask" shadow="never" class="qa-progress-card">
          <div class="qa-progress-head"><b>{{ qaTask.currentStage }}</b><span>{{ qaTask.progress || 0 }}%</span></div>
          <el-progress :percentage="qaTask.progress || 0" :status="qaTask.status==='FAILED' ? 'exception' : undefined" />
          <el-collapse v-model="qaRunningPanels" class="trace-collapse">
            <el-collapse-item title="查看实时任务拆解与检索动作" name="running-trace">
              <el-alert v-if="qaTask.queryPlan && qaTask.queryPlan.length" :title="qaTask.queryPlan[0].action" type="info" :closable="false" class="mb16" />
              <el-steps direction="vertical" :active="runningActiveStep" finish-status="success" process-status="process">
                <el-step v-for="step in (qaTask.queryPlan || [])" :key="`running-${step.order}`" :title="step.name" :description="`${step.action} · ${step.input || ''}`" />
              </el-steps>
              <el-table v-if="qaTask.retrievalLogs && qaTask.retrievalLogs.length" :data="qaTask.retrievalLogs" size="mini">
                <el-table-column label="状态" prop="status" width="100" /><el-table-column label="动作" prop="action" width="150" />
                <el-table-column label="输入/过滤" prop="input" min-width="200" /><el-table-column label="结果" prop="result" min-width="180" />
              </el-table>
            </el-collapse-item>
          </el-collapse>
        </el-card>
        <el-card v-if="qaResult" shadow="never" class="answer-card">
          <div slot="header" class="answer-header">
            <span><b>知识库回答</b><el-tag size="mini" :type="answerModeType(qaResult.answerMode)" class="answer-mode">{{ answerModeText(qaResult.answerMode) }}</el-tag></span>
            <span class="model-name">{{ qaResult.model }}</span>
          </div>
          <el-alert v-for="(warning,index) in (qaResult.warnings || [])" :key="`warning-${index}`" :title="warning" type="warning" :closable="false" class="mb16" />
          <div v-if="qaResult.sourceBreakdown" class="source-breakdown">
            <span>本次证据：</span>
            <el-tag size="mini" type="success">事实结论引用覆盖率 {{ qaResult.citationCoveragePercent }}%</el-tag>
            <el-tag size="mini">分析报告 {{ qaResult.sourceBreakdown.REPORT || 0 }}</el-tag>
            <el-tag size="mini" type="warning">新闻 {{ qaResult.sourceBreakdown.NEWS || 0 }}</el-tag>
            <el-tag size="mini" type="success">政策 {{ qaResult.sourceBreakdown.POLICY || 0 }}</el-tag>
            <el-tag size="mini" type="info">PDF {{ qaResult.sourceBreakdown.PDF || 0 }}</el-tag>
          </div>
          <el-collapse v-model="qaTracePanels" class="trace-collapse">
            <el-collapse-item name="final-trace">
              <template slot="title"><i class="el-icon-connection trace-title-icon" />思考链路及参考内容</template>
              <el-steps :active="(qaResult.queryPlan || []).length" finish-status="success" simple>
                <el-step v-for="step in qaResult.queryPlan" :key="step.order" :title="step.name" :description="`${step.action} · ${step.input || ''}`" />
              </el-steps>
              <el-table v-if="qaResult.retrievalLogs" :data="qaResult.retrievalLogs" size="mini" class="log-table">
                <el-table-column label="状态" prop="status" width="100"><template slot-scope="s"><el-tag size="mini" :type="logStatusType(s.row.status)">{{ s.row.status }}</el-tag></template></el-table-column>
                <el-table-column label="动作" prop="action" width="150" /><el-table-column label="输入/过滤" prop="input" min-width="220" />
                <el-table-column label="结果" prop="result" min-width="160" />
                <el-table-column label="耗时" width="90"><template slot-scope="s">{{ s.row.durationMs }} ms</template></el-table-column>
              </el-table>
            </el-collapse-item>
          </el-collapse>
          <template v-if="qaResult.analysisTrace && qaResult.analysisTrace.length">
            <el-divider content-position="left">直白分析过程（可审计）</el-divider>
            <el-alert title="这里展示的是可核验的任务拆解、执行动作和证据判断，不展示不可验证的模型内部隐性思维。" type="info" :closable="false" class="mb16" />
            <el-timeline class="analysis-timeline">
              <el-timeline-item v-for="step in qaResult.analysisTrace" :key="`analysis-${step.order}`" :timestamp="`步骤 ${step.order}`" placement="top" type="primary">
                <el-card shadow="never" class="analysis-step-card">
                  <div class="analysis-step-title">{{ step.title }}</div>
                  <div><b>我做了什么：</b>{{ step.action }}</div>
                  <div><b>得到什么：</b>{{ step.result }}</div>
                  <div class="analysis-boundary"><b>结论边界：</b>{{ step.boundary }}</div>
                  <div v-if="step.evidences && step.evidences.length" class="analysis-evidences">
                    <span>对应证据：</span>
                    <el-button v-for="evidence in step.evidences" :key="`${step.order}-${evidence.citationLabel}-${evidence.chunkId}`" type="text" size="mini" @click="openEvidence(evidence)">
                      [{{ evidence.citationLabel }}] {{ evidence.sourceName }}<span v-if="evidence.pageStart"> · 第{{ evidence.pageStart }}页</span>
                    </el-button>
                  </div>
                </el-card>
              </el-timeline-item>
            </el-timeline>
          </template>
          <div class="answer-text">
            <template v-for="segment in qaAnswerSegments">
              <span v-if="!segment.citation" :key="segment.key">{{ segment.text }}</span>
              <el-popover v-else :key="segment.key" placement="top-start" width="430" trigger="hover">
                <div class="inline-source-title"><i :class="sourceIcon(segment.citation.sourceType)" /> {{ segment.citation.sourceName }} · {{ segment.citation.versionNo }}</div>
                <div class="citation-snippet">{{ citationPreview(segment.citation) }}</div>
                <a v-if="segment.citation.sourceUrl" :href="segment.citation.sourceUrl" target="_blank" rel="noopener noreferrer">打开来源原文</a>
                <sup slot="reference" class="inline-citation" @click.stop="openCitation(segment.citation)">[{{ segment.citation.citationLabel }}]</sup>
              </el-popover>
            </template>
          </div>
          <template v-if="qaResult.claims && qaResult.claims.length">
            <el-divider content-position="left">逐句证据核验</el-divider>
            <div v-for="(claim, claimIndex) in qaResult.claims" :key="`claim-${claimIndex}`" class="claim-row">
              <div><el-tag type="success" size="mini">已核验</el-tag> {{ claim.claimText }}</div>
              <div v-for="(evidence, evidenceIndex) in claim.evidences" :key="`evidence-${claimIndex}-${evidenceIndex}`" class="claim-evidence">
                <b>[{{ evidence.citationLabel }}]</b>
                {{ evidence.sourceName }}<span v-if="evidence.originalName"> · {{ evidence.originalName }}</span><span v-if="evidence.pageStart"> · PDF 第 {{ evidence.pageStart }} 页</span>
                · 匹配度 {{ Math.round(evidence.matchScore * 100) }}%
                <div class="citation-snippet">原文：{{ evidence.evidenceSnippet }}</div>
                <el-button type="text" size="mini" @click="openEvidence(evidence)">精确定位原文</el-button>
              </div>
            </div>
          </template>
          <el-divider content-position="left">引用来源</el-divider>
          <div v-for="item in qaResult.citations" :key="item.id" class="citation-row">
            <i :class="sourceIcon(item.sourceType)" /><b>[{{ item.citationLabel }}]</b> {{ item.sourceName }} · {{ item.versionNo }}
            <span v-if="item.originalName"> · {{ item.originalName }}</span>
            <span v-if="item.pageStart"> · PDF 第 {{ item.pageStart }} 页</span>
            <span v-if="item.metricId"> · 指标 {{ item.metricId }}</span>
            <a v-if="item.sourceUrl" :href="item.sourceUrl" target="_blank" rel="noopener noreferrer"> · 原文链接</a>
            <div class="citation-snippet">{{ item.sourceSnippet }}</div>
            <el-button type="text" size="mini" @click="openCitation(item)">点击定位引用段落</el-button>
          </div>
          <template v-if="qaResult.graph && qaResult.graph.nodes && qaResult.graph.nodes.length">
            <el-divider content-position="left">本次回答关联图谱</el-divider>
            <div ref="qaGraph" class="qa-graph" />
          </template>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="多源知识图谱" name="graph">
        <el-form :inline="true" size="small" @submit.native.prevent>
          <el-form-item label="时间"><el-input v-model="graphFilter.period" clearable placeholder="如 2023 Q3" /></el-form-item>
          <el-form-item label="数据类型"><el-select v-model="graphFilter.dataType" clearable placeholder="全部来源"><el-option label="财报" value="FINANCIAL" /><el-option label="新闻" value="NEWS" /><el-option label="政策" value="POLICY" /><el-option label="结构化分析报告" value="REPORT" /><el-option label="PDF资料" value="PDF" /></el-select></el-form-item>
          <el-form-item><el-button type="primary" icon="el-icon-share" :loading="graphLoading" @click="loadGraph()">查询图谱</el-button><el-button v-if="graphCenterId" @click="resetGraph">返回全图</el-button><el-button icon="el-icon-refresh" :loading="graphRebuilding" @click="rebuildGraph" v-hasPermi="['business:knowledge:edit']">重建现有资料图谱</el-button></el-form-item>
        </el-form>
        <el-alert title="点击实体节点可下钻展开直接关系；点击连线可查看来源和原文证据。" type="info" :closable="false" class="mb16" />
        <el-empty v-if="graphLoaded && (!graphData.nodes || !graphData.nodes.length)" description="当前筛选条件下暂无图谱关系，请先入库包含实体的资料" />
        <div v-show="graphData.nodes && graphData.nodes.length" ref="graphChart" class="graph-chart" />
        <el-card v-if="selectedRelation" shadow="never" class="relation-card">
          <b>{{ selectedRelation.name }}</b> · {{ selectedRelation.sourceName }} · {{ selectedRelation.versionNo }}
          <el-tag v-if="selectedRelation.mentionCount > 1" size="mini">{{ selectedRelation.mentionCount }} 条证据 / {{ selectedRelation.sourceCount }} 个来源</el-tag>
          <span v-if="selectedRelation.pageStart"> · PDF 第 {{ selectedRelation.pageStart }} 页</span>
          <div class="citation-snippet">{{ selectedRelation.evidenceSnippet }}</div>
          <el-button type="text" @click="openEvidence(selectedRelation)">定位源文档段落</el-button>
          <a v-if="selectedRelation.sourceUrl" :href="selectedRelation.sourceUrl" target="_blank" rel="noopener noreferrer">打开来源原文</a>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog :title="form.id ? '编辑固定资料' : '登记固定资料'" :visible.sync="editOpen" width="620px">
      <el-form ref="form" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="资料编码" prop="sourceCode"><el-input v-model="form.sourceCode" placeholder="例如 POC-PDF-001" /></el-form-item>
        <el-form-item label="资料名称" prop="sourceName"><el-input v-model="form.sourceName" /></el-form-item>
        <el-form-item label="知识分类" prop="sourceType"><el-radio-group v-model="form.sourceType"><el-radio-button v-for="t in sourceTypes" :key="t.value" :label="t.value">{{ t.label }}</el-radio-button></el-radio-group></el-form-item>
        <el-form-item label="密级"><el-select v-model="form.confidentiality"><el-option label="内部" value="INTERNAL" /><el-option label="公开" value="PUBLIC" /><el-option label="受限" value="RESTRICTED" /></el-select></el-form-item>
        <el-form-item label="允许使用范围" prop="allowedPurpose"><el-input v-model="form.allowedPurpose" type="textarea" placeholder="例如：仅限POC问答和内部分析，不允许外发" /></el-form-item>
        <el-form-item label="允许角色ID"><el-input v-model="form.allowedRoleIds" placeholder="逗号分隔；留空则继承菜单权限" /></el-form-item>
        <el-form-item label="是否启用"><el-switch v-model="form.enabled" active-value="1" inactive-value="0" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="editOpen=false">取消</el-button><el-button type="primary" @click="submitForm">保存</el-button></div>
    </el-dialog>

    <el-dialog title="资料入库" :visible.sync="ingestOpen" width="620px" :close-on-click-modal="false">
      <el-alert v-if="ingestSource" :title="`${ingestSource.sourceName}（${sourceTypeLabel(ingestSource.sourceType)}）`" type="info" :closable="false" class="mb16" />
      <el-form label-width="100px">
        <el-form-item label="版本号"><el-input v-model="ingestForm.versionNo" placeholder="留空自动生成时间版本" /></el-form-item>
        <template v-if="ingestSource && ingestSource.sourceType==='PDF'">
          <el-form-item label="PDF文件"><el-upload action="#" :auto-upload="false" :limit="1" accept=".pdf,application/pdf" :on-change="onPdfChange" :on-remove="onPdfRemove"><el-button icon="el-icon-document-add">选择PDF</el-button></el-upload></el-form-item>
        </template>
        <template v-else-if="ingestSource && ingestSource.sourceType==='NEWS'">
          <el-form-item label="批量JSON"><el-upload action="#" :auto-upload="false" :limit="1" accept=".json,application/json" :on-change="onNewsJsonChange" :on-remove="onNewsJsonRemove"><el-button icon="el-icon-document-add">选择爬虫JSON</el-button><div slot="tip" class="el-upload__tip">选择JSON时优先批量入库；不选择时可在下方逐条录入</div></el-upload></el-form-item>
          <el-form-item label="新闻URL"><el-input v-model="ingestForm.url" placeholder="原始新闻URL，用于来源追溯" /></el-form-item>
          <el-form-item label="新闻标题"><el-input v-model="ingestForm.title" /></el-form-item>
          <el-form-item label="新闻正文"><el-input v-model="ingestForm.content" type="textarea" :rows="8" placeholder="粘贴新闻正文；留空时仅允许抓取后端白名单域名" /></el-form-item>
        </template>
        <template v-else-if="ingestSource && ingestSource.sourceType==='POLICY'">
          <el-form-item label="政策名称"><el-input v-model="ingestForm.title" placeholder="政策或通知的完整名称" /></el-form-item>
          <el-form-item label="发布机关"><el-input v-model="ingestForm.issuedBy" placeholder="例如：国务院、工信部" /></el-form-item>
          <el-form-item label="发布时间"><el-input v-model="ingestForm.publishedAt" placeholder="例如：2025-01-15" /></el-form-item>
          <el-form-item label="政策级别"><el-select v-model="ingestForm.policyLevel" clearable placeholder="请选择"><el-option label="国家" value="国家" /><el-option label="地方" value="地方" /><el-option label="行业" value="行业" /></el-select></el-form-item>
          <el-form-item label="原文URL"><el-input v-model="ingestForm.url" placeholder="政策原文URL，用于来源追溯" /></el-form-item>
          <el-form-item label="政策正文"><el-input v-model="ingestForm.content" type="textarea" :rows="8" placeholder="粘贴政策原文或与分析相关的完整条款" /></el-form-item>
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
      <el-table :data="versions"><el-table-column label="版本" prop="versionNo" /><el-table-column label="原始文件/标题" prop="originalName" min-width="220" /><el-table-column label="页数" prop="pageCount" width="70" /><el-table-column label="切片" prop="chunkCount" width="70" /><el-table-column label="状态" width="90"><template slot-scope="s">{{ statusText(s.row.status) }}</template></el-table-column><el-table-column label="解析提示" prop="errorMessage" min-width="220" show-overflow-tooltip /><el-table-column label="创建时间" prop="createTime" width="170" /></el-table>
    </el-dialog>

    <el-dialog title="源文档精确定位" :visible.sync="evidenceOpen" width="760px">
      <div v-if="evidenceDetail">
        <div class="source-meta"><span>{{ evidenceDetail.sourceName }}</span><span>{{ evidenceDetail.originalName }}</span><span>版本 {{ evidenceDetail.versionNo }}</span><span v-if="evidenceDetail.pageStart">PDF 第 {{ evidenceDetail.pageStart }} 页</span></div>
        <pre class="evidence-content"><span>{{ evidenceBefore }}</span><mark>{{ evidenceDetail.highlightedText }}</mark><span>{{ evidenceAfter }}</span></pre>
        <el-button v-if="evidenceDetail.fileAvailable" type="primary" size="small" icon="el-icon-document" @click="openSourcePdf">在原 PDF 对应页查看</el-button>
        <a v-if="evidenceDetail.sourceUrl" :href="evidenceDetail.sourceUrl" target="_blank" rel="noopener noreferrer">打开新闻原文</a>
      </div>
    </el-dialog>

    <el-dialog title="知识问答 LLM 配置" :visible.sync="llmConfigOpen" width="620px">
      <el-alert title="此配置同时用于 Excel 表头识别、报告生成、文本实体抽取和知识问答。保存后持久化到 MySQL，服务重启仍然生效；API Key 加密保存且永不回显。" type="success" :closable="false" class="mb16" />
      <el-form label-width="110px" size="small">
        <el-form-item label="请求地址"><el-input v-model="llmConfigForm.apiUrl" placeholder="https://ark.cn-beijing.volces.com/api/v3/chat/completions" /></el-form-item>
        <el-form-item label="模型名称"><el-input v-model="llmConfigForm.model" placeholder="glm-5-2-260617" /></el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="llmConfigForm.apiKey" type="password" show-password autocomplete="new-password" :placeholder="llmConfigForm.apiKeyConfigured ? '已配置；留空表示保持不变' : '请输入API Key'" />
        </el-form-item>
        <el-form-item label="当前状态"><el-tag :type="llmConfigForm.apiKeyConfigured ? 'success' : 'warning'">{{ llmConfigForm.apiKeyConfigured ? 'API Key 已配置' : 'API Key 未配置' }}</el-tag></el-form-item>
        <el-form-item><el-checkbox v-model="llmConfigForm.clearApiKey">清除当前 API Key</el-checkbox></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="llmConfigOpen=false">取消</el-button><el-button :loading="llmTesting" @click="testLlm">测试连接</el-button><el-button type="primary" :loading="llmSaving" @click="saveLlmConfig">保存</el-button></div>
    </el-dialog>
  </div>
</template>

<script>
import { listKnowledgeBase, getKnowledgeBase, addKnowledgeBase, updateKnowledgeBase, delKnowledgeBase,
  ingestPdf, ingestNews, ingestPolicy, ingestNewsJson, ingestReport, getKnowledgeTask, listKnowledgeVersions, searchKnowledge,
  submitKnowledgeQaTask, getKnowledgeQaTask, listKnowledgeQaTasks,
  getKnowledgeGraph, getKnowledgeEvidence, getKnowledgeEvidenceFile, rebuildKnowledgeGraph, getKnowledgeLlmConfig, updateKnowledgeLlmConfig,
  testKnowledgeLlmConfig } from '@/api/business/knowledge/knowledgeBase'
import * as echarts from 'echarts'
import { blobValidate } from '@/utils/ruoyi'

export default {
  name: 'KnowledgeBase',
  data() {
    return {
      activeTab: 'sources', sourceTypes: [
        { value: 'NEWS', label: '新闻' },
        { value: 'REPORT', label: '生成报告' },
        { value: 'PDF', label: 'PDF文档' },
        { value: 'POLICY', label: '政策' }
      ], loading: false, total: 0, sourceList: [],
      queryParams: { pageNum: 1, pageSize: 10, sourceName: undefined, sourceType: undefined },
      editOpen: false, form: {}, rules: { sourceCode: [{ required: true, message: '资料编码不能为空', trigger: 'blur' }], sourceName: [{ required: true, message: '资料名称不能为空', trigger: 'blur' }], sourceType: [{ required: true, message: '请选择类型', trigger: 'change' }], allowedPurpose: [{ required: true, message: '请填写允许使用范围', trigger: 'blur' }] },
      ingestOpen: false, ingestSource: null, ingestForm: {}, pdfFile: null, newsJsonFile: null, submitting: false, currentTask: null, poller: null,
      versionOpen: false, versions: [], searchForm: { q: '', sourceType: '' }, searching: false, searched: false, searchResults: [],
      qaForm: { question: '', sourceType: '', includeNews: true }, asking: false, qaResult: null,
      qaTask: null, qaPoller: null, qaRunningPanels: [], qaTracePanels: [],
      qaHistory: [], qaHistoryLoading: false,
      graphFilter: { period: '', dataType: '' }, graphLoading: false, graphLoaded: false, graphRebuilding: false,
      graphData: { nodes: [], links: [], categories: [] }, graphCenterId: null, selectedRelation: null,
      graphChartInstance: null, qaGraphInstance: null, evidenceOpen: false, evidenceDetail: null,
      llmConfigOpen: false,
      llmConfigForm: { apiUrl: '', model: '', apiKey: '', apiKeyConfigured: false, clearApiKey: false },
      llmSaving: false, llmTesting: false
    }
  },
  created() { this.getList() },
  beforeDestroy() { this.stopPolling(); this.stopQaPolling(); if (this.graphChartInstance) this.graphChartInstance.dispose(); if (this.qaGraphInstance) this.qaGraphInstance.dispose() },
  computed: {
    evidenceBefore() { if (!this.evidenceDetail) return ''; return this.evidenceDetail.content.slice(0, this.evidenceDetail.startOffset) },
    evidenceAfter() { if (!this.evidenceDetail) return ''; return this.evidenceDetail.content.slice(this.evidenceDetail.endOffset) },
    runningActiveStep() {
      if (!this.qaTask || !this.qaTask.queryPlan) return 0
      if ((this.qaTask.progress || 0) >= 90) return this.qaTask.queryPlan.length
      if ((this.qaTask.progress || 0) >= 35) return Math.max(1, this.qaTask.queryPlan.length - 1)
      if ((this.qaTask.progress || 0) >= 30) return Math.min(4, this.qaTask.queryPlan.length)
      if ((this.qaTask.progress || 0) >= 16) return Math.min(3, this.qaTask.queryPlan.length)
      if ((this.qaTask.progress || 0) >= 10) return Math.min(2, this.qaTask.queryPlan.length)
      return Math.min(1, this.qaTask.queryPlan.length)
    },
    qaAnswerSegments() {
      const answer = (this.qaResult && this.qaResult.answer) || ''
      const citations = ((this.qaResult && this.qaResult.citations) || []).reduce((map, item) => { map[item.citationLabel] = item; return map }, {})
      const evidenceQueues = {}
      ;((this.qaResult && this.qaResult.claims) || []).forEach(claim => {
        ;(claim.evidences || []).forEach(evidence => {
          if (!evidenceQueues[evidence.citationLabel]) evidenceQueues[evidence.citationLabel] = []
          evidenceQueues[evidence.citationLabel].push(evidence)
        })
      })
      const evidenceCursors = {}
      const result = []; const pattern = /\[S(\d+)]/g; let start = 0; let match; let key = 0
      while ((match = pattern.exec(answer)) !== null) {
        if (match.index > start) result.push({ key: `text-${key++}`, text: answer.slice(start, match.index) })
        const label = `S${match[1]}`
        const queue = evidenceQueues[label] || []; const cursor = evidenceCursors[label] || 0
        const exactEvidence = queue[Math.min(cursor, Math.max(0, queue.length - 1))]
        evidenceCursors[label] = cursor + 1
        const citation = citations[label] && exactEvidence
          ? { ...citations[label], ...exactEvidence, evidenceLocations: [exactEvidence] }
          : citations[label]
        result.push({ key: `citation-${key++}`, text: match[0], citation })
        start = pattern.lastIndex
      }
      if (start < answer.length) result.push({ key: `text-${key++}`, text: answer.slice(start) })
      return result
    }
  },
  methods: {
    getList() { this.loading = true; listKnowledgeBase(this.queryParams).then(r => { this.sourceList = r.rows; this.total = r.total }).finally(() => { this.loading = false }) },
    handleCategoryChange() { this.queryParams.pageNum = 1; this.getList() },
    resetQuery() { this.queryParams = { pageNum: 1, pageSize: 10, sourceName: undefined, sourceType: undefined }; this.getList() },
    resetFormData() { this.form = { id: undefined, sourceCode: '', sourceName: '', sourceType: 'PDF', confidentiality: 'INTERNAL', allowedPurpose: '', allowedRoleIds: '', enabled: '1', status: '0', remark: '' } },
    handleAdd() { this.resetFormData(); this.editOpen = true },
    handleUpdate(row) { getKnowledgeBase(row.id).then(r => { this.form = r.data; this.editOpen = true }) },
    submitForm() { this.$refs.form.validate(valid => { if (!valid) return; const action = this.form.id ? updateKnowledgeBase : addKnowledgeBase; action(this.form).then(() => { this.$modal.msgSuccess('保存成功'); this.editOpen = false; this.getList() }) }) },
    handleDelete(row) { this.$modal.confirm(`确认删除“${row.sourceName}”吗？`).then(() => delKnowledgeBase(row.id)).then(() => { this.$modal.msgSuccess('删除成功'); this.getList() }).catch(() => {}) },
    openIngest(row) { this.stopPolling(); this.ingestSource = row; this.ingestForm = { versionNo: '', url: '', title: '', content: '', issuedBy: '', publishedAt: '', policyLevel: '', reportId: undefined }; this.pdfFile = null; this.newsJsonFile = null; this.currentTask = null; this.ingestOpen = true },
    closeIngest() { this.ingestOpen = false; if (!this.currentTask || !['0','1'].includes(this.currentTask.status)) this.stopPolling() },
    onPdfChange(file) { this.pdfFile = file.raw }, onPdfRemove() { this.pdfFile = null },
    onNewsJsonChange(file) { this.newsJsonFile = file.raw }, onNewsJsonRemove() { this.newsJsonFile = null },
    submitIngest() {
      if (!this.ingestSource) return
      let request
      if (this.ingestSource.sourceType === 'PDF') { if (!this.pdfFile) return this.$modal.msgError('请选择PDF文件'); request = ingestPdf(this.ingestSource.id, this.ingestForm.versionNo, this.pdfFile) }
      else if (this.ingestSource.sourceType === 'NEWS') {
        if (this.newsJsonFile) request = ingestNewsJson(this.ingestSource.id, this.ingestForm.versionNo, this.newsJsonFile)
        else { if (!this.ingestForm.url || !this.ingestForm.title) return this.$modal.msgError('请选择新闻JSON，或填写新闻URL和标题'); request = ingestNews({ sourceId: this.ingestSource.id, ...this.ingestForm }) }
      }
      else if (this.ingestSource.sourceType === 'POLICY') {
        if (!this.ingestForm.url || !this.ingestForm.title || !this.ingestForm.content || this.ingestForm.content.trim().length < 20) return this.$modal.msgError('请填写政策名称、原文URL及至少20字的政策正文')
        request = ingestPolicy({ sourceId: this.ingestSource.id, ...this.ingestForm })
      }
      else { if (!this.ingestForm.reportId) return this.$modal.msgError('请输入报告ID'); request = ingestReport({ sourceId: this.ingestSource.id, ...this.ingestForm }) }
      this.submitting = true
      request.then(r => { this.currentTask = r.data; this.startPolling(r.data.id); this.$modal.msgSuccess('入库任务已提交') }).finally(() => { this.submitting = false })
    },
    startPolling(id) { this.stopPolling(); const tick = () => getKnowledgeTask(id).then(r => { this.currentTask = r.data; if (['2','3'].includes(r.data.status)) { this.stopPolling(); this.getList() } }); tick(); this.poller = setInterval(tick, 2000) },
    stopPolling() { if (this.poller) clearInterval(this.poller); this.poller = null },
    showVersions(row) { listKnowledgeVersions(row.id).then(r => { this.versions = r.data || []; this.versionOpen = true }) },
    doSearch() { if (!this.searchForm.q || this.searchForm.q.trim().length < 2) return this.$modal.msgError('检索词至少2个字符'); this.searching = true; this.searched = true; searchKnowledge({ ...this.searchForm, limit: 20 }).then(r => { this.searchResults = r.data || [] }).finally(() => { this.searching = false }) },
    doAsk() {
      if (!this.qaForm.question || this.qaForm.question.trim().length < 2) return this.$modal.msgError('问题至少2个字符')
      this.stopQaPolling(); this.asking = true; this.qaResult = null; this.qaTask = null; this.qaTracePanels = []
      submitKnowledgeQaTask(this.qaForm).then(r => { this.qaTask = r.data; this.startQaPolling(r.data.taskId) }).catch(() => { this.asking = false })
    },
    startQaPolling(taskId) {
      const tick = () => {
        getKnowledgeQaTask(taskId).then(r => {
          this.qaTask = r.data
          if (r.data.status === 'SUCCESS') {
            this.qaResult = r.data.result; this.asking = false; this.stopQaPolling()
            this.$nextTick(() => this.renderGraph('qaGraph', this.qaResult.graph, 'qaGraphInstance'))
          } else if (r.data.status === 'FAILED') {
            this.asking = false; this.stopQaPolling(); this.$modal.msgError(r.data.errorMessage || '知识问答处理失败')
          } else this.qaPoller = setTimeout(tick, 1200)
        }).catch(() => { this.asking = false; this.stopQaPolling() })
      }
      tick()
    },
    stopQaPolling() { if (this.qaPoller) clearTimeout(this.qaPoller); this.qaPoller = null },
    openLlmConfig() { getKnowledgeLlmConfig().then(r => { this.llmConfigForm = { ...r.data, apiKey: '', clearApiKey: false }; this.llmConfigOpen = true }) },
    saveLlmConfig() {
      if (!this.llmConfigForm.apiUrl || !this.llmConfigForm.model) return this.$modal.msgError('请求地址和模型名称不能为空')
      this.llmSaving = true
      updateKnowledgeLlmConfig(this.llmConfigForm).then(r => { this.llmConfigForm = { ...r.data, apiKey: '', clearApiKey: false }; this.$modal.msgSuccess('LLM配置已保存，服务重启后仍然生效') }).finally(() => { this.llmSaving = false })
    },
    testLlm() { this.llmTesting = true; testKnowledgeLlmConfig().then(r => this.$modal.msgSuccess(`连接成功，耗时 ${r.data.durationMs} ms`)).finally(() => { this.llmTesting = false }) },
    onTabClick(tab) { if (tab.name === 'graph' && !this.graphLoaded) this.loadGraph(); if (tab.name === 'qa') this.loadQaHistory() },
    loadQaHistory() { this.qaHistoryLoading = true; listKnowledgeQaTasks({ limit: 30 }).then(r => { this.qaHistory = r.data || [] }).finally(() => { this.qaHistoryLoading = false }) },
    restoreQaTask(row) {
      getKnowledgeQaTask(row.taskId).then(r => {
        this.qaTask = r.data
        if (r.data.status === 'SUCCESS') { this.qaResult = r.data.result; this.asking = false; this.$nextTick(() => this.renderGraph('qaGraph', this.qaResult && this.qaResult.graph, 'qaGraphInstance')) }
        else if (r.data.status === 'QUEUED' || r.data.status === 'RUNNING') { this.asking = true; this.startQaPolling(row.taskId) }
        else { this.qaResult = null; this.asking = false; this.$modal.msgWarning(r.data.errorMessage || '该任务执行失败') }
      })
    },
    loadGraph(centerId) { this.graphLoading = true; this.graphCenterId = centerId || null; this.selectedRelation = null; getKnowledgeGraph({ ...this.graphFilter, centerId: this.graphCenterId, limit: 300 }).then(r => { this.graphData = r.data || { nodes: [], links: [], categories: [] }; this.graphLoaded = true; this.$nextTick(() => this.renderGraph('graphChart', this.graphData, 'graphChartInstance')) }).finally(() => { this.graphLoading = false }) },
    resetGraph() { this.graphCenterId = null; this.loadGraph() },
    rebuildGraph() { this.graphRebuilding = true; rebuildKnowledgeGraph().then(r => { this.$modal.msgSuccess(`已处理 ${r.data.chunkCount} 个切片`); this.resetGraph() }).finally(() => { this.graphRebuilding = false }) },
    renderGraph(refName, data, instanceName) {
      const element = this.$refs[refName]; if (!element || !data || !data.nodes || !data.nodes.length) return
      if (this[instanceName]) this[instanceName].dispose()
      const chart = echarts.init(element); this[instanceName] = chart
      chart.setOption({ tooltip: { formatter: p => p.dataType === 'edge' ? `${p.data.name}<br/>${p.data.sourceName || ''}` : `${p.data.name}<br/>${p.data.entityType}` }, legend: [{ bottom: 8, data: (data.categories || []).map(c => c.name) }], series: [{ type: 'graph', layout: 'force', roam: true, draggable: true, categories: data.categories || [], data: data.nodes, links: data.links, label: { show: true, position: 'right' }, edgeLabel: { show: true, formatter: p => p.data.name, fontSize: 10 }, edgeSymbol: ['none', 'arrow'], force: { repulsion: 260, edgeLength: [90, 170], gravity: 0.08 }, lineStyle: { color: 'source', curveness: 0.12, opacity: 0.75 } }] })
      chart.on('click', params => { if (params.dataType === 'node' && refName === 'graphChart') this.loadGraph(Number(params.data.id)); if (params.dataType === 'edge') this.selectedRelation = params.data })
    },
    openEvidence(item) { const chunkId = item.chunkId || item.id; if (!chunkId) return this.$modal.msgError('该来源缺少切片定位信息'); getKnowledgeEvidence(chunkId, { startOffset: item.startOffset, endOffset: item.endOffset }).then(r => { this.evidenceDetail = r.data; this.evidenceOpen = true }) },
    openCitation(item) { const evidence = item.evidenceLocations && item.evidenceLocations.length ? item.evidenceLocations[0] : item; this.openEvidence({ id: item.id, chunkId: evidence.chunkId || item.chunkId || item.id, startOffset: evidence.startOffset, endOffset: evidence.endOffset }) },
    openSourcePdf() {
      if (!this.evidenceDetail || !this.evidenceDetail.chunkId) return
      const viewer = window.open('about:blank', '_blank')
      if (viewer) viewer.opener = null
      getKnowledgeEvidenceFile(this.evidenceDetail.chunkId).then(blob => {
        if (!blobValidate(blob) || ((blob.type || '').toLowerCase().includes('json'))) {
          return blob.text().then(text => {
            let message = 'PDF原件打开失败'
            try { message = JSON.parse(text).msg || message } catch (e) { /* 保留通用提示 */ }
            if (viewer) viewer.close()
            this.$modal.msgError(message)
          })
        }
        const url = URL.createObjectURL(blob)
        const page = this.evidenceDetail.pageStart || 1
        const search = encodeURIComponent((this.evidenceDetail.highlightedText || '').replace(/\s+/g, ' ').slice(0, 120))
        const target = `${url}#page=${page}${search ? `&search=${search}` : ''}`
        if (viewer) viewer.location.href = target
        else window.open(target, '_blank', 'noopener')
        setTimeout(() => URL.revokeObjectURL(url), 60000)
      }).catch(() => { if (viewer) viewer.close() })
    },
    citationPreview(item) { const evidence = item.evidenceLocations && item.evidenceLocations.length ? item.evidenceLocations[0] : null; return (evidence && evidence.evidenceSnippet) || item.sourceSnippet || '暂无摘要' },
    sourceIcon(type) { return ({ NEWS: 'el-icon-news', POLICY: 'el-icon-document-checked', PDF: 'el-icon-document', REPORT: 'el-icon-data-analysis' })[type] || 'el-icon-files' },
    sourceTypeLabel(type) { const item = this.sourceTypes.find(t => t.value === type); return item ? item.label : (type || '未分类') },
    sourceTypeTag(type) { return ({ NEWS: 'success', REPORT: 'primary', PDF: 'info', POLICY: 'warning' })[type] || 'info' },
    statusText(s) { return ({ '0': '待处理', '1': '处理中', '2': '成功', '3': '失败' })[s] || s },
    statusType(s) { return ({ '0': 'info', '1': 'warning', '2': 'success', '3': 'danger' })[s] || 'info' },
    answerModeText(mode) { return ({ LLM_VERIFIED: 'LLM 已核验', LLM_REPAIRED: 'LLM 引用已修复', EXTRACTIVE_FALLBACK: '原文安全降级' })[mode] || mode || '未知模式' },
    answerModeType(mode) { return mode === 'EXTRACTIVE_FALLBACK' ? 'warning' : (mode === 'LLM_REPAIRED' ? 'primary' : 'success') },
    logStatusType(status) { return ({ SUCCESS: 'success', RETRY: 'warning', FALLBACK: 'warning' })[status] || 'info' },
    prettyEvidence(value) { try { return JSON.stringify(JSON.parse(value), null, 2) } catch (e) { return value } }
  }
}
</script>

<style scoped>
.mb16 { margin-bottom: 16px; }.danger { color:#f56c6c; }.knowledge-category-filter { display:flex; align-items:center; gap:14px; padding:14px 16px; margin-bottom:16px; background:#f7f9fc; border:1px solid #ebeef5; border-radius:6px; }.category-title { color:#303133; font-weight:600; }.source-breakdown { display:flex; align-items:center; gap:8px; margin-bottom:14px; color:#606266; }.result-card { margin-bottom:14px; }.result-head { display:flex; justify-content:space-between; align-items:center; }.snippet { line-height:1.75; white-space:pre-wrap; }.source-meta { display:flex; flex-wrap:wrap; gap:18px; color:#8492a6; font-size:13px; }.task-card { margin-top:16px; line-height:2; } pre { white-space:pre-wrap; max-height:260px; overflow:auto; }.answer-card { margin-top:18px; }.answer-header { display:flex; justify-content:space-between; align-items:center; }.answer-mode { margin-left:10px; }.answer-text { line-height:1.9; white-space:pre-wrap; margin-top:16px; }.model-name { color:#909399; font-size:12px; }.claim-row { padding:10px 0; border-bottom:1px dashed #dcdfe6; line-height:1.8; }.claim-evidence { margin:6px 0 0 26px; padding:8px 10px; background:#f7f9fc; border-left:3px solid #67c23a; }.citation-row { padding:10px 0; border-bottom:1px solid #ebeef5; line-height:1.7; }.citation-snippet { color:#606266; font-size:13px; white-space:pre-wrap; }.graph-chart { height:650px; background:#f8fafc; border:1px solid #ebeef5; border-radius:8px; }.qa-graph { height:420px; }.relation-card { margin-top:14px; }.relation-card a { margin-left:18px; }.log-table { margin-top:12px; }.evidence-content { margin-top:16px; max-height:480px; padding:16px; background:#f7f9fc; line-height:1.8; }.evidence-content mark { background:#ffe58f; color:#303133; }.qa-progress-card { margin:14px 0; }.qa-progress-head { display:flex; justify-content:space-between; margin-bottom:10px; color:#606266; }.trace-collapse { margin:12px 0 16px; }.trace-title-icon { margin-right:8px; color:#409eff; }.inline-citation { color:#409eff; cursor:pointer; font-weight:600; margin:0 2px; }.inline-citation:hover { color:#66b1ff; text-decoration:underline; }.inline-source-title { font-weight:600; margin-bottom:8px; }.inline-source-title i,.citation-row>i { margin-right:6px; color:#409eff; }
.analysis-timeline { padding:6px 8px 0 6px; }.analysis-step-card { line-height:1.75; }.analysis-step-title { font-weight:700; font-size:15px; color:#303133; margin-bottom:6px; }.analysis-boundary { color:#e6a23c; }.analysis-evidences { display:flex; flex-wrap:wrap; align-items:center; gap:8px; margin-top:6px; padding-top:6px; border-top:1px dashed #dcdfe6; }
</style>
