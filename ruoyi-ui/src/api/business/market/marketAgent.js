import request from '@/utils/request'

const base = '/business/market'

export function marketHealth() {
  return request({ url: `${base}/health`, method: 'get' })
}

export function llmStatus() {
  return request({ url: `${base}/llm/status`, method: 'get' })
}

export function testLlmConnection() {
  return request({ url: `${base}/llm/test`, method: 'post', data: {}, timeout: 120000 })
}

export function uploadMarketFiles(files) {
  const data = new FormData()
  files.forEach(file => data.append('files', file.raw || file))
  return request({
    url: `${base}/upload`, method: 'post', data,
    headers: { 'Content-Type': 'multipart/form-data', repeatSubmit: false }, timeout: 600000
  })
}

export function createMarketUploadJob(files) {
  const data = new FormData()
  files.forEach(file => data.append('files', file.raw || file))
  return request({ url: `${base}/upload/jobs`, method: 'post', data, headers: { 'Content-Type': 'multipart/form-data', repeatSubmit: false }, timeout: 120000 })
}

// Excel 导入模块专用：仅解析并返回预览结果，不创建整车市场分析任务，
// 也不会把解析结果交给整车市场分析页面。
export function createExcelParseJob(files) {
  const data = new FormData()
  files.forEach(file => data.append('files', file.raw || file))
  return request({ url: `${base}/parse/jobs`, method: 'post', data, headers: { 'Content-Type': 'multipart/form-data', repeatSubmit: false }, timeout: 120000 })
}

export function getExcelParseJob(jobId) {
  return request({ url: `${base}/parse/jobs/${encodeURIComponent(jobId)}`, method: 'get' })
}

export function cancelExcelParseJob(jobId) {
  return request({ url: `${base}/parse/jobs/${encodeURIComponent(jobId)}/cancel`, method: 'post', data: {} })
}

export function retryExcelParseJob(jobId) {
  return request({ url: `${base}/parse/jobs/${encodeURIComponent(jobId)}/retry`, method: 'post', data: {} })
}

export function getMarketUploadJob(jobId) {
  return request({ url: `${base}/upload/jobs/${encodeURIComponent(jobId)}`, method: 'get' })
}

export function cancelMarketUploadJob(jobId) {
  return request({ url: `${base}/upload/jobs/${encodeURIComponent(jobId)}/cancel`, method: 'post', data: {} })
}

export function retryMarketUploadJob(jobId) {
  return request({ url: `${base}/upload/jobs/${encodeURIComponent(jobId)}/retry`, method: 'post', data: {} })
}

export function getSheets(datasetId) {
  return request({ url: `${base}/sheets/${encodeURIComponent(datasetId)}`, method: 'get' })
}

export function getPeriodOptions(datasetId, params) {
  return request({ url: `${base}/period-options/${encodeURIComponent(datasetId)}`, method: 'get', params })
}

export function getMarketAnalysis(datasetId, params) {
  // Browser requests must pass through the Spring Boot gateway.  Its public
  // route is /analysis/{datasetId}; it transparently forwards sheet_name to
  // the Python service, where it is a strict Sheet data boundary.
  // Do not place Sheet names in an extra URL path segment: that path is not a
  // public gateway route and Spring would return "No static resource".
  return request({ url: `${base}/analysis/${encodeURIComponent(datasetId)}`, method: 'get', params, timeout: 120000 })
}

export function getDashboardComponents(datasetId, params) {
  // 大数据集需要重新聚合排行榜和趋势图，不能使用全局 10 秒超时。
  return request({ url: `${base}/dashboard-components/${encodeURIComponent(datasetId)}`, method: 'get', params, timeout: 120000 })
}

export function addContextText(datasetId, data) {
  return request({ url: `${base}/context/${encodeURIComponent(datasetId)}/text`, method: 'post', data })
}

/** 从固定知识库选择资料写入行业资料（category 须与知识库 sourceType 一致） */
export function addContextFromKnowledge(datasetId, data) {
  return request({
    url: `${base}/context/${encodeURIComponent(datasetId)}/from-knowledge`,
    method: 'post',
    data,
    timeout: 180000
  })
}

