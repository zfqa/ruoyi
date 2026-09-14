import request from '@/utils/request'

// 查询PDF解析列表
export function listPdfParse(query) {
  return request({
    url: '/business/data/pdf/list',
    method: 'get',
    params: query
  })
}

// 查询PDF解析详细
export function getPdfParse(id) {
  return request({
    url: '/business/data/pdf/' + id,
    method: 'get'
  })
}

// 新增PDF解析
export function addPdfParse(data) {
  return request({
    url: '/business/data/pdf',
    method: 'post',
    data: data
  })
}

// 上传 PDF/PPTX 并由若依后端调用 agent-service 完成解析
export function parsePdf(formData) {
  return request({
    url: '/business/data/pdf/parse',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// 将已保存的解析结果异步发布到若依知识库
export function publishPdfKnowledge(id) {
  return request({
    url: '/business/data/pdf/' + id + '/publish-knowledge',
    method: 'post'
  })
}

// 重新提交失败的原解析任务，不创建第二条业务任务记录
export function retryPdfParse(id) {
  return request({
    url: '/business/data/pdf/' + id + '/retry-parse',
    method: 'post'
  })
}

// 修改PDF解析
export function updatePdfParse(data) {
  return request({
    url: '/business/data/pdf',
    method: 'put',
    data: data
  })
}

// 删除PDF解析
export function delPdfParse(id) {
  return request({
    url: '/business/data/pdf/' + id,
    method: 'delete'
  })
}

// 导出PDF解析
export function exportPdfParse(query) {
  return request({
    url: '/business/data/pdf/export',
    method: 'post',
    data: query,
    responseType: 'blob'
  })
}
