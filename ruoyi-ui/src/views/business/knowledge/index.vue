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
          <el-table-column label="状态" width="110"><template slot-scope="s"><el-tag :type="statusType(s.row.status)" size="mini">{{ statusText(s.row.status) }}</el-tag></template></el-table-column>
          <el-table-column label="启用" width="70"><template slot-scope="s">{{ s.row.enabled === '1' ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="操作" width="245" fixed="right">
            <template slot-scope="s">
              <el-button v-if="showIngestButton(s.row)" size="mini" type="text" icon="el-icon-upload2" @click="openIngest(s.row)">入库</el-button>
              <span v-else-if="isIngestSuccess(s.row)" class="ingest-success">入库成功</span>
              <span v-else-if="isIngestRunning(s.row)" class="ingest-running">入库中</span>
              <el-button size="mini" type="text" icon="el-icon-time" @click="showVersions(s.row)">版本</el-button>
              <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(s.row)">编辑</el-button>
              <el-button
                v-if="canDelete(s.row)"
                size="mini"
                type="text"
                class="danger"
                @click="handleDelete(s.row)"
              >删除</el-button>
              <el-tooltip v-else content="请先编辑，将「是否启用」改为否后再删除" placement="top">
                <el-button size="mini" type="text" class="danger is-disabled" disabled>删除</el-button>
              </el-tooltip>
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
            <span>版本：{{ item.versionNo }}</span><span v-if="item.originalName">原始文件：{{ item.originalName }}</span><span v-if="item.pageStart">第 {{ item.pageStart }} 页</span>
            <span v-if="item.metricId">指标：{{ item.metricId }}</span>
            <el-button v-if="['PDF', 'NEWS'].includes(item.sourceType)" class="source-link-button" type="text" size="mini" :icon="item.sourceType === 'PDF' ? 'el-icon-document' : 'el-icon-news'" @click="openEvidence(item)">查看原文</el-button>
            <a v-else-if="item.sourceUrl" :href="item.sourceUrl" target="_blank" rel="noopener noreferrer">查看原文</a>
          </div>
          <el-collapse v-if="item.evidenceJson"><el-collapse-item title="查看结构化来源证据"><pre>{{ prettyEvidence(item.evidenceJson) }}</pre></el-collapse-item></el-collapse>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="知识问答" name="qa">
        <div class="qa-chat-shell">
          <div class="qa-chat-toolbar">
            <div class="qa-chat-toolbar-left">
              <span class="qa-web-llm">
                <el-switch v-model="qaForm.allowWebSearch" />
                <span>知识库＋互联网</span>
              </span>
            </div>
            <div class="qa-chat-toolbar-right">
              <el-button type="text" size="mini" icon="el-icon-time" @click="qaHistoryDrawer = true">历史记录</el-button>
              <el-button type="text" size="mini" icon="el-icon-delete" :disabled="!qaMessages.length && !asking" @click="clearQaChat">清空对话</el-button>
              <el-button type="text" size="mini" icon="el-icon-setting" v-hasPermi="['business:ai:config:query']" @click="goAiConfig">AI 配置</el-button>
            </div>
          </div>

          <div ref="qaChatScroll" class="qa-chat-messages">
            <div v-if="!qaMessages.length && !asking" class="qa-chat-empty">
              <div class="qa-chat-empty-title">知识库对话</div>
              <div class="qa-chat-empty-desc">先给结论，再补充关键数据。思考链路和引用来源会保留在每条回答里。</div>
              <div class="qa-chat-suggestions">
                <button v-for="tip in qaSuggestions" :key="tip" type="button" class="qa-suggestion" @click="useQaSuggestion(tip)">{{ tip }}</button>
              </div>
            </div>

            <div v-for="message in qaMessages" :key="message.id" :class="['qa-msg', message.role]">
              <div class="qa-msg-meta">
                <span class="qa-msg-role">{{ message.role === 'user' ? '我' : '助手' }}</span>
                <span v-if="message.role === 'assistant' && message.result && message.result.qaStatus" class="qa-msg-model">{{ qaStatusLabel(message.result.qaStatus) }}</span>
                <el-tag v-if="message.role === 'assistant' && message.result" size="mini" :type="answerModeType(message.result.answerMode)">{{ answerModeText(message.result.answerMode) }}</el-tag>
                <span v-if="message.role === 'assistant' && message.result && message.result.model" class="qa-msg-model">{{ message.result.model }}</span>
              </div>

              <div v-if="message.role === 'user'" class="qa-msg-bubble qa-msg-user">{{ message.content }}</div>

              <div v-else class="qa-msg-bubble qa-msg-assistant">
                <el-alert
                  v-for="(warning, index) in ((message.result && message.result.warnings) || [])"
                  :key="`${message.id}-warn-${index}`"
                  :title="warning"
                  type="warning"
                  :closable="false"
                  class="mb12"
                />
                <div v-if="message.result && message.result.sourceBreakdown" class="qa-msg-tags">
                  <el-tag v-if="message.result.answerMode === 'WEB_SEARCH' || message.result.answerMode === 'KB_AND_WEB'" size="mini" type="warning">联网搜索</el-tag>
                  <el-tag v-else-if="message.result.citationPolicy === 'AGENT_GROUNDED'" size="mini" type="info">参考来源见下方</el-tag>
                  <el-tag size="mini">报告 {{ message.result.sourceBreakdown.REPORT || 0 }}</el-tag>
                  <el-tag size="mini" type="warning">新闻 {{ message.result.sourceBreakdown.NEWS || 0 }}</el-tag>
                  <el-tag size="mini" type="success">政策 {{ message.result.sourceBreakdown.POLICY || 0 }}</el-tag>
                  <el-tag size="mini" type="info">PDF {{ message.result.sourceBreakdown.PDF || 0 }}</el-tag>
                </div>
                <div class="qa-msg-answer">
                  <template v-for="segment in answerSegments(message.result)">
                    <span v-if="!segment.citation" :key="segment.key">{{ segment.text }}</span>
                    <el-popover v-else :key="segment.key" placement="top-start" width="430" trigger="hover">
                      <div class="inline-source-title"><i :class="sourceIcon(segment.citation.sourceType)" /> {{ segment.citation.sourceName }} · {{ segment.citation.versionNo }}</div>
                      <div class="citation-snippet">{{ citationPreview(segment.citation) }}</div>
                      <a v-if="segment.citation.sourceUrl" :href="segment.citation.sourceUrl" target="_blank" rel="noopener noreferrer">打开来源原文</a>
                      <sup slot="reference" class="inline-citation" @click.stop="openCitation(segment.citation)">[{{ segment.citation.citationLabel }}]</sup>
                    </el-popover>
                  </template>
                </div>

                <el-collapse v-if="message.result" v-model="message.openPanels" class="qa-msg-panels" @change="onAssistantPanelsChange(message)">
                  <el-collapse-item title="思考链路" name="trace">
                    <el-steps v-if="message.result.queryPlan && message.result.queryPlan.length" :active="message.result.queryPlan.length" finish-status="success" simple>
                      <el-step v-for="step in message.result.queryPlan" :key="`${message.id}-plan-${step.order}`" :title="step.name" :description="`${step.action} · ${step.input || ''}`" />
                    </el-steps>
                    <el-table v-if="message.result.retrievalLogs && message.result.retrievalLogs.length" :data="message.result.retrievalLogs" size="mini" class="log-table">
                      <el-table-column label="状态" prop="status" width="100"><template slot-scope="s"><el-tag size="mini" :type="logStatusType(s.row.status)">{{ s.row.status }}</el-tag></template></el-table-column>
                      <el-table-column label="动作" prop="action" width="140" />
                      <el-table-column label="输入/过滤" prop="input" min-width="180" />
                      <el-table-column label="结果" prop="result" min-width="140" />
                      <el-table-column label="耗时" width="90"><template slot-scope="s">{{ s.row.durationMs }} ms</template></el-table-column>
                    </el-table>
                    <el-timeline v-if="message.result.analysisTrace && message.result.analysisTrace.length" class="analysis-timeline">
                      <el-timeline-item v-for="step in message.result.analysisTrace" :key="`${message.id}-trace-${step.order}`" :timestamp="`步骤 ${step.order}`" placement="top" type="primary">
                        <div class="analysis-step-card">
                          <div class="analysis-step-title">{{ step.title }}</div>
                          <div><b>我做了什么：</b>{{ step.action }}</div>
                          <div><b>得到什么：</b>{{ step.result }}</div>
                          <div class="analysis-boundary"><b>结论边界：</b>{{ step.boundary }}</div>
                        </div>
                      </el-timeline-item>
                    </el-timeline>
                  </el-collapse-item>

                  <el-collapse-item v-if="message.result.claims && message.result.claims.length" title="逐句证据核验" name="claims">
                    <div v-for="(claim, claimIndex) in message.result.claims" :key="`${message.id}-claim-${claimIndex}`" class="claim-row">
                      <div><el-tag type="success" size="mini">已核验</el-tag> {{ claim.claimText }}</div>
                      <div v-for="(evidence, evidenceIndex) in claim.evidences" :key="`${message.id}-ev-${claimIndex}-${evidenceIndex}`" class="claim-evidence">
                        <b>[{{ evidence.citationLabel }}]</b>
                        {{ evidence.sourceName }}<span v-if="evidence.originalName"> · {{ evidence.originalName }}</span><span v-if="evidence.pageStart"> · PDF 第 {{ evidence.pageStart }} 页</span>
                        · 匹配度 {{ Math.round(evidence.matchScore * 100) }}%
                        <div class="citation-snippet">原文：{{ evidence.evidenceSnippet }}</div>
                        <el-button type="text" size="mini" @click="openEvidence(evidence)">精确定位原文</el-button>
                      </div>
                    </div>
                  </el-collapse-item>

                  <el-collapse-item v-if="message.result.citations && message.result.citations.length" title="引用来源" name="citations">
                    <div v-for="item in message.result.citations" :key="`${message.id}-cite-${item.citationLabel}-${item.id}`" class="citation-row">
                      <i :class="sourceIcon(item.sourceType)" /><b>[{{ item.citationLabel }}]</b> {{ item.sourceName }} · {{ item.versionNo }}
                      <span v-if="item.originalName"> · {{ item.originalName }}</span>
                      <span v-if="item.pageStart"> · PDF 第 {{ item.pageStart }} 页</span>
                      <span v-if="item.metricId"> · 指标 {{ item.metricId }}</span>
                      <a v-if="item.sourceUrl" :href="item.sourceUrl" target="_blank" rel="noopener noreferrer"> · 原文链接</a>
                      <div class="citation-snippet">{{ item.sourceSnippet }}</div>
                      <el-button v-if="item.sourceType !== 'WEB'" type="text" size="mini" @click="openCitation(item)">点击定位引用段落</el-button>
                    </div>
                  </el-collapse-item>

                  <el-collapse-item v-if="message.result.graph && message.result.graph.nodes && message.result.graph.nodes.length" title="知识图谱" name="graph">
                    <el-form :inline="true" size="mini" class="qa-graph-filter" @submit.native.prevent>
                      <el-form-item label="时间"><el-input v-model="qaGraphFilter.period" clearable placeholder="例如 2023 Q3" @keyup.enter.native="applyQaGraphFilter" /></el-form-item>
                      <el-form-item label="类型">
                        <el-select v-model="qaGraphFilter.dataType" clearable placeholder="全部">
                          <el-option label="企业" value="COMPANY" /><el-option label="车型" value="MODEL" />
                          <el-option label="销量" value="SALES" /><el-option label="新闻" value="NEWS" />
                          <el-option label="财报" value="FINANCIAL" /><el-option label="政策" value="POLICY" />
                        </el-select>
                      </el-form-item>
                      <el-form-item>
                        <el-button type="primary" icon="el-icon-search" @click="applyQaGraphFilter">筛选</el-button>
                        <el-button v-if="qaGraphCenterId" icon="el-icon-back" @click="resetQaGraphCenter">返回全图</el-button>
                      </el-form-item>
                    </el-form>
                    <div v-if="activeQaMessageId === message.id">
                      <div v-if="qaGraphCenterId" class="qa-graph-status">当前下钻：{{ qaGraphCenterName }}</div>
                      <el-empty v-if="!qaGraphData.nodes.length" description="当前筛选条件下没有匹配的图谱关系" />
                      <div v-show="qaGraphData.nodes.length" :ref="'qaGraph-' + message.id" class="qa-graph" />
                      <el-card v-if="selectedQaRelation" shadow="never" class="relation-card">
                        <b>{{ selectedQaRelation.name }}</b> · {{ selectedQaRelation.sourceName }} · {{ selectedQaRelation.versionNo }}
                        <div class="citation-snippet">{{ selectedQaRelation.evidenceSnippet }}</div>
                        <el-button type="text" @click="openEvidence(selectedQaRelation)">定位源文档段落</el-button>
                      </el-card>
                    </div>
                    <div v-else class="qa-graph-hint">展开本条图谱后可交互查看</div>
                  </el-collapse-item>
                </el-collapse>
              </div>
            </div>

            <div v-if="asking" class="qa-msg assistant">
              <div class="qa-msg-meta"><span class="qa-msg-role">助手</span><span class="qa-msg-model">思考中</span></div>
              <div class="qa-msg-bubble qa-msg-assistant qa-msg-pending">
                <div class="qa-progress-head"><b>{{ (qaTask && qaTask.currentStage) || '正在理解问题并检索资料' }}</b><span>{{ (qaTask && qaTask.progress) || 0 }}%</span></div>
                <el-progress :percentage="(qaTask && qaTask.progress) || 0" :status="qaTask && qaTask.status==='FAILED' ? 'exception' : undefined" />
                <el-collapse v-model="qaRunningPanels" class="qa-msg-panels">
                  <el-collapse-item title="实时任务拆解" name="running-trace">
                    <el-steps direction="vertical" :active="runningActiveStep" finish-status="success" process-status="process">
                      <el-step v-for="step in ((qaTask && qaTask.queryPlan) || [])" :key="`running-${step.order}`" :title="step.name" :description="`${step.action} · ${step.input || ''}`" />
                    </el-steps>
                  </el-collapse-item>
                </el-collapse>
              </div>
            </div>
          </div>

          <div class="qa-chat-composer">
            <el-input
              v-model="qaForm.question"
              type="textarea"
              :rows="3"
              resize="none"
              class="qa-composer-input"
              placeholder="输入问题，Ctrl + Enter 发送"
              @keydown.native="onQaComposerKeydown"
            />
            <div class="qa-composer-actions">
              <span class="qa-composer-hint">{{ qaForm.allowWebSearch ? '已开启知识库＋互联网：知识库没有足够依据时才联网，并与知识库来源分开标注' : '默认只在唯一知识库中检索，不会自动联网' }}</span>
              <el-button type="primary" icon="el-icon-s-promotion" :loading="asking" :disabled="!qaForm.question.trim()" @click="doAsk">发送</el-button>
            </div>
          </div>
        </div>

        <el-drawer title="最近问答记录" :visible.sync="qaHistoryDrawer" size="420px" append-to-body>
          <div class="qa-history-drawer">
            <el-button size="mini" icon="el-icon-refresh" :loading="qaHistoryLoading" @click="loadQaHistory">刷新</el-button>
            <el-table :data="qaHistory" size="mini" style="margin-top:12px" @row-click="restoreQaTask">
              <el-table-column label="问题" prop="question" min-width="200" show-overflow-tooltip />
              <el-table-column label="状态" prop="status" width="90" />
              <el-table-column label="时间" prop="createdAt" width="150" />
            </el-table>
          </div>
        </el-drawer>
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
        <el-form-item label="是否启用">
          <el-switch v-model="form.enabled" active-value="1" inactive-value="0" />
          <div class="form-tip">关闭启用后，列表中才会允许删除该资料</div>
        </el-form-item>
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
        <div class="source-meta">
          <span>{{ evidenceDetail.sourceName }}</span>
          <span>{{ evidenceDetail.originalName }}</span>
          <span>版本 {{ evidenceDetail.versionNo }}</span>
          <span v-if="evidenceDetail.pageStart">{{ pageLabel(evidenceDetail) }}</span>
          <el-tag v-if="evidenceDetail.fileKind" size="mini" type="info">{{ fileKindLabel(evidenceDetail.fileKind) }}</el-tag>
        </div>
        <pre class="evidence-content"><span>{{ evidenceBefore }}</span><mark>{{ evidenceDetail.highlightedText }}</mark><span>{{ evidenceAfter }}</span></pre>
        <el-button v-if="evidenceDetail.fileAvailable" type="primary" size="small" icon="el-icon-aim" :loading="pdfPreviewLoading" @click="openSourceFile">{{ openOriginalButtonText }}</el-button>
        <a v-if="evidenceDetail.sourceUrl" class="external-source-link" :href="evidenceDetail.sourceUrl" target="_blank" rel="noopener noreferrer">打开来源网站原文</a>
      </div>
    </el-dialog>

    <el-dialog
      :title="previewDialogTitle"
      :visible.sync="pdfPreviewOpen"
      width="92%"
      top="3vh"
      append-to-body
      custom-class="pdf-preview-dialog"
      @closed="revokePdfPreview"
    >
      <div class="pdf-preview-toolbar">
        <span>文件：{{ pdfPreviewName || '原件' }}</span>
        <span v-if="pdfPreviewPage">{{ pdfPreviewKind === 'pptx' ? '定位幻灯片' : '定位页' }}：第 {{ pdfPreviewPage }} 页</span>
        <span v-if="pdfPreviewKind === 'pptx' && pptxSlideCount">共 {{ pptxSlideCount }} 页</span>
        <el-tag v-if="highlightMatched === true" size="mini" type="success">已高亮定位</el-tag>
        <el-tag v-else-if="highlightMatched === false" size="mini" type="warning">已跳转页面，未精确匹配到原文（可对照下方高亮文本）</el-tag>
        <template v-if="pdfPreviewKind === 'pptx' && pptxSlideCount > 1">
          <el-button type="text" size="mini" :disabled="pdfPreviewPage <= 1 || pdfPreviewLoading" @click="shiftPptxSlide(-1)">上一页</el-button>
          <el-button type="text" size="mini" :disabled="pdfPreviewPage >= pptxSlideCount || pdfPreviewLoading" @click="shiftPptxSlide(1)">下一页</el-button>
        </template>
      </div>

      <div v-if="pdfPreviewKind === 'pdf'" class="pdf-locate-stage" v-loading="pdfPreviewLoading">
        <div class="pdf-locate-scroll">
          <div class="pdf-locate-canvas-wrap">
            <canvas ref="pdfCanvas" class="pdf-locate-canvas" />
            <canvas ref="pdfOverlay" class="pdf-locate-overlay" />
          </div>
        </div>
        <div v-if="pdfPreviewHighlight" class="locate-snippet">
          <div class="locate-snippet-label">定位摘录</div>
          <mark>{{ pdfPreviewHighlight }}</mark>
        </div>
      </div>

      <div v-else-if="pdfPreviewKind === 'pptx'" class="pptx-preview-panel" v-loading="pdfPreviewLoading">
        <div class="pptx-slide-card">
          <div class="pptx-slide-badge">幻灯片 {{ pdfPreviewPage }} / {{ pptxSlideCount || '?' }}</div>
          <h3 v-if="pptxSlideTitle" class="pptx-slide-title">{{ pptxSlideTitle }}</h3>
          <div class="pptx-slide-body" v-html="pptxHighlightedHtml" />
        </div>
        <div v-if="pdfPreviewHighlight" class="locate-snippet">
          <div class="locate-snippet-label">定位摘录</div>
          <mark>{{ pdfPreviewHighlight }}</mark>
        </div>
      </div>

      <div v-else class="empty-tip">正在加载原件…</div>
    </el-dialog>
  </div>
</template>

<script>
import { listKnowledgeBase, getKnowledgeBase, addKnowledgeBase, updateKnowledgeBase, delKnowledgeBase,
  ingestPdf, ingestNews, ingestPolicy, ingestNewsJson, ingestReport, getKnowledgeTask, listKnowledgeVersions, searchKnowledge,
  submitKnowledgeQaTask, getKnowledgeQaTask, listKnowledgeQaTasks,
  getKnowledgeEvidence, getKnowledgeEvidenceFile, getKnowledgeLocatePreview } from '@/api/business/knowledge/knowledgeBase'
import * as echarts from 'echarts'
import { blobValidate } from '@/utils/ruoyi'
import { renderPdfPageWithHighlight } from '@/utils/pdfLocate'

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
      qaForm: { question: '', allowWebSearch: false }, asking: false, qaResult: null,
      qaTask: null, qaPoller: null, qaRunningPanels: ['running-trace'], qaTracePanels: [],
      qaHistory: [], qaHistoryLoading: false, qaHistoryDrawer: false,
      qaMessages: [], activeQaMessageId: null, qaMessageSeq: 0,
      qaSuggestions: ['byd 2023 2024 销量', '比亚迪2026年销量', 'Tianma 2025年前三季度 LTPS 出货'],
      qaGraphFilter: { period: '', dataType: '' }, qaGraphData: { nodes: [], links: [], categories: [] },
      qaGraphCenterId: null, selectedQaRelation: null, qaGraphInstance: null,
      evidenceOpen: false, evidenceDetail: null,
      pdfPreviewOpen: false, pdfPreviewLoading: false, pdfPreviewUrl: '', pdfPreviewObjectUrl: '',
      pdfPreviewPage: 1, pdfPreviewName: '', pdfPreviewKind: 'pdf', pdfPreviewBlob: null,
      pdfPreviewHighlight: '', pdfPreviewBytes: null, highlightMatched: null,
      pptxSlideCount: 0, pptxSlideTitle: '', pptxParagraphs: [], pptxFullText: ''
    }
  },
  created() { this.getList() },
  beforeDestroy() { this.stopPolling(); this.stopQaPolling(); this.revokePdfPreview(); if (this.qaGraphInstance) this.qaGraphInstance.dispose() },
  computed: {
    openOriginalButtonText() {
      const kind = this.evidenceDetail && this.evidenceDetail.fileKind
      if (kind === 'pptx') return '跳转到原 PPTX 定位并高亮'
      if (kind === 'pdf') return '跳转到原 PDF 定位并高亮'
      return '跳转到原文档定位并高亮'
    },
    previewDialogTitle() {
      return this.pdfPreviewKind === 'pptx' ? '原 PPTX 定位预览' : '原 PDF 定位预览'
    },
    pptxHighlightedHtml() {
      return this.buildHighlightedHtml(this.pptxParagraphs, this.pdfPreviewHighlight || (this.evidenceDetail && this.evidenceDetail.highlightedText))
    },
    evidenceBefore() { if (!this.evidenceDetail) return ''; return this.evidenceDetail.content.slice(0, this.evidenceDetail.startOffset) },
    evidenceAfter() { if (!this.evidenceDetail) return ''; return this.evidenceDetail.content.slice(this.evidenceDetail.endOffset) },
    qaGraphCenterName() {
      if (!this.qaGraphCenterId || !this.qaResult || !this.qaResult.graph) return ''
      const node = (this.qaResult.graph.nodes || []).find(item => String(item.id) === String(this.qaGraphCenterId))
      return node ? node.name : this.qaGraphCenterId
    },
    runningActiveStep() {
      if (!this.qaTask || !this.qaTask.queryPlan) return 0
      if ((this.qaTask.progress || 0) >= 90) return this.qaTask.queryPlan.length
      if ((this.qaTask.progress || 0) >= 35) return Math.max(1, this.qaTask.queryPlan.length - 1)
      if ((this.qaTask.progress || 0) >= 30) return Math.min(4, this.qaTask.queryPlan.length)
      if ((this.qaTask.progress || 0) >= 16) return Math.min(3, this.qaTask.queryPlan.length)
      if ((this.qaTask.progress || 0) >= 10) return Math.min(2, this.qaTask.queryPlan.length)
      return Math.min(1, this.qaTask.queryPlan.length)
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
    handleDelete(row) {
      if (!this.canDelete(row)) return this.$modal.msgError('请先编辑并将“是否启用”改为否，再删除')
      this.$modal.confirm(`确认删除“${row.sourceName}”吗？删除后不可恢复。`).then(() => delKnowledgeBase(row.id)).then(() => { this.$modal.msgSuccess('删除成功'); this.getList() }).catch(() => {})
    },
    canDelete(row) { return String(row && row.enabled) === '0' },
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
    qaStatusLabel(status) {
      return ({
        ANSWERED: '已回答',
        PARTIALLY_ANSWERED: '部分回答',
        NOT_FOUND_IN_KNOWLEDGE_BASE: '知识库未找到依据',
        LLM_UNAVAILABLE: '模型暂不可用',
        RETRIEVAL_FAILED: '检索失败'
      })[status] || status
    },
    doAsk() {
      if (this.asking) return
      if (!this.qaForm.question || this.qaForm.question.trim().length < 2) return this.$modal.msgError('问题至少2个字符')
      const question = this.qaForm.question.trim()
      this.stopQaPolling()
      this.asking = true
      this.qaResult = null
      this.qaTask = null
      this.qaRunningPanels = ['running-trace']
      this.resetQaGraphState()
      this.qaMessages.push({ id: `u-${++this.qaMessageSeq}`, role: 'user', content: question })
      this.qaForm.question = ''
      this.scrollQaChatToBottom()
      submitKnowledgeQaTask({ question, allowWebSearch: !!this.qaForm.allowWebSearch }).then(r => {
        this.qaTask = r.data
        this.startQaPolling(r.data.taskId)
      }).catch(() => { this.asking = false })
    },
    startQaPolling(taskId) {
      const tick = () => {
        getKnowledgeQaTask(taskId).then(r => {
          this.qaTask = r.data
          if (r.data.status === 'SUCCESS') {
            this.appendAssistantResult(r.data.result, r.data.question || '')
            this.asking = false
            this.stopQaPolling()
          } else if (r.data.status === 'FAILED') {
            this.asking = false
            this.stopQaPolling()
            this.$modal.msgError(r.data.errorMessage || '知识问答处理失败')
          } else {
            this.scrollQaChatToBottom()
            this.qaPoller = setTimeout(tick, 1200)
          }
        }).catch(() => { this.asking = false; this.stopQaPolling() })
      }
      tick()
    },
    stopQaPolling() { if (this.qaPoller) clearTimeout(this.qaPoller); this.qaPoller = null },
    appendAssistantResult(result, question) {
      const message = {
        id: `a-${++this.qaMessageSeq}`,
        role: 'assistant',
        content: (result && result.answer) || '',
        question: question || '',
        result: result || null,
        openPanels: ['citations']
      }
      this.qaMessages.push(message)
      this.qaResult = result
      this.activeQaMessageId = message.id
      this.scrollQaChatToBottom()
      this.$nextTick(() => this.initializeQaGraph())
    },
    clearQaChat() {
      if (this.asking) return this.$modal.msgWarning('请等待当前回答完成')
      this.stopQaPolling()
      this.qaMessages = []
      this.qaResult = null
      this.qaTask = null
      this.activeQaMessageId = null
      this.resetQaGraphState()
    },
    useQaSuggestion(text) {
      this.qaForm.question = text
      this.$nextTick(() => this.doAsk())
    },
    onQaComposerKeydown(event) {
      if (event.ctrlKey && event.key === 'Enter') {
        event.preventDefault()
        this.doAsk()
      }
    },
    scrollQaChatToBottom() {
      this.$nextTick(() => {
        const el = this.$refs.qaChatScroll
        if (el) el.scrollTop = el.scrollHeight
      })
    },
    answerSegments(result) {
      const answer = (result && result.answer) || ''
      const citations = ((result && result.citations) || []).reduce((map, item) => { map[item.citationLabel] = item; return map }, {})
      const evidenceQueues = {}
      ;((result && result.claims) || []).forEach(claim => {
        ;(claim.evidences || []).forEach(evidence => {
          if (!evidenceQueues[evidence.citationLabel]) evidenceQueues[evidence.citationLabel] = []
          evidenceQueues[evidence.citationLabel].push(evidence)
        })
      })
      const evidenceCursors = {}
      const segments = [];       const pattern = /\[([SW])(\d+)]/g; let start = 0; let match; let key = 0
      while ((match = pattern.exec(answer)) !== null) {
        if (match.index > start) segments.push({ key: `text-${key++}`, text: answer.slice(start, match.index) })
        const label = `${match[1]}${match[2]}`
        const queue = evidenceQueues[label] || []; const cursor = evidenceCursors[label] || 0
        const exactEvidence = queue[Math.min(cursor, Math.max(0, queue.length - 1))]
        evidenceCursors[label] = cursor + 1
        const citation = citations[label] && exactEvidence
          ? { ...citations[label], ...exactEvidence, evidenceLocations: [exactEvidence] }
          : citations[label]
        segments.push({ key: `citation-${key++}`, text: match[0], citation })
        start = pattern.lastIndex
      }
      if (start < answer.length) segments.push({ key: `text-${key++}`, text: answer.slice(start) })
      return segments
    },
    onAssistantPanelsChange(message) {
      if (!message || !message.result) return
      const panels = message.openPanels || []
      if (panels.includes('graph')) {
        this.qaResult = message.result
        this.activeQaMessageId = message.id
        this.$nextTick(() => this.initializeQaGraph())
      }
    },
    goAiConfig() { this.$router.push('/business/ai') },
    onTabClick(tab) { if (tab.name === 'qa') this.loadQaHistory() },
    loadQaHistory() { this.qaHistoryLoading = true; listKnowledgeQaTasks({ limit: 30 }).then(r => { this.qaHistory = r.data || [] }).finally(() => { this.qaHistoryLoading = false }) },
    restoreQaTask(row) {
      getKnowledgeQaTask(row.taskId).then(r => {
        this.qaHistoryDrawer = false
        this.qaTask = r.data
        if (r.data.status === 'SUCCESS') {
          const question = r.data.question || row.question || ''
          if (question) this.qaMessages.push({ id: `u-${++this.qaMessageSeq}`, role: 'user', content: question })
          this.appendAssistantResult(r.data.result, question)
          this.asking = false
        } else if (r.data.status === 'QUEUED' || r.data.status === 'RUNNING') {
          this.asking = true
          this.startQaPolling(row.taskId)
        } else {
          this.asking = false
          this.$modal.msgWarning(r.data.errorMessage || '该任务执行失败')
        }
      })
    },
    resetQaGraphState() {
      this.qaGraphFilter = { period: '', dataType: '' }
      this.qaGraphData = { nodes: [], links: [], categories: [] }
      this.qaGraphCenterId = null; this.selectedQaRelation = null
      if (this.qaGraphInstance) { this.qaGraphInstance.dispose(); this.qaGraphInstance = null }
    },
    initializeQaGraph() {
      this.qaGraphFilter = { period: '', dataType: '' }; this.qaGraphCenterId = null; this.selectedQaRelation = null
      this.$nextTick(() => this.applyQaGraphFilter())
    },
    applyQaGraphFilter() {
      const source = (this.qaResult && this.qaResult.graph) || { nodes: [], links: [], categories: [] }
      const allNodes = source.nodes || []
      const nodeMap = allNodes.reduce((map, node) => { map[String(node.id)] = node; return map }, {})
      const period = (this.qaGraphFilter.period || '').replace(/\s+/g, '').toLowerCase()
      const dataType = (this.qaGraphFilter.dataType || '').toUpperCase()
      let links = (source.links || []).filter(link => {
        const linkPeriod = String(link.period || '').replace(/\s+/g, '').toLowerCase()
        const sourceNode = nodeMap[String(link.source)] || {}; const targetNode = nodeMap[String(link.target)] || {}
        const periodMatched = !period || linkPeriod.includes(period)
        const typeMatched = !dataType || String(link.dataType || '').toUpperCase() === dataType
          || String(sourceNode.entityType || '').toUpperCase() === dataType
          || String(targetNode.entityType || '').toUpperCase() === dataType
        return periodMatched && typeMatched
      })
      if (this.qaGraphCenterId) {
        links = links.filter(link => String(link.source) === String(this.qaGraphCenterId) || String(link.target) === String(this.qaGraphCenterId))
      }
      const ids = new Set(); links.forEach(link => { ids.add(String(link.source)); ids.add(String(link.target)) })
      const nodes = allNodes.filter(node => ids.has(String(node.id))).map(node => ({
        ...node, symbolSize: String(node.id) === String(this.qaGraphCenterId) ? 54 : node.symbolSize
      }))
      const present = new Set(nodes.map(node => node.entityType))
      const categories = (source.categories || []).filter(item => present.has(item.entityType))
      this.qaGraphData = { nodes, links, categories }; this.selectedQaRelation = null
      this.$nextTick(() => {
        if (!nodes.length) {
          if (this.qaGraphInstance) { this.qaGraphInstance.dispose(); this.qaGraphInstance = null }
          return
        }
        const refName = this.activeQaMessageId ? (`qaGraph-${this.activeQaMessageId}`) : 'qaGraph'
        this.renderGraph(refName, this.qaGraphData, 'qaGraphInstance')
      })
    },
    drillQaGraph(nodeId) { this.qaGraphCenterId = String(nodeId); this.applyQaGraphFilter() },
    resetQaGraphCenter() { this.qaGraphCenterId = null; this.applyQaGraphFilter() },
    resetQaGraphFilter() { this.qaGraphFilter = { period: '', dataType: '' }; this.qaGraphCenterId = null; this.applyQaGraphFilter() },
    renderGraph(refName, data, instanceName) {
      let element = this.$refs[refName]
      if (Array.isArray(element)) element = element[0]
      if (!element || !data || !data.nodes || !data.nodes.length) return
      if (this[instanceName]) this[instanceName].dispose()
      const chart = echarts.init(element); this[instanceName] = chart
      chart.setOption({ tooltip: { formatter: p => p.dataType === 'edge' ? `${p.data.name}<br/>${p.data.sourceName || ''}` : `${p.data.name}<br/>${p.data.entityType}` }, legend: [{ bottom: 8, data: (data.categories || []).map(c => c.name) }], series: [{ type: 'graph', layout: 'force', roam: true, draggable: true, categories: data.categories || [], data: data.nodes, links: data.links, label: { show: true, position: 'right' }, edgeLabel: { show: true, formatter: p => p.data.name, fontSize: 10 }, edgeSymbol: ['none', 'arrow'], force: { repulsion: 260, edgeLength: [90, 170], gravity: 0.08 }, lineStyle: { color: 'source', curveness: 0.12, opacity: 0.75 } }] })
      chart.on('click', params => {
        if (params.dataType === 'node') this.drillQaGraph(params.data.id)
        if (params.dataType === 'edge') this.selectedQaRelation = params.data
      })
    },
    openEvidence(item) { const chunkId = item.chunkId || item.id; if (!chunkId) return this.$modal.msgError('该来源缺少切片定位信息'); getKnowledgeEvidence(chunkId, { startOffset: item.startOffset, endOffset: item.endOffset }).then(r => { this.evidenceDetail = r.data; this.evidenceOpen = true }) },
    openCitation(item) { const evidence = item.evidenceLocations && item.evidenceLocations.length ? item.evidenceLocations[0] : item; this.openEvidence({ id: item.id, chunkId: evidence.chunkId || item.chunkId || item.id, startOffset: evidence.startOffset, endOffset: evidence.endOffset }) },
    pageLabel(item) {
      if (!item || !item.pageStart) return ''
      return (item.fileKind === 'pptx' ? '幻灯片第 ' : '第 ') + item.pageStart + ' 页'
    },
    fileKindLabel(kind) {
      return ({ pdf: 'PDF', pptx: 'PPTX', docx: 'DOCX', xlsx: 'XLSX' })[kind] || (kind || '').toUpperCase()
    },
    openSourceFile() {
      if (!this.evidenceDetail || !this.evidenceDetail.chunkId) return
      if (!this.evidenceDetail.fileAvailable) {
        this.$modal.msgError('当前知识源没有可打开的 PDF/PPTX 原件（文件缺失或上传目录已变更）')
        return
      }
      const kind = this.evidenceDetail.fileKind || this.detectFileKind(this.evidenceDetail.originalName)
      this.pdfPreviewLoading = true
      this.highlightMatched = null
      getKnowledgeLocatePreview(this.evidenceDetail.chunkId, {
        startOffset: this.evidenceDetail.startOffset,
        endOffset: this.evidenceDetail.endOffset,
        slide: this.evidenceDetail.pageStart || undefined
      }).then(async locateRes => {
        const locate = locateRes.data || {}
        this.revokePdfPreview()
        this.pdfPreviewKind = locate.kind || kind
        this.pdfPreviewName = locate.originalName || this.evidenceDetail.originalName || ''
        this.pdfPreviewHighlight = locate.highlightedText || this.evidenceDetail.highlightedText || ''
        this.pdfPreviewPage = Number(locate.pageNumber || locate.slideNumber || locate.pageStart || this.evidenceDetail.pageStart || 1)
        this.pdfPreviewOpen = true

        if (this.pdfPreviewKind === 'pptx') {
          this.applyPptxLocate(locate)
          return
        }

        if (this.pdfPreviewKind === 'pdf') {
          await this.renderPdfLocate()
          return
        }
        this.$modal.msgError('暂不支持该原件格式的在线定位预览')
      }).catch(error => {
        this.$modal.msgError((error && (error.msg || error.message)) || '原件定位打开失败')
      }).finally(() => { this.pdfPreviewLoading = false })
    },
    applyPptxLocate(locate) {
      this.pptxSlideCount = Number(locate.slideCount || 0)
      this.pptxSlideTitle = locate.slideTitle || ''
      this.pptxParagraphs = Array.isArray(locate.paragraphs) ? locate.paragraphs : []
      this.pptxFullText = locate.fullText || this.pptxParagraphs.join('\n')
      this.pdfPreviewPage = Number(locate.slideNumber || this.pdfPreviewPage || 1)
      this.highlightMatched = this.textContainsNormalized(this.pptxFullText, this.pdfPreviewHighlight)
      this.$nextTick(() => this.scrollHighlightIntoView())
    },
    async renderPdfLocate() {
      const blob = await getKnowledgeEvidenceFile(this.evidenceDetail.chunkId)
      if (!blobValidate(blob) || ((blob.type || '').toLowerCase().includes('json'))) {
        const text = await blob.text()
        let message = 'PDF 原件打开失败'
        try { message = JSON.parse(text).msg || message } catch (e) { /* keep */ }
        throw new Error(message)
      }
      const bytes = await blob.arrayBuffer()
      if (!bytes || bytes.byteLength < 5) throw new Error('PDF 原件内容为空')
      const head = String.fromCharCode(...new Uint8Array(bytes.slice(0, 5)))
      if (head !== '%PDF-') throw new Error('返回内容不是有效PDF文件')
      this.pdfPreviewBytes = bytes
      await this.$nextTick()
      let canvas = this.$refs.pdfCanvas
      let overlay = this.$refs.pdfOverlay
      for (let i = 0; i < 20 && (!canvas || !overlay); i++) {
        await new Promise(resolve => setTimeout(resolve, 50))
        canvas = this.$refs.pdfCanvas
        overlay = this.$refs.pdfOverlay
      }
      if (!canvas || !overlay) throw new Error('PDF 预览画布未就绪')
      const result = await renderPdfPageWithHighlight({
        data: bytes,
        pageNumber: this.pdfPreviewPage,
        highlightText: this.pdfPreviewHighlight,
        canvas,
        overlay
      })
      this.highlightMatched = !!result.matched
      if (result.pageCount) this.pptxSlideCount = result.pageCount
    },
    shiftPptxSlide(delta) {
      if (this.pdfPreviewKind !== 'pptx' || !this.evidenceDetail) return
      const next = Math.min(Math.max(1, this.pdfPreviewPage + delta), this.pptxSlideCount || this.pdfPreviewPage)
      if (next === this.pdfPreviewPage) return
      this.pdfPreviewLoading = true
      getKnowledgeLocatePreview(this.evidenceDetail.chunkId, {
        startOffset: this.evidenceDetail.startOffset,
        endOffset: this.evidenceDetail.endOffset,
        slide: next
      }).then(r => {
        this.applyPptxLocate(r.data || {})
      }).catch(error => {
        this.$modal.msgError((error && (error.msg || error.message)) || '切换幻灯片失败')
      }).finally(() => { this.pdfPreviewLoading = false })
    },
    buildHighlightedHtml(paragraphs, highlightText) {
      const lines = Array.isArray(paragraphs) ? paragraphs : []
      if (!lines.length) return '<div class="empty-tip">该幻灯片没有可提取的文本</div>'
      const needle = this.normalizeLocateText(highlightText)
      let remaining = needle
      return lines.map(line => {
        const safe = this.escapeHtml(line)
        if (!remaining) return `<p>${safe}</p>`
        const normalizedLine = this.normalizeLocateText(line)
        let idx = normalizedLine.indexOf(remaining)
        let matchLen = remaining.length
        if (idx < 0 && remaining.length > 20) {
          const partial = remaining.slice(0, 20)
          idx = normalizedLine.indexOf(partial)
          matchLen = partial.length
        }
        if (idx < 0) return `<p>${safe}</p>`
        remaining = ''
        return `<p>${this.wrapNormalizedHighlight(line, idx, Math.min(matchLen, normalizedLine.length - idx))}</p>`
      }).join('')
    },
    wrapNormalizedHighlight(rawLine, normStart, normLen) {
      // Map normalized index back onto original string by walking non-whitespace chars.
      let seen = 0
      let start = -1
      let end = -1
      for (let i = 0; i < rawLine.length; i++) {
        if (/\s/.test(rawLine[i])) continue
        if (seen === normStart) start = i
        seen += 1
        if (seen === normStart + normLen) {
          end = i + 1
          break
        }
      }
      if (start < 0) return this.escapeHtml(rawLine)
      if (end < 0) end = rawLine.length
      return `${this.escapeHtml(rawLine.slice(0, start))}<mark>${this.escapeHtml(rawLine.slice(start, end))}</mark>${this.escapeHtml(rawLine.slice(end))}`
    },
    normalizeLocateText(value) {
      return String(value || '').replace(/\s+/g, '').toLowerCase()
    },
    textContainsNormalized(haystack, needle) {
      const n = this.normalizeLocateText(needle)
      if (!n) return false
      const h = this.normalizeLocateText(haystack)
      return h.includes(n) || (n.length > 20 && h.includes(n.slice(0, 20)))
    },
    escapeHtml(value) {
      return String(value || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;')
    },
    scrollHighlightIntoView() {
      const root = this.$el && this.$el.querySelector && this.$el.querySelector('.pptx-slide-body mark')
      if (root && root.scrollIntoView) root.scrollIntoView({ behavior: 'smooth', block: 'center' })
    },
    detectFileKind(name) {
      const lower = (name || '').toLowerCase()
      if (lower.endsWith('.pdf')) return 'pdf'
      if (lower.endsWith('.pptx')) return 'pptx'
      return 'other'
    },
    revokePdfPreview() {
      if (this.pdfPreviewObjectUrl) {
        URL.revokeObjectURL(this.pdfPreviewObjectUrl)
      }
      this.pdfPreviewObjectUrl = ''
      this.pdfPreviewUrl = ''
      this.pdfPreviewName = ''
      this.pdfPreviewPage = 1
      this.pdfPreviewKind = 'pdf'
      this.pdfPreviewBlob = null
      this.pdfPreviewBytes = null
      this.pdfPreviewHighlight = ''
      this.highlightMatched = null
      this.pptxSlideCount = 0
      this.pptxSlideTitle = ''
      this.pptxParagraphs = []
      this.pptxFullText = ''
    },
    citationPreview(item) { const evidence = item.evidenceLocations && item.evidenceLocations.length ? item.evidenceLocations[0] : null; return (evidence && evidence.evidenceSnippet) || item.sourceSnippet || '暂无摘要' },
    sourceIcon(type) { return ({ NEWS: 'el-icon-news', POLICY: 'el-icon-document-checked', PDF: 'el-icon-document', REPORT: 'el-icon-data-analysis', WEB: 'el-icon-link' })[type] || 'el-icon-files' },
    sourceTypeLabel(type) { const item = this.sourceTypes.find(t => t.value === type); return item ? item.label : (type || '未分类') },
    sourceTypeTag(type) { return ({ NEWS: 'success', REPORT: 'primary', PDF: 'info', POLICY: 'warning' })[type] || 'info' },
    statusText(s) { return ({ '0': '待入库', '1': '入库中', '2': '入库成功', '3': '入库失败' })[s] || s },
    statusType(s) { return ({ '0': 'info', '1': 'warning', '2': 'success', '3': 'danger' })[s] || 'info' },
    isIngestSuccess(row) { return row.status === '2' || !!row.currentVersionId },
    isIngestRunning(row) { return !this.isIngestSuccess(row) && (row.status === '1' || (row.status === '0' && String(row.sourceCode || '').startsWith('NEWS-ARTICLE-'))) },
    showIngestButton(row) {
      if (this.isIngestSuccess(row) || this.isIngestRunning(row)) return false
      if (row.sourceType === 'NEWS' && String(row.sourceCode || '').startsWith('NEWS-ARTICLE-')) return false
      return !row.currentVersionId
    },
    answerModeText(mode) { return ({ LLM_VERIFIED: '分析助手回答', LLM_REPAIRED: '分析助手回答', EXTRACTIVE_FALLBACK: '资料整理回答', LLM_UNAVAILABLE_WITH_EVIDENCE: '已找到证据，模型暂不可用', PROGRAM_CALCULATED: '按原文计算', PROGRAM_PARTIAL: '部分依据', NOT_FOUND: '知识库未找到依据', RETRIEVAL_FAILED: '检索失败', WEB_SEARCH: '联网搜索', KB_AND_WEB: '知识库 + 联网辅助' })[mode] || mode || '未知模式' },
    answerModeType(mode) { return mode === 'WEB_SEARCH' || mode === 'KB_AND_WEB' || mode === 'EXTRACTIVE_FALLBACK' || mode === 'NOT_FOUND' || mode === 'LLM_UNAVAILABLE_WITH_EVIDENCE' ? 'warning' : 'success' },
    logStatusType(status) { return ({ SUCCESS: 'success', RETRY: 'warning', FALLBACK: 'warning' })[status] || 'info' },
    prettyEvidence(value) { try { return JSON.stringify(JSON.parse(value), null, 2) } catch (e) { return value } }
  }
}
</script>

<style scoped>
.mb12 { margin-bottom: 12px; }
.qa-chat-shell {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 168px);
  min-height: 620px;
  border: 1px solid #e4e7ed;
  border-radius: 12px;
  background: linear-gradient(180deg, #f7f9fc 0%, #ffffff 28%);
  overflow: hidden;
}
.qa-chat-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid #ebeef5;
  background: rgba(255,255,255,.92);
}
.qa-chat-toolbar-left, .qa-chat-toolbar-right { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.qa-source-select { width: 140px; }
.qa-web-llm { display: inline-flex; align-items: center; gap: 6px; margin-left: 10px; color: #606266; font-size: 12px; }
.qa-chat-messages {
  flex: 1;
  overflow: auto;
  padding: 20px 18px 12px;
}
.qa-chat-empty {
  max-width: 720px;
  margin: 48px auto 0;
  text-align: center;
  color: #606266;
}
.qa-chat-empty-title { font-size: 22px; font-weight: 700; color: #303133; margin-bottom: 8px; }
.qa-chat-empty-desc { line-height: 1.7; margin-bottom: 22px; }
.qa-chat-suggestions { display: flex; flex-wrap: wrap; gap: 10px; justify-content: center; }
.qa-suggestion {
  border: 1px solid #dcdfe6;
  background: #fff;
  color: #606266;
  border-radius: 999px;
  padding: 8px 14px;
  cursor: pointer;
  line-height: 1.4;
}
.qa-suggestion:hover { border-color: #409eff; color: #409eff; }
.qa-msg { display: flex; flex-direction: column; margin-bottom: 18px; max-width: 860px; }
.qa-msg.user { margin-left: auto; align-items: flex-end; }
.qa-msg.assistant { margin-right: auto; align-items: flex-start; width: min(860px, 100%); }
.qa-msg-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; color: #909399; font-size: 12px; }
.qa-msg-role { font-weight: 700; color: #606266; }
.qa-msg-model { color: #909399; }
.qa-msg-bubble { border-radius: 14px; padding: 14px 16px; line-height: 1.8; white-space: pre-wrap; word-break: break-word; }
.qa-msg-user { background: #409eff; color: #fff; border-bottom-right-radius: 4px; }
.qa-msg-assistant { background: #fff; border: 1px solid #ebeef5; box-shadow: 0 8px 24px rgba(31,45,61,.04); border-bottom-left-radius: 4px; width: 100%; }
.qa-msg-pending { background: #fafbfc; }
.qa-msg-tags { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.qa-msg-answer { white-space: pre-wrap; line-height: 1.9; color: #303133; }
.qa-msg-panels { margin-top: 12px; }
.qa-graph-hint { color: #909399; font-size: 13px; padding: 8px 0; }
.qa-chat-composer {
  border-top: 1px solid #ebeef5;
  background: #fff;
  padding: 12px 16px 14px;
}
.qa-composer-input >>> textarea {
  border-radius: 10px;
  padding: 12px 14px;
  min-height: 84px !important;
  font-size: 14px;
  line-height: 1.7;
}
.qa-composer-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
}
.qa-composer-hint { color: #909399; font-size: 12px; }
.qa-history-drawer { padding: 0 8px 16px; }
.qa-progress-head { display:flex; justify-content:space-between; margin-bottom:10px; color:#606266; }
.mb16 { margin-bottom: 16px; }.danger { color:#f56c6c; }.is-disabled { opacity: 0.45; cursor: not-allowed; }.form-tip { margin-top: 6px; color: #909399; font-size: 12px; line-height: 1.4; }.ingest-success { color:#67c23a; margin-right: 8px; font-size: 12px; }.ingest-running { color:#e6a23c; margin-right: 8px; font-size: 12px; }.knowledge-category-filter { display:flex; align-items:center; gap:14px; padding:14px 16px; margin-bottom:16px; background:#f7f9fc; border:1px solid #ebeef5; border-radius:6px; }.category-title { color:#303133; font-weight:600; }.source-breakdown { display:flex; align-items:center; gap:8px; margin-bottom:14px; color:#606266; }.result-card { margin-bottom:14px; }.result-head { display:flex; justify-content:space-between; align-items:center; }.snippet { line-height:1.75; white-space:pre-wrap; }.source-meta { display:flex; flex-wrap:wrap; gap:18px; color:#8492a6; font-size:13px; }.task-card { margin-top:16px; line-height:2; } pre { white-space:pre-wrap; max-height:260px; overflow:auto; }.answer-card { margin-top:18px; }.answer-header { display:flex; justify-content:space-between; align-items:center; }.answer-mode { margin-left:10px; }.answer-text { line-height:1.9; white-space:pre-wrap; margin-top:16px; }.model-name { color:#909399; font-size:12px; }.claim-row { padding:10px 0; border-bottom:1px dashed #dcdfe6; line-height:1.8; }.claim-evidence { margin:6px 0 0 26px; padding:8px 10px; background:#f7f9fc; border-left:3px solid #67c23a; }.citation-row { padding:10px 0; border-bottom:1px solid #ebeef5; line-height:1.7; }.citation-snippet { color:#606266; font-size:13px; white-space:pre-wrap; }.qa-graph { height:420px; background:#f8fafc; border:1px solid #ebeef5; border-radius:8px; }.qa-graph-filter { margin-bottom:4px; }.qa-graph-status { margin:0 0 12px; color:#409eff; font-weight:600; }.relation-card { margin-top:14px; }.relation-card a { margin-left:18px; }.log-table { margin-top:12px; }.evidence-content { margin-top:16px; max-height:480px; padding:16px; background:#f7f9fc; line-height:1.8; }.evidence-content mark { background:#ffe58f; color:#303133; }.qa-progress-card { margin:14px 0; }.trace-collapse { margin:12px 0 16px; }.trace-title-icon { margin-right:8px; color:#409eff; }.inline-citation { color:#409eff; cursor:pointer; font-weight:600; margin:0 2px; }.inline-citation:hover { color:#66b1ff; text-decoration:underline; }.inline-source-title { font-weight:600; margin-bottom:8px; }.inline-source-title i,.citation-row>i { margin-right:6px; color:#409eff; }
.source-meta { align-items:center; }.source-link-button { padding:0; }.external-source-link { margin-left:16px; }
.pdf-preview-toolbar { display:flex; align-items:center; flex-wrap:wrap; gap:14px; margin-bottom:10px; color:#606266; font-size:13px; }
.pdf-locate-stage { display:flex; gap:16px; align-items:flex-start; min-height:70vh; }
.pdf-locate-scroll { flex:1; min-width:0; overflow:auto; max-height:78vh; background:#525659; border:1px solid #ebeef5; text-align:center; padding:12px; }
.pdf-locate-canvas-wrap { position:relative; display:inline-block; max-width:100%; }
.pdf-locate-canvas, .pdf-locate-overlay { display:block; max-width:100%; height:auto; }
.pdf-locate-overlay { position:absolute; left:0; top:0; pointer-events:none; }
.locate-snippet { width:280px; flex-shrink:0; padding:12px; background:#fffbe6; border:1px solid #ffe58f; border-radius:6px; line-height:1.7; max-height:78vh; overflow:auto; }
.locate-snippet-label { color:#909399; font-size:12px; margin-bottom:8px; }
.locate-snippet mark { background:#ffe58f; }
.pptx-preview-panel { padding:8px 4px 12px; display:flex; gap:16px; align-items:flex-start; }
.pptx-slide-card { flex:1; min-width:0; min-height:60vh; max-height:78vh; overflow:auto; padding:28px 32px; background:linear-gradient(180deg,#f8fafc 0%,#ffffff 40%); border:1px solid #dcdfe6; border-radius:10px; box-shadow:0 8px 24px rgba(31,45,61,.08); }
.pptx-slide-badge { display:inline-block; margin-bottom:12px; padding:2px 10px; border-radius:999px; background:#ecf5ff; color:#409eff; font-size:12px; }
.pptx-slide-title { margin:0 0 16px; font-size:22px; color:#303133; }
.pptx-slide-body { line-height:1.9; color:#303133; font-size:15px; }
.pptx-slide-body p { margin:0 0 10px; }
.pptx-slide-body mark { background:#ffe58f; color:#303133; padding:0 2px; border-radius:2px; }
.analysis-timeline { padding:6px 8px 0 6px; }.analysis-step-card { line-height:1.75; }.analysis-step-title { font-weight:700; font-size:15px; color:#303133; margin-bottom:6px; }.analysis-boundary { color:#e6a23c; }.analysis-evidences { display:flex; flex-wrap:wrap; align-items:center; gap:8px; margin-top:6px; padding-top:6px; border-top:1px dashed #dcdfe6; }
</style>
