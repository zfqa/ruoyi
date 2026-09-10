import request from '@/utils/request'

// 提交自由文本LLM实体抽取任务
export function extractTextStruct(data) {
  return request({
    url: '/business/data/text/extract',
    method: 'post',
    data
  })
}

// 查询文本结构化列表
export function listTextStruct(query) {
  return request({
    url: '/business/data/text/list',
    method: 'get',
    params: query
  })
}

// 查询文本结构化详细
export function getTextStruct(id) {
  return request({
    url: '/business/data/text/' + id,
    method: 'get'
  })
}

// 新增文本结构化
export function addTextStruct(data) {
  return request({
    url: '/business/data/text',
    method: 'post',
    data: data
  })
}

// 修改文本结构化
export function updateTextStruct(data) {
  return request({
    url: '/business/data/text',
    method: 'put',
    data: data
  })
}

// 删除文本结构化
export function delTextStruct(id) {
  return request({
    url: '/business/data/text/' + id,
    method: 'delete'
  })
}

// 导出文本结构化
export function exportTextStruct(query) {
  return request({
    url: '/business/data/text/export',
    method: 'post',
    data: query,
    responseType: 'blob'
  })
}
