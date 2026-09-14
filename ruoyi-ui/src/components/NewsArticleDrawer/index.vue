<template>
  <el-drawer :visible="visible" :with-header="false" size="58%" append-to-body @close="$emit('update:visible', false)">
    <div class="drawer-body" v-loading="loading">
      <h3>{{ article.title || '新闻详情' }}</h3>
      <el-descriptions v-if="article.id" :column="2" border size="small" class="meta">
        <el-descriptions-item label="来源">{{ article.sourceName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="来源站点">{{ article.sourceSite || '—' }}</el-descriptions-item>
        <el-descriptions-item label="发布时间">{{ article.publishedAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="采集时间">{{ article.crawledAt || '—' }}</el-descriptions-item>
        <el-descriptions-item v-if="operation" label="采集操作">{{ operation }}</el-descriptions-item>
        <el-descriptions-item v-if="mysqlOperation" label="主库结果">{{ mysqlOperation }}</el-descriptions-item>
      </el-descriptions>
      <div class="content-title">正文</div>
      <div v-if="article.content" class="content">{{ article.content }}</div>
      <div v-else-if="article.id" class="empty-content">暂无正文内容，请查看原文</div>
      <div class="source-link" v-if="sourceUrl"><el-link :href="sourceUrl" target="_blank" type="primary">查看原文</el-link></div>
    </div>
  </el-drawer>
</template>

<script>
import request from '@/utils/request'
export default {
  name: 'NewsArticleDrawer',
  props: { visible: Boolean, articleId: [Number, String], operation: String, mysqlOperation: String },
  data () { return { loading: false, article: {} } },
  computed: {
    sourceUrl () {
      const url = this.article.canonicalUrl || this.article.originalUrl || this.article.url
      return /^https?:\/\//i.test(url || '') ? url : ''
    }
  },
  watch: {
    visible (value) { if (value) this.load() },
    articleId () { if (this.visible) this.load() }
  },
  methods: {
    load () {
      if (!this.articleId) return
      this.loading = true
      request({ url: '/business/news/article/' + this.articleId, method: 'get' })
        .then(response => { this.article = response.data || {} })
        .finally(() => { this.loading = false })
    }
  }
}
</script>

<style scoped>
.drawer-body{height:100%;padding:24px;overflow:auto}.meta{margin:16px 0}.content-title{margin:20px 0 8px;font-weight:600}.content{max-height:calc(100vh - 360px);overflow:auto;white-space:pre-wrap;word-break:break-word;line-height:1.75;color:#303133}.empty-content{padding:20px 0;color:#909399}.source-link{margin-top:18px}
</style>


