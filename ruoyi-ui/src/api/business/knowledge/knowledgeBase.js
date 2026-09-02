import request from '@/utils/request'

// 查询固定知识库列表
export function listKnowledgeBase(query) {
  return request({
    url: '/business/knowledge/list',
    method: 'get',
    params: query
  })
}

// 查询固定知识库详细
export function getKnowledgeBase(id) {
  return request({
    url: '/business/knowledge/' + id,
    method: 'get'
  })
}

// 新增固定知识库
export function addKnowledgeBase(data) {
  return request({
    url: '/business/knowledge',
    method: 'post',
    data: data
  })
}

// 修改固定知识库
export function updateKnowledgeBase(data) {
  return request({
    url: '/business/knowledge',
    method: 'put',
    data: data
  })
}

// 删除固定知识库
export function delKnowledgeBase(id) {
  return request({
    url: '/business/knowledge/' + id,
    method: 'delete'
  })
}

// 导出固定知识库
export function exportKnowledgeBase(query) {
  return request({
    url: '/business/knowledge/export',
    method: 'post',
    data: query,
    responseType: 'blob'
  })
}

export function ingestPdf(sourceId, versionNo, file) {
  const data = new FormData()
  data.append('sourceId', sourceId)
  if (versionNo) data.append('versionNo', versionNo)
  data.append('file', file)
  return request({ url: '/business/knowledge/ingest/pdf', method: 'post', data })
}

export function ingestNews(data) {
  return request({ url: '/business/knowledge/ingest/news', method: 'post', data })
}

export function ingestReport(data) {
  return request({ url: '/business/knowledge/ingest/report', method: 'post', data })
}

export function getKnowledgeTask(id) {
  return request({ url: '/business/knowledge/task/' + id, method: 'get' })
}

export function listKnowledgeVersions(sourceId) {
  return request({ url: `/business/knowledge/${sourceId}/versions`, method: 'get' })
}

export function searchKnowledge(params) {
  return request({ url: '/business/knowledge/search', method: 'get', params })
}

export function askKnowledge(data) {
  return request({ url: '/business/knowledge/qa', method: 'post', data })
}
