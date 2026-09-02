import request from '@/utils/request'

// 查询车载显示分析列表
export function listDisplayAnalysis(query) {
  return request({
    url: '/business/analysis/display/list',
    method: 'get',
    params: query
  })
}

// 查询车载显示分析详细
export function getDisplayAnalysis(id) {
  return request({
    url: '/business/analysis/display/' + id,
    method: 'get'
  })
}

// 新增车载显示分析
export function addDisplayAnalysis(data) {
  return request({
    url: '/business/analysis/display',
    method: 'post',
    data: data
  })
}

// 修改车载显示分析
export function updateDisplayAnalysis(data) {
  return request({
    url: '/business/analysis/display',
    method: 'put',
    data: data
  })
}

// 删除车载显示分析
export function delDisplayAnalysis(id) {
  return request({
    url: '/business/analysis/display/' + id,
    method: 'delete'
  })
}

// 导出车载显示分析
export function exportDisplayAnalysis(query) {
  return request({
    url: '/business/analysis/display/export',
    method: 'post',
    data: query,
    responseType: 'blob'
  })
}
