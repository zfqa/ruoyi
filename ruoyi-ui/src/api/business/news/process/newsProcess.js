import request from '@/utils/request'

// 查询新闻处理列表
export function listNewsProcess(query) {
  return request({
    url: '/business/news/process/list',
    method: 'get',
    params: query
  })
}

// 查询新闻处理详细
export function getNewsProcess(id) {
  return request({
    url: '/business/news/process/' + id,
    method: 'get'
  })
}

// 新增新闻处理
export function addNewsProcess(data) {
  return request({
    url: '/business/news/process',
    method: 'post',
    data: data
  })
}

// 修改新闻处理
export function updateNewsProcess(data) {
  return request({
    url: '/business/news/process',
    method: 'put',
    data: data
  })
}

// 删除新闻处理
export function delNewsProcess(id) {
  return request({
    url: '/business/news/process/' + id,
    method: 'delete'
  })
}

// 导出新闻处理
export function exportNewsProcess(query) {
  return request({
    url: '/business/news/process/export',
    method: 'post',
    data: query,
    responseType: 'blob'
  })
}