/** 行业资料页：列出可添加的固定知识库资料（已启用且已入库） */
export function listContextKnowledgeSources(params) {
  return request({ url: `${base}/context/knowledge-sources`, method: 'get', params })
}

export function updateContextCategory(datasetId, itemId, category) {
  return request({
    url: `${base}/context/${encodeURIComponent(datasetId)}/${encodeURIComponent(itemId)}/category`,
    method: 'put', data: { category }
  })
}

export function getContext(datasetId) {
  return request({ url: `${base}/context/${encodeURIComponent(datasetId)}`, method: 'get' })
}

export function clearContext(datasetId) {
  return request({ url: `${base}/context/${encodeURIComponent(datasetId)}`, method: 'delete' })
}

export function deleteContextItems(datasetId, itemIds) {
  return request({ url: `${base}/context/${encodeURIComponent(datasetId)}/delete-items`, method: 'post', data: { item_ids: itemIds } })
}

export function uploadContextFiles(datasetId, files) {
  const data = new FormData()
  files.forEach(file => data.append('files', file.raw || file))
  return request({
    url: `${base}/context/${encodeURIComponent(datasetId)}/upload`, method: 'post', data,
    headers: { 'Content-Type': 'multipart/form-data', repeatSubmit: false }, timeout: 600000
  })
}

export function askMarketAgent(data) {
  return request({ url: `${base}/chat`, method: 'post', data, timeout: 180000 })
}

export function getMarketReport(datasetId, params) {
  return request({ url: `${base}/report/${encodeURIComponent(datasetId)}`, method: 'get', params, timeout: 180000 })
}

/** 将当前周报写入 AI 分析报告并同步固定知识库（对齐车载分析） */
export function publishMarketReport(datasetId, params) {
  return request({ url: `${base}/report/${encodeURIComponent(datasetId)}/publish`, method: 'post', params, data: {}, timeout: 180000 })
}

export function exportMarketReport(datasetId, format, params) {
  return request({ url: `${base}/export/${encodeURIComponent(datasetId)}/${format}`, method: 'post', params, data: {}, timeout: 600000 })
}

export function downloadMarketReport(fileName) {
  return request({ url: `${base}/files/${encodeURIComponent(fileName)}`, method: 'get', responseType: 'blob', timeout: 120000 })
}

export function getReportPlan(datasetId) {
  return request({ url: `${base}/report-plan/${encodeURIComponent(datasetId)}`, method: 'get', timeout: 60000 })
}

export function updateReportPlan(datasetId, data) {
  return request({ url: `${base}/report-plan/${encodeURIComponent(datasetId)}/instruction`, method: 'post', data, timeout: 180000 })
}

export function saveVisualReportPlan(datasetId, data) {
  return request({ url: `${base}/report-plan/${encodeURIComponent(datasetId)}/visual`, method: 'put', data, timeout: 180000 })
}

export function resetReportPlan(datasetId) {
  return request({ url: `${base}/report-plan/${encodeURIComponent(datasetId)}/reset`, method: 'post', data: {} })
}

export function getReportConfig(datasetId) {
  return request({ url: `${base}/report-config/${encodeURIComponent(datasetId)}`, method: 'get', timeout: 60000 })
}

export function updateReportConfig(datasetId, data) {
  return request({ url: `${base}/report-config/${encodeURIComponent(datasetId)}`, method: 'put', data })
}

export function resetReportConfig(datasetId) {
  return request({ url: `${base}/report-config/${encodeURIComponent(datasetId)}/reset`, method: 'post', data: {} })
}

export function getDatasetDetail(datasetId) {
  return request({ url: `${base}/dataset/${encodeURIComponent(datasetId)}`, method: 'get' })
}

export function deleteMarketDataset(datasetId) {
  return request({ url: `${base}/dataset/${encodeURIComponent(datasetId)}`, method: 'delete' })
}
