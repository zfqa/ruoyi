<template>
  <legacy-display />
</template>

<script>
import LegacyDisplay from './legacy-display'

export default {
  // 车载市场分析使用独立路由，直接呈现组长版完整车载解析页面。
  // 通用 Excel 导入仍由 business/data/excel/index.vue 单独承载。
  name: 'OnboardMarket',
  components: { LegacyDisplay }
}
</script>
