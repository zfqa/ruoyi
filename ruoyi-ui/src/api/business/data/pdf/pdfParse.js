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
