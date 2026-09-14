import request from '@/utils/request'

// 查询新闻采集列表
export function listNewsCollect(query) {
  return request({
    url: '/business/news/collect/list',
    method: 'get',
    params: query
  })
}
// 当前允许新建采集任务的来源（仅 enabled=true）
export function listCollectableNewsSources() {
  return request({
    url: '/business/news/collect/sources',
    method: 'get'
  })
}

// 历史任务来源，包含后来已停用的来源，仅用于筛选历史记录
export function listHistoricalNewsSources() {
  return request({
    url: '/business/news/collect/options/history-sources',
    method: 'get'
  })
}

// 将一次采集任务关联的全部新闻发布到统一知识库
export function publishTaskNewsKnowledge(taskId) {
  return request({
    url: '/business/news/collect/' + taskId + '/knowledge',
    method: 'post'
  })
}

// 将一次采集任务中的单条新闻发布到统一知识库
export function publishArticleKnowledge(taskId, articleId) {
  return request({
    url: '/business/news/collect/' + taskId + '/articles/' + articleId + '/knowledge',
    method: 'post'
  })
}

// 查询新闻采集详细
export function getNewsCollect(id) {
  return request({
    url: '/business/news/collect/' + id,
    method: 'get'
  })
}

// 新增新闻采集
export function addNewsCollect(data) {
  return request({
    url: '/business/news/collect',
    method: 'post',
    data: data
  })
}

// 修改新闻采集
export function updateNewsCollect(data) {
  return request({
    url: '/business/news/collect',
    method: 'put',
    data: data
  })
}

// 删除新闻采集
export function delNewsCollect(id) {
  return request({
    url: '/business/news/collect/' + id,
    method: 'delete'
  })
}

// 导出新闻采集
export function exportNewsCollect(query) {
  return request({
    url: '/business/news/collect/export',
    method: 'post',
    data: query,
    responseType: 'blob'
  })
}
