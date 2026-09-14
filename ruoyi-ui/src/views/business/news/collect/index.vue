<template>
  <div class="app-container">
    <el-form :model="q" :inline="true" size="small">
      <el-form-item label="来源">
        <el-select v-model="q.sourceName" filterable clearable placeholder="请选择或搜索新闻来源" class="source-select">
          <el-option v-for="source in querySourceOptions" :key="source.name" :label="source.name" :value="source.name" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态"><el-select v-model="q.status" clearable><el-option label="处理中" value="1"/><el-option label="成功" value="2"/><el-option label="失败" value="3"/></el-select></el-form-item>
      <el-button type="primary" @click="query">查询</el-button><el-button @click="resetQuery">重置</el-button><el-button v-hasPermi="['business:news:collect:add']" type="success" @click="dialog=true">立即采集</el-button>
    </el-form>
    <el-table :data="rows"><el-table-column prop="taskName" label="任务名称"/><el-table-column prop="sourceName" label="来源"/><el-table-column prop="triggerType" label="触发"/><el-table-column prop="status" label="状态"/><el-table-column label="采集条件" width="220"><template slot-scope="s">{{ dateRange(s.row) }}{{ s.row.force ? '；强制重新采集' : '' }}</template></el-table-column><el-table-column prop="startedTime" label="开始时间"/><el-table-column prop="completedTime" label="完成时间"/><el-table-column label="采集侧" width="140"><template slot-scope="s"><el-popover placement="top" trigger="hover"><div>详情请求：{{ s.row.fetchedCount || 0 }}</div><div>详情处理成功：{{ s.row.insertedCount || 0 }}</div><div v-if="s.row.updatedCount">暂存报告更新：{{ s.row.updatedCount }}</div><div>暂存重复：{{ s.row.duplicateCount || 0 }}</div><div>过滤：{{ s.row.filteredCount || 0 }}</div><div>采集失败：{{ s.row.failedCount || 0 }}</div><span slot="reference">请求 {{ s.row.fetchedCount || 0 }} / 重复 {{ s.row.duplicateCount || 0 }}</span></el-popover></template></el-table-column><el-table-column label="主库侧" width="165"><template slot-scope="s"><span v-if="s.row.statisticsVersion === 'V2'">新增 {{ s.row.mysqlInsertedCount || 0 }} / 更新 {{ s.row.mysqlUpdatedCount || 0 }} / 已有 {{ s.row.mysqlExistingCount || 0 }}</span><span v-else class="history-stat">历史任务未记录</span></template></el-table-column><el-table-column prop="errorMessage" label="错误"/><el-table-column label="操作" width="400"><template slot-scope="s"><el-button v-hasPermi="['business:news:collect:query']" type="text" @click="viewTaskArticles(s.row.id)">查看本次新闻</el-button><el-button v-if="s.row.status==='2'" v-hasPermi="['business:news:collect:edit']" type="text" :loading="!!batchPublishing[s.row.id]" @click="publishTask(s.row)">本次全部入库</el-button><el-button v-hasPermi="['business:news:collect:remove']" type="text" class="danger" @click="openDeleteArticles(s.row.id)">删除</el-button><el-button v-if="s.row.status==='3'" v-hasPermi="['business:news:collect:edit']" type="text" @click="retry(s.row.id)">重新采集</el-button></template></el-table-column></el-table>
    <pagination :total="total" :page.sync="q.pageNum" :limit.sync="q.pageSize" @pagination="load"/>
    <el-dialog title="立即采集" :visible.sync="dialog"><el-form><el-form-item label="来源"><el-select v-model="form.sourceName" filterable clearable placeholder="请选择或搜索新闻来源" class="source-select"><el-option v-for="source in sourceOptions" :key="source.name" :label="source.name" :value="source.name" /></el-select></el-form-item><el-form-item label="发布时间开始"><el-date-picker v-model="form.publishTimeStart" type="date" clearable value-format="yyyy-MM-dd" placeholder="不限" /></el-form-item><el-form-item label="发布时间结束"><el-date-picker v-model="form.publishTimeEnd" type="date" clearable value-format="yyyy-MM-dd" placeholder="不限" /></el-form-item><el-form-item><el-checkbox v-model="form.force">强制重新采集</el-checkbox></el-form-item></el-form><span slot="footer"><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="crawl">开始</el-button></span></el-dialog>
    <el-dialog title="本次命中的新闻" :visible.sync="articlesDialog" width="1050px"><el-table :data="taskArticles" max-height="420"><el-table-column prop="title" label="标题" min-width="250" show-overflow-tooltip/><el-table-column prop="publishedAt" label="发布时间" width="150"/><el-table-column prop="operation" label="采集操作" width="130"/><el-table-column prop="mysqlOperation" label="主库结果" width="145"/><el-table-column label="知识库" width="90"><template slot-scope="s"><el-tag v-if="s.row.knowledgeStored" size="mini" type="success">已入库</el-tag><el-tag v-else size="mini" type="info">未入库</el-tag></template></el-table-column><el-table-column label="操作" width="235"><template slot-scope="s"><el-button v-hasPermi="['business:news:process:query']" type="text" @click="showArticle(s.row)">查看详情</el-button><el-button v-hasPermi="['business:news:collect:edit']" type="text" :loading="!!articlePublishing[s.row.articleId]" @click="publishArticle(s.row)">{{ s.row.knowledgeStored ? '重新入库' : '入库' }}</el-button><el-link v-if="s.row.canonicalUrl" :href="s.row.canonicalUrl" target="_blank" type="primary">查看原文</el-link></template></el-table-column></el-table><div v-if="!taskArticles.length" class="empty-tip">本次任务没有可关联的新闻。</div></el-dialog>
    <el-dialog title="移除本任务新闻" :visible.sync="deleteDialog" width="900px"><div class="delete-tip">仅移除本次采集任务与新闻的关联；新闻正文和统一知识库版本保持不变。</div><el-table :data="taskArticles" max-height="420" @selection-change="selectedArticles = $event"><el-table-column type="selection" width="55"/><el-table-column prop="title" label="标题" min-width="300" show-overflow-tooltip/><el-table-column prop="publishedAt" label="发布时间" width="160"/><el-table-column prop="operation" label="采集操作" width="150"/></el-table><span slot="footer"><el-button @click="deleteDialog=false">取消</el-button><el-button type="danger" :disabled="!selectedArticles.length" @click="deleteSelectedArticles">移除选中新闻</el-button></span></el-dialog>
    <news-article-drawer :visible.sync="articleDrawer" :article-id="selectedArticleId" :operation="selectedOperation" :mysql-operation="selectedMysqlOperation" />
  </div>
