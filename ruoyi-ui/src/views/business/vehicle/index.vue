<template>
  <div class="app-container">
    <el-alert
      v-if="authReady === true"
      title="懂车帝登录态已就绪"
      type="success"
      :closable="false"
      show-icon
      :description="authMessage || '已检测到本地登录状态文件，可直接选择品牌/车系采集。若采集时提示登录失效，请重新执行人工登录。'"
      style="margin-bottom:12px"
    />
    <el-alert
      v-else-if="authReady === false"
      title="懂车帝采集需先完成人工登录"
      type="warning"
      :closable="false"
      show-icon
      :description="authMessage || '车系列表与参数采集依赖 Playwright 登录态。请在 agent-service 目录执行：python scripts/dongchedi_login.py，浏览器登录成功后按提示确认，生成 data/dongchedi_auth.json 后再试。'"
      style="margin-bottom:12px"
    />
    <el-alert
      v-else
      title="正在检查懂车帝登录态…"
      type="info"
      :closable="false"
      show-icon
      description="正在读取本地登录状态文件。"
      style="margin-bottom:12px"
    />
    <el-tabs v-model="active"><el-tab-pane label="采集任务" name="tasks"><el-form inline size="small"><el-form-item label="品牌"><el-select v-model="form.brandName" filterable clearable placeholder="请选择品牌" @change="loadSeries"><el-option v-for="item in brands" :key="item.name" :label="item.name" :value="item.name"/></el-select></el-form-item><el-form-item label="车系"><el-select v-model="form.seriesId" filterable clearable :loading="seriesLoading" :disabled="!form.brandName" :placeholder="seriesLoading ? '正在加载车系' : '请先选择品牌'"><el-option v-for="item in series" :key="item.seriesId" :label="item.seriesName" :value="item.seriesId"/></el-select></el-form-item><el-button v-hasPermi="['business:vehicle:collect:add']" type="primary" :disabled="!form.brandName||!form.seriesId" @click="collect">立即采集</el-button><el-button size="small" @click="loadAuthStatus">刷新登录态</el-button></el-form>
      <el-table :data="tasks" v-loading="taskLoading"><el-table-column prop="brandName" label="品牌"/><el-table-column prop="seriesName" label="车系"/><el-table-column prop="status" label="状态"><template slot-scope="s">{{ statusText(s.row.status) }}</template></el-table-column><el-table-column prop="fetchedCount" label="抓取"/><el-table-column prop="insertedCount" label="新增"/><el-table-column prop="updatedCount" label="更新"/><el-table-column prop="existingCount" label="已存在"/><el-table-column prop="failedCount" label="失败"/><el-table-column prop="startedTime" label="开始时间" width="170"/><el-table-column prop="completedTime" label="完成时间" width="170"/><el-table-column prop="errorMessage" label="错误"/><el-table-column label="操作" width="300"><template slot-scope="s"><el-button v-hasPermi="['business:vehicle:collect:query']" type="text" @click="viewTaskModels(s.row.id)">查看车型</el-button><el-button v-hasPermi="['business:vehicle:collect:add']" type="text" @click="publishTask(s.row.id)">入库</el-button><el-button v-hasPermi="['business:vehicle:collect:export']" type="text" @click="exportTask(s.row)">导出Excel</el-button><el-button v-hasPermi="['business:vehicle:collect:remove']" type="text" class="danger" @click="openDeleteModels(s.row.id)">删除</el-button><el-button v-if="s.row.status==='3'" v-hasPermi="['business:vehicle:collect:edit']" type="text" @click="retry(s.row.id)">重试</el-button></template></el-table-column></el-table></el-tab-pane>
      <el-tab-pane label="车型数据" name="models"><el-form inline size="small"><el-input v-model="modelQuery.brandName" placeholder="品牌" clearable/><el-input v-model="modelQuery.seriesName" placeholder="车系" clearable/><el-input v-model="modelQuery.modelName" placeholder="车型" clearable/><el-button type="primary" @click="loadModels">查询</el-button></el-form><el-table :data="models" v-loading="modelLoading"><el-table-column prop="brandName" label="品牌"/><el-table-column prop="seriesName" label="车系"/><el-table-column prop="modelName" label="车型" min-width="220"/><el-table-column prop="manufacturer" label="厂商"/><el-table-column prop="officialGuidePrice" label="官方指导价"/><el-table-column prop="level" label="级别"/><el-table-column prop="energyType" label="能源类型"/><el-table-column prop="lastCrawledTime" label="最后采集" width="170"/><el-table-column label="详情"><template slot-scope="s"><el-button v-hasPermi="['business:vehicle:model:query']" type="text" @click="showModel(s.row.id)">查看</el-button></template></el-table-column></el-table></el-tab-pane></el-tabs>
    <el-drawer title="车型详情" :visible.sync="drawer" size="55%"><pre class="json">{{ detail }}</pre></el-drawer>
    <el-dialog title="本任务车型" :visible.sync="taskModelsDialog" width="1100px"><el-table :data="taskModels" max-height="420"><el-table-column prop="seriesName" label="车系"/><el-table-column prop="modelName" label="车型" min-width="220"/><el-table-column prop="manufacturer" label="厂商"/><el-table-column prop="officialGuidePrice" label="官方指导价"><template slot-scope="s">{{ displayValue(s.row.officialGuidePrice) }}</template></el-table-column><el-table-column prop="level" label="级别"><template slot-scope="s">{{ displayValue(s.row.level) }}</template></el-table-column><el-table-column prop="energyType" label="能源类型"><template slot-scope="s">{{ displayValue(s.row.energyType) }}</template></el-table-column><el-table-column prop="instrumentScreenSizeInch" label="液晶仪表尺寸" width="110"><template slot-scope="s">{{ displayValue(s.row.instrumentScreenSizeInch) }}</template></el-table-column><el-table-column prop="centerScreenSizeInch" label="中控屏尺寸" width="110"><template slot-scope="s">{{ displayValue(s.row.centerScreenSizeInch) }}</template></el-table-column><el-table-column prop="centerScreenMaterial" label="中控屏幕材质" width="120"><template slot-scope="s">{{ displayValue(s.row.centerScreenMaterial) }}</template></el-table-column><el-table-column prop="passengerScreenSizeInch" label="副驾驶屏幕尺寸" width="120"><template slot-scope="s">{{ displayValue(s.row.passengerScreenSizeInch) }}</template></el-table-column><el-table-column prop="rearScreenSizeInch" label="后排屏幕尺寸" width="120"><template slot-scope="s">{{ displayValue(s.row.rearScreenSizeInch) }}</template></el-table-column></el-table><div v-if="!taskModels.length" class="empty-tip">本次任务没有可关联的车型。</div></el-dialog>
    <el-dialog title="移除本任务车型" :visible.sync="deleteModelsDialog" width="900px"><div class="delete-tip">仅移除本次任务与车型的关联，不会删除车型主数据或已入库的统一知识库内容。</div><el-table :data="taskModels" max-height="420" @selection-change="selectedTaskModels=$event"><el-table-column type="selection" width="55"/><el-table-column prop="seriesName" label="车系"/><el-table-column prop="modelName" label="车型" min-width="220"/><el-table-column prop="manufacturer" label="厂商"/><el-table-column prop="crawledAt" label="采集时间" width="180"/></el-table><span slot="footer"><el-button @click="deleteModelsDialog=false">取消</el-button><el-button type="danger" :disabled="!selectedTaskModels.length" @click="deleteSelectedTaskModels">移除选中车型</el-button></span></el-dialog>
  </div>
