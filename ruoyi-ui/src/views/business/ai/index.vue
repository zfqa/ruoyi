<template>
  <div class="app-container ai-config">
    <el-alert
      title="本页维护系统共用的大模型凭证。整车市场分析、车载分析、知识问答、文本结构化与文档语义解析均使用此处配置。"
      type="info"
      :closable="false"
      show-icon
      class="mb16"
    />

    <el-row :gutter="16">
      <el-col :md="14" :xs="24">
        <el-card shadow="never">
          <div slot="header" class="card-head">
            <span>模型连接</span>
            <el-tag size="mini" :type="form.apiKeyConfigured ? 'success' : 'warning'">
              {{ form.apiKeyConfigured ? 'API Key 已配置' : 'API Key 未配置' }}
            </el-tag>
          </div>
          <el-form ref="form" :model="form" label-width="110px" size="small">
            <el-form-item label="请求地址" required>
              <el-input v-model="form.apiUrl" placeholder="https://api.deepseek.com/v1/chat/completions" />
            </el-form-item>
            <el-form-item label="模型名称" required>
              <el-input v-model="form.model" placeholder="deepseek-chat" />
            </el-form-item>
            <el-form-item label="API Key">
              <el-input
                v-model="form.apiKey"
                type="password"
                show-password
                autocomplete="new-password"
                :placeholder="form.apiKeyConfigured ? '已配置；留空表示保持不变' : '请输入 API Key'"
              />
            </el-form-item>
            <el-form-item>
              <el-checkbox v-model="form.clearApiKey">清除当前 API Key</el-checkbox>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" v-hasPermi="['business:ai:config:edit']" @click="save">保存</el-button>
              <el-button :loading="testing" v-hasPermi="['business:ai:config:edit']" @click="test">测试连接</el-button>
              <el-button icon="el-icon-refresh" :loading="loading" @click="load">刷新</el-button>
            </el-form-item>
          </el-form>
          <el-alert
            v-for="(note, index) in notes"
            :key="index"
            :title="note"
            type="warning"
            :closable="false"
            show-icon
            class="note-item"
          />
        </el-card>
      </el-col>

      <el-col :md="10" :xs="24">
        <el-card shadow="never" class="mb16">
          <div slot="header">作用范围</div>
          <el-table :data="appliesTo" size="mini" border>
            <el-table-column prop="name" label="模块" min-width="120" />
            <el-table-column prop="usage" label="用途" min-width="180" show-overflow-tooltip />
          </el-table>
        </el-card>

        <el-card shadow="never" class="mb16">
          <div slot="header">
            <span>功能超时（只读）</span>
            <el-tag size="mini" type="info">yml / 环境变量</el-tag>
          </div>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="文本结构化超时">{{ featureLimits.textTimeoutSeconds }} 秒</el-descriptions-item>
            <el-descriptions-item label="知识问答超时">{{ featureLimits.knowledgeTimeoutSeconds }} 秒</el-descriptions-item>
            <el-descriptions-item label="车载表结构超时">{{ featureLimits.excelTableTimeoutSeconds }} 秒</el-descriptions-item>
            <el-descriptions-item label="车载报告超时">{{ featureLimits.excelReportTimeoutSeconds }} 秒</el-descriptions-item>
            <el-descriptions-item label="车载最大重试">{{ featureLimits.excelMaxRetries }}</el-descriptions-item>
            <el-descriptions-item label="车载表 LLM 调用上限">{{ featureLimits.excelMaxTableCalls }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card shadow="never">
          <div slot="header">
            <span>侧车服务（只读）</span>
            <el-tag size="mini" type="info">yml / 环境变量</el-tag>
          </div>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="market-agent">{{ sidecars.marketAgentBaseUrl || '-' }}</el-descriptions-item>
            <el-descriptions-item label="agent-service">{{ sidecars.agentServiceBaseUrl || '-' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import { getAiConfig, updateAiConfig, testAiConfig } from '@/api/business/ai/aiConfig'

export default {
  name: 'AiConfig',
  data() {
    return {
      loading: false,
      saving: false,
      testing: false,
      form: {
        apiUrl: '',
        model: '',
        apiKey: '',
        apiKeyConfigured: false,
        clearApiKey: false
      },
      appliesTo: [],
      featureLimits: {},
      sidecars: {},
      notes: []
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      return getAiConfig().then(res => {
        const data = res.data || {}
        this.form = {
          apiUrl: data.apiUrl || '',
          model: data.model || '',
          apiKey: '',
          apiKeyConfigured: !!data.apiKeyConfigured,
          clearApiKey: false
        }
        this.appliesTo = data.appliesTo || []
        this.featureLimits = data.featureLimits || {}
        this.sidecars = data.sidecars || {}
        this.notes = data.notes || []
      }).finally(() => {
        this.loading = false
      })
    },
    save() {
      if (!this.form.apiUrl || !this.form.model) {
        return this.$modal.msgError('请求地址和模型名称不能为空')
      }
      this.saving = true
      updateAiConfig(this.form).then(res => {
        const data = res.data || {}
        this.form = {
          apiUrl: data.apiUrl || this.form.apiUrl,
          model: data.model || this.form.model,
          apiKey: '',
          apiKeyConfigured: !!data.apiKeyConfigured,
          clearApiKey: false
        }
        this.appliesTo = data.appliesTo || this.appliesTo
        this.featureLimits = data.featureLimits || this.featureLimits
        this.sidecars = data.sidecars || this.sidecars
        this.$modal.msgSuccess('AI 配置已保存，服务重启后仍生效')
      }).finally(() => {
        this.saving = false
      })
    },
    test() {
      this.testing = true
      testAiConfig().then(res => {
        const data = res.data || {}
        this.$modal.msgSuccess(`连接成功（模型 ${data.model || this.form.model}，耗时 ${data.durationMs || '-'} ms）`)
      }).finally(() => {
        this.testing = false
      })
    }
  }
}
</script>

<style scoped>
.ai-config .mb16 { margin-bottom: 16px; }
.ai-config .card-head { display: flex; align-items: center; justify-content: space-between; }
.ai-config .note-item { margin-top: 10px; }
</style>