</template>
<script>
import request from '@/utils/request'
import NewsArticleDrawer from '@/components/NewsArticleDrawer'
import { listCollectableNewsSources, listHistoricalNewsSources, publishTaskNewsKnowledge, publishArticleKnowledge } from '@/api/business/news/collect/newsCollect'

export default {
  name: 'NewsCollect',
  components: { NewsArticleDrawer },
  data() {
    return {
      q: { pageNum: 1, pageSize: 10, sourceName: '', status: '' }, rows: [], total: 0,
      sourceOptions: [], querySourceOptions: [], dialog: false, articlesDialog: false, deleteDialog: false,
      articleDrawer: false, selectedArticleId: null, selectedOperation: '', selectedMysqlOperation: '',
      taskArticles: [], selectedArticles: [], deletingTaskId: null, viewingTaskId: null,
      batchPublishing: {}, articlePublishing: {}, form: { sourceName: '', publishTimeStart: '', publishTimeEnd: '', force: false }, timer: null
    }
  },
  created() { this.load(); this.loadSources() },
  beforeDestroy() { clearInterval(this.timer) },
  methods: {
    load() {
      request({ url: '/business/news/collect/list', method: 'get', params: this.q }).then(r => {
        this.rows = r.rows; this.total = r.total
        if (this.rows.some(x => x.status === '1') && !this.timer) this.timer = setInterval(this.load, 4000)
        if (!this.rows.some(x => x.status === '1') && this.timer) { clearInterval(this.timer); this.timer = null }
      })
    },
    query() { this.q.pageNum = 1; this.load() },
    resetQuery() { this.q.sourceName = ''; this.q.status = ''; this.q.pageNum = 1; this.load() },
    loadSources() {
      Promise.all([listCollectableNewsSources(), listHistoricalNewsSources()]).then(([available, historical]) => {
        // The API has no stable source code; this UI-only exclusion applies
        // only to the immediate-collection catalogue, not historical filters.
        this.sourceOptions = (available.data || []).filter(source => source.enabled !== false && source.name !== '懂车帝')
        const sourceMap = new Map(this.sourceOptions.map(source => [source.name, source]))
        ;(historical.data || []).forEach(name => { if (!sourceMap.has(name)) sourceMap.set(name, { name }) })
        this.querySourceOptions = Array.from(sourceMap.values())
      })
    },
    crawl() {
      if (this.form.publishTimeStart && this.form.publishTimeEnd && this.form.publishTimeStart > this.form.publishTimeEnd) { this.$modal.msgError('发布时间开始不能晚于结束时间'); return }
      request({ url: '/business/news/collect/crawl', method: 'post', data: this.form }).then(() => { this.$modal.msgSuccess('采集任务已开始'); this.dialog = false; this.load() })
    },
    retry(id) { request({ url: '/business/news/collect/' + id + '/retry', method: 'post' }).then(() => this.load()) },
    viewTaskArticles(id) { this.viewingTaskId = id; request({ url: '/business/news/collect/' + id + '/articles', method: 'get' }).then(r => { this.taskArticles = r.data || []; this.articlesDialog = true }) },
    publishTask(row) {
      this.$set(this.batchPublishing, row.id, true)
      publishTaskNewsKnowledge(row.id).then(r => {
        const data = r.data || {}
        this.$modal.msgSuccess(`已处理${data.total || 0}条：新入库${data.submitted || 0}条，已存在${data.reused || 0}条，失败${data.failed || 0}条`)
        if (this.articlesDialog && this.viewingTaskId === row.id) this.viewTaskArticles(row.id)
      }).finally(() => this.$set(this.batchPublishing, row.id, false))
    },
    publishArticle(article) {
      this.$set(this.articlePublishing, article.articleId, true)
      publishArticleKnowledge(this.viewingTaskId, article.articleId).then(r => {
        const data = r.data || {}
        this.$modal.msgSuccess(data.reused ? '该新闻已在知识库中，已复用现有版本' : '新闻已提交知识库入库')
        this.viewTaskArticles(this.viewingTaskId)
      }).finally(() => this.$set(this.articlePublishing, article.articleId, false))
    },
    openDeleteArticles(id) { request({ url: '/business/news/collect/' + id + '/articles', method: 'get' }).then(r => { this.deletingTaskId = id; this.taskArticles = r.data || []; this.selectedArticles = []; this.deleteDialog = true }) },
    deleteSelectedArticles() { const articleIds = this.selectedArticles.map(item => item.articleId); if (!articleIds.length) return; this.$modal.confirm('确认移除选中的任务新闻？').then(() => request({ url: '/business/news/collect/' + this.deletingTaskId + '/articles/delete', method: 'post', data: { articleIds } })).then(() => { this.$modal.msgSuccess('已移除选中的任务新闻'); this.deleteDialog = false; this.viewTaskArticles(this.deletingTaskId); this.load() }) },
    showArticle(article) { this.selectedArticleId = article.articleId; this.selectedOperation = article.operation; this.selectedMysqlOperation = article.mysqlOperation; this.articleDrawer = true },
    dateRange(row) { return row.publishTimeStart || row.publishTimeEnd ? (row.publishTimeStart || '不限') + ' ~ ' + (row.publishTimeEnd || '不限') : '不限发布时间' }
  }
}
</script>
<style scoped>.source-select{width:300px}.empty-tip{padding:18px 0;text-align:center;color:#909399}.history-stat{color:#909399}.danger{color:#f56c6c}.delete-tip{margin-bottom:12px;color:#909399}</style>
