import request from '@/utils/request'

/** 读取统一 AI/LLM 配置（含作用范围与当前超时） */
export function getAiConfig() {
  return request({ url: '/business/ai/config', method: 'get' })
}

/** 保存接口地址 / 模型 / API Key */
export function updateAiConfig(data) {
  return request({ url: '/business/ai/config', method: 'put', data })
}

/** 测试当前已保存配置的连通性 */
export function testAiConfig() {
  return request({ url: '/business/ai/config/test', method: 'post', timeout: 60000 })
}
