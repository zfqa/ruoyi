import request from '@/utils/request'

// 查询AI分析报告列表
export function listAiReport(query) {
  return request({
    url: '/business/report/list',
    method: 'get',
    params: query
  })
}

// 查询AI分析报告详细
export function getAiReport(id) {
  return request({
    url: '/business/report/' + id,
    method: 'get'
  })
}

// 按Excel解析任务查询自动生成的第一份报告
export function getAiReportByImportTask(importTaskId) {
  return request({
    url: '/business/report/by-import/' + importTaskId,
    method: 'get'
  })
}

// 新增AI分析报告
export function addAiReport(data) {
  return request({
    url: '/business/report',
    method: 'post',
    data: data
  })
}

// 修改AI分析报告
export function updateAiReport(data) {
  return request({
    url: '/business/report',
    method: 'put',
    data: data
  })
}

// 删除AI分析报告
export function delAiReport(id) {
  return request({
    url: '/business/report/' + id,
    method: 'delete'
  })
}

// 导出AI分析报告
export function exportAiReport(query) {
  return request({
    url: '/business/report/export',
    method: 'post',
    data: query,
    responseType: 'blob'
  })
}