</template>
<script>
import { saveAs } from 'file-saver'
import { getVehicleAuthStatus, listVehicleBrands, listVehicleSeries, createVehicleCollect, listVehicleCollect, retryVehicleCollect, listVehicleCollectModels, publishVehicleCollect, deleteVehicleCollectModels, exportVehicleCollect, listVehicleModels, getVehicleModel } from '@/api/business/vehicle/vehicle'
export default {
  name: 'VehicleData',
  data() {
    return {
      active: 'tasks', brands: [], series: [], seriesLoading: false, seriesRequestSequence: 0,
      tasks: [], models: [], taskModels: [], selectedTaskModels: [], taskModelTaskId: null,
      form: { brandName: '', seriesId: '', seriesName: '' }, modelQuery: { pageNum: 1, pageSize: 20 },
      taskLoading: false, modelLoading: false, drawer: false, taskModelsDialog: false, deleteModelsDialog: false,
      detail: '', authReady: null, authMessage: ''
    }
  },
  created() { this.loadAuthStatus(); this.loadBrands(); this.loadTasks(); this.loadModels() },
  beforeDestroy() { clearInterval(this.timer) },
  methods: {
    async loadAuthStatus() {
      try {
        const r = await getVehicleAuthStatus()
        const data = r.data || {}
        this.authReady = !!data.ready
        this.authMessage = data.message || ''
      } catch (error) {
        this.authReady = false
        this.authMessage = (error && (error.msg || error.message)) || '无法检查懂车帝登录态，请确认采集服务已启动。'
      }
    },
    async loadBrands() { const r = await listVehicleBrands(); this.brands = r.data || [] },
    async loadSeries(brand) {
      const selectedBrand = brand || this.form.brandName
      const requestSequence = ++this.seriesRequestSequence
      this.form.seriesId = ''
      this.form.seriesName = ''
      this.series = []
      if (!selectedBrand) { this.seriesLoading = false; return }
      this.seriesLoading = true
      try {
        const r = await listVehicleSeries(selectedBrand)
        if (requestSequence !== this.seriesRequestSequence || this.form.brandName !== selectedBrand) return
        this.series = Array.isArray(r.data) ? r.data : []
        await this.loadAuthStatus()
      } catch (error) {
        if (requestSequence !== this.seriesRequestSequence || this.form.brandName !== selectedBrand) return
        this.series = []
        const msg = (error && (error.msg || error.message)) || '车系列表获取失败，请稍后重试'
        this.$modal.msgError(msg)
        await this.loadAuthStatus()
      } finally {
        if (requestSequence === this.seriesRequestSequence && this.form.brandName === selectedBrand) this.seriesLoading = false
      }
    },
    selectedSeries() { return this.series.find(x => x.seriesId === this.form.seriesId) },
    async collect() {
      const s = this.selectedSeries()
      if (!s) return
      await createVehicleCollect({ brandName: this.form.brandName, seriesId: s.seriesId, seriesName: s.seriesName })
      this.$modal.msgSuccess('采集任务已开始')
      this.loadTasks()
    },
    async loadTasks() {
      this.taskLoading = true
      try {
        const r = await listVehicleCollect({ pageNum: 1, pageSize: 50 })
        this.tasks = r.rows || []
        this.refreshTimer()
      } finally { this.taskLoading = false }
    },
    refreshTimer() {
      clearInterval(this.timer)
      if (this.tasks.some(x => x.status === '1')) this.timer = setInterval(() => this.loadTasks(), 4000)
    },
    async retry(id) { await retryVehicleCollect(id); this.$modal.msgSuccess('已重新进入采集队列'); this.loadTasks() },
    async viewTaskModels(id) { const r = await listVehicleCollectModels(id); this.taskModels = r.data || []; this.taskModelsDialog = true },
    async openDeleteModels(id) { const r = await listVehicleCollectModels(id); this.taskModels = r.data || []; this.selectedTaskModels = []; this.taskModelTaskId = id; this.deleteModelsDialog = true },
    async deleteSelectedTaskModels() {
      const modelIds = this.selectedTaskModels.map(item => item.modelId)
      if (!modelIds.length) return
      await this.$modal.confirm('确认移除选中的任务车型？')
      await deleteVehicleCollectModels(this.taskModelTaskId, { modelIds })
      this.$modal.msgSuccess('已移除选中的任务车型')
      this.deleteModelsDialog = false
      this.loadTasks()
    },
    async publishTask(id) {
      const r = await publishVehicleCollect(id)
      this.$modal.msgSuccess(`入库完成：提交 ${r.data.submitted}，复用 ${r.data.reused}，失败 ${r.data.failed}`)
    },
    async exportTask(task) {
      const blob = await exportVehicleCollect(task.id)
      if (blob.type && blob.type.includes('application/json')) {
        const error = JSON.parse(await blob.text())
        this.$modal.msgError(error.msg || '车辆导出失败')
        return
      }
      saveAs(new Blob([blob], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), `懂车帝_${task.brandName}_${task.seriesName}.xlsx`)
    },
    async loadModels() {
      this.modelLoading = true
      try { const r = await listVehicleModels(this.modelQuery); this.models = r.rows || [] }
      finally { this.modelLoading = false }
    },
    async showModel(id) { const r = await getVehicleModel(id); this.detail = JSON.stringify(r.data, null, 2); this.drawer = true },
    displayValue(v) { return v === null || v === undefined || v === '' ? '--' : v },
    statusText(v) { return ({ '1': '处理中', '2': '成功', '3': '失败' })[v] || v }
  }
}
</script>
<style scoped>.json{white-space:pre-wrap;word-break:break-word;padding:0 18px}.danger{color:#f56c6c}.empty-tip{padding:18px 0;text-align:center;color:#909399}.delete-tip{margin-bottom:12px;color:#909399}</style>
