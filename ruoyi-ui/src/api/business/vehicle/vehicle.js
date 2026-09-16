import request from '@/utils/request'

export const listVehicleBrands = () => request({ url: '/business/vehicle/collect/brands', method: 'get' })
// 首次选择品牌会触发 Python 的实时发现；该请求必须长于 Java 的 600 秒上游等待，
// 但不影响其他普通页面接口的全局 10 秒超时。
export const listVehicleSeries = brand => request({ url: '/business/vehicle/collect/series', method: 'get', params: { brand }, timeout: 620000 })
export const createVehicleCollect = data => request({ url: '/business/vehicle/collect', method: 'post', data })
export const listVehicleCollect = query => request({ url: '/business/vehicle/collect/list', method: 'get', params: query })
export const retryVehicleCollect = id => request({ url: `/business/vehicle/collect/${id}/retry`, method: 'post' })
export const listVehicleCollectModels = id => request({ url: `/business/vehicle/collect/${id}/models`, method: 'get' })
export const publishVehicleCollect = id => request({ url: `/business/vehicle/collect/${id}/publish`, method: 'post' })
export const deleteVehicleCollectModels = (id, data) => request({ url: `/business/vehicle/collect/${id}/models/delete`, method: 'post', data })
export const exportVehicleCollect = id => request({ url: `/business/vehicle/collect/${id}/export`, method: 'get', responseType: 'blob' })
export const listVehicleModels = query => request({ url: '/business/vehicle/model/list', method: 'get', params: query })
export const getVehicleModel = id => request({ url: `/business/vehicle/model/${id}`, method: 'get' })
