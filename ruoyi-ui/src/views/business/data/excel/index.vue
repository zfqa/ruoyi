<template>
  <div class="app-container excel-entry">
    <market-import />
  </div>
</template>

<script>
import MarketImport from './market-import'

export default {
  // 动态菜单的路由名由 path=excel 生成为 Excel。必须与该名字一致，
  // 才会被全局 keep-alive 缓存；页签切换时即可保留解析现场。
  name: 'Excel',
  components: { MarketImport }
}
</script>

<style scoped>
.excel-entry { padding:16px; }
</style>
