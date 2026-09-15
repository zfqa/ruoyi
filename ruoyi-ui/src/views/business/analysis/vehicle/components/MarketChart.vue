<template><div class="market-chart" /></template>
<script>
import * as echarts from 'echarts'
export default {
  name: 'MarketChart',
  props: {
    option: { type: Object, required: true },
    height: { type: String, default: '360px' }
  },
  data() {
    return {
      chart: null,
      resizeObserver: null,
      resizeFrame: null
    }
  },
  watch: {
    option: {
      deep: true,
      handler() { this.scheduleRender() }
    },
    height(value) {
      this.$el.style.height = value
      this.scheduleRender()
    }
  },
  mounted() {
    this.$el.style.height = this.height
    window.addEventListener('resize', this.scheduleRender)

    // el-tab-pane 会先以 display:none 渲染。隐藏时初始化 ECharts 会把画布
    // 固定成极小宽度，因此等容器真正可见后再初始化，并持续观察布局变化。
    if (typeof ResizeObserver !== 'undefined') {
      this.resizeObserver = new ResizeObserver(() => this.scheduleRender())
      this.resizeObserver.observe(this.$el)
    }
    this.scheduleRender()
  },
  activated() {
    this.scheduleRender()
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.scheduleRender)
    if (this.resizeObserver) this.resizeObserver.disconnect()
    if (this.resizeFrame) cancelAnimationFrame(this.resizeFrame)
    if (this.chart) this.chart.dispose()
  },
  methods: {
    scheduleRender() {
      if (this.resizeFrame) cancelAnimationFrame(this.resizeFrame)
      this.resizeFrame = requestAnimationFrame(() => {
        this.resizeFrame = null
        this.render()
      })
    },
    render() {
      const rect = this.$el.getBoundingClientRect()
      if (rect.width < 2 || rect.height < 2) return
      if (!this.chart) this.chart = echarts.init(this.$el)
      this.chart.resize()
      this.chart.setOption(this.option || {}, true)
    },
    resize() {
      this.scheduleRender()
    }
  }
}
</script>
<style scoped>
.market-chart { display:block;width:100%;min-width:0; }
</style>
