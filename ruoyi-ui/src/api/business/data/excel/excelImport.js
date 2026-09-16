import request from '@/utils/request'

// 查询Excel导入列表
export function listExcelImport(query) {
  return request({
    url: '/business/data/excel/list',
    method: 'get',
    params: query
  })
}

// 查询Excel导入详细
export function getExcelImport(id) {
  return request({
    url: '/business/data/excel/' + id,
    method: 'get'
  })
}

// 查询Excel解析结果
export function getExcelImportResult(id) {
  return request({
    url: '/business/data/excel/result/' + id,
    method: 'get'
  })
}

// 查询Excel解析任务状态
export function getExcelImportStatus(id) {
  return request({
    url: '/business/data/excel/status/' + id,
    method: 'get'
  })
}

// 新增Excel导入
export function addExcelImport(data) {
  return request({
    url: '/business/data/excel',
    method: 'post',
    data: data
  })
}

// 修改Excel导入
export function updateExcelImport(data) {
  return request({
    url: '/business/data/excel',
    method: 'put',
    data: data
  })
}

// 删除Excel导入
export function delExcelImport(id) {
  return request({
    url: '/business/data/excel/' + id,
    method: 'delete'
  })
}

// 导出Excel导入
export function exportExcelImport(query) {
  return request({
    url: '/business/data/excel/export',
    method: 'post',
    data: query,
    responseType: 'blob'
  })
}

// 上传Excel文件
export function uploadExcelImport(file) {
  return request({
    url: '/business/data/excel/upload',
    method: 'post',
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    data: file,
    timeout: 120000
  })
}

// 解析本地Excel文件
export function parseLocalExcel(filePath, options = {}) {
  return request({
    url: '/business/data/excel/parse-local',
    method: 'post',
    data: { filePath, ...options }
  })
}

// 解析已上传Excel文件
export function parseUploadExcel(fileName, options = {}) {
  return request({
    url: '/business/data/excel/parse-upload',
    method: 'post',
    data: { fileName, ...options }
  })
}
