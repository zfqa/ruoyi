import request from '@/utils/request'

// 查询整车市场分析列表
export function listVehicleAnalysis(query) {
  return request({
    url: '/business/analysis/vehicle/list',
    method: 'get',
    params: query
  })
}

// 查询整车市场分析详细
export function getVehicleAnalysis(id) {
  return request({
    url: '/business/analysis/vehicle/' + id,
    method: 'get'
  })
}

// 新增整车市场分析
export function addVehicleAnalysis(data) {
  return request({
    url: '/business/analysis/vehicle',
    method: 'post',
    data: data
  })
}

// 修改整车市场分析
export function updateVehicleAnalysis(data) {
  return request({
    url: '/business/analysis/vehicle',
    method: 'put',
    data: data
  })
}

// 删除整车市场分析
export function delVehicleAnalysis(id) {
  return request({
    url: '/business/analysis/vehicle/' + id,
    method: 'delete'
  })
}

// 导出整车市场分析
export function exportVehicleAnalysis(query) {
  return request({
    url: '/business/analysis/vehicle/export',
    method: 'post',
    data: query,
    responseType: 'blob'
  })
}
