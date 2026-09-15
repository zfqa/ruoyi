<template>
  <el-table :data="rows" border stripe size="mini" :height="height" empty-text="暂无数据">
    <el-table-column v-for="column in columns" :key="column" :prop="column" :label="column" min-width="130" show-overflow-tooltip>
      <template slot-scope="scope">{{ formatCell(scope.row[column], column) }}</template>
    </el-table-column>
  </el-table>
</template>
<script>
export default {
  name: 'DynamicTable', props: { rows: { type: Array, default: () => [] }, height: { type: [String, Number], default: null } },
  computed: { columns() { const result = []; this.rows.slice(0, 20).forEach(row => Object.keys(row || {}).forEach(key => { if (!result.includes(key)) result.push(key) })); return result } },
  methods: {
    formatCell(value, column) {
      if (value == null || value === '') return '-'
      if (typeof value === 'boolean') return value ? '是' : '否'
      const number = typeof value === 'number' ? value : Number(value)
      const isRate = /占比|同比|增长率|增速|变化率/.test(column)
      if (isRate && Number.isFinite(number)) return `${(number * 100).toFixed(2)}%`
      if (typeof value === 'number' && Number.isFinite(value)) return value.toLocaleString('zh-CN', { maximumFractionDigits: 4 })
      return value
    }
  }
}
</script>
