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
