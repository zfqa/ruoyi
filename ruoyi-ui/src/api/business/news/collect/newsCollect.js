import request from '@/utils/request'

// 查询新闻采集列表
export function listNewsCollect(query) {
  return request({
    url: '/business/news/collect/list',
    method: 'get',
    params: query
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
