<template>
  <section class="card table-card">
    <div class="table-card__head">
      <div class="title">
        <h2>指数行情总览</h2>
        <span class="count num">{{ view.length }} / {{ rows.length }}</span>
      </div>
      <div class="filters">
        <el-input
          v-model="keyword"
          class="search"
          placeholder="搜索代码或名称"
          clearable
          :prefix-icon="Search"
        />
        <el-select v-model="trendFilter" class="trend-select" placeholder="趋势">
          <el-option label="全部趋势" value="all" />
          <el-option label="多头排列" value="多头排列" />
          <el-option label="震荡" value="震荡" />
          <el-option label="空头排列" value="空头排列" />
        </el-select>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="view"
      :default-sort="{ prop: 'latestPctChg', order: 'descending' }"
      row-key="tsCode"
      style="width: 100%"
      @row-click="onRowClick"
    >
      <el-table-column label="指数" min-width="150" prop="tsCode" sortable>
        <template #default="{ row }">
          <div class="idx">
            <span class="idx__name">{{ indexName(row.tsCode) }}</span>
            <span class="idx__code num">{{ row.tsCode }}</span>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="最新收盘" prop="latestClose" min-width="110" align="right" sortable>
        <template #default="{ row }">
          <span class="num">{{ fmtNum(row.latestClose, 2) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="当日涨跌" prop="latestPctChg" min-width="108" align="right" sortable>
        <template #default="{ row }">
          <span class="num strong" :class="dirClass(row.latestPctChg)">{{ fmtPct(row.latestPctChg) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="近20日" prop="change20d" min-width="100" align="right" sortable>
        <template #default="{ row }">
          <span class="num" :class="dirClass(row.change20d)">{{ fmtPct(row.change20d) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="近60日" prop="change60d" min-width="100" align="right" sortable>
        <template #default="{ row }">
          <span class="num" :class="dirClass(row.change60d)">{{ fmtPct(row.change60d) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="MA20乖离" prop="bias20" min-width="110" align="right" sortable>
        <template #default="{ row }">
          <span class="num" :class="dirClass(row.bias20)">{{ fmtPct(row.bias20) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="趋势" prop="trend" min-width="110" align="center" sortable>
        <template #default="{ row }">
          <span class="tag" :class="trendTag(row.trend).cls">{{ trendTag(row.trend).text }}</span>
        </template>
      </el-table-column>

      <el-table-column label="60日走势" min-width="120" align="center">
        <template #default="{ row }">
          <Sparkline :data="row.spark" />
        </template>
      </el-table-column>

      <el-table-column label="操作" width="86" align="center" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click.stop="emit('select', row.tsCode)">详情</el-button>
        </template>
      </el-table-column>

      <template #empty>
        <div class="empty">
          <span class="empty__icon">📭</span>
          <span>暂无匹配的指数数据</span>
        </div>
      </template>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import Sparkline from './Sparkline.vue'
import { dirClass, fmtNum, fmtPct, indexName, trendTag } from '@/utils/format'
import type { OverviewRow } from '@/composables/useOverview'

const props = defineProps<{
  rows: OverviewRow[]
  loading?: boolean
}>()

const emit = defineEmits<{
  (e: 'select', code: string): void
}>()

const keyword = ref('')
const trendFilter = ref('all')

function onRowClick(row: OverviewRow) {
  emit('select', row.tsCode)
}

const view = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.rows.filter((r) => {
    if (trendFilter.value !== 'all' && r.trend !== trendFilter.value) return false
    if (!kw) return true
    return r.tsCode.toLowerCase().includes(kw) || indexName(r.tsCode).toLowerCase().includes(kw)
  })
})
</script>

<style scoped>
.table-card {
  padding: 16px 18px 6px;
}
.table-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.title {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.title h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.count {
  font-size: 12px;
  color: var(--text-3);
}
.filters {
  display: flex;
  gap: 10px;
}
.search {
  width: 200px;
}
.trend-select {
  width: 130px;
}

.idx {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}
.idx__name {
  font-weight: 600;
}
.idx__code {
  font-size: 12px;
  color: var(--text-3);
}

:deep(.el-table__row) {
  cursor: pointer;
}

@media (max-width: 768px) {
  .table-card {
    padding: 14px 12px 4px;
  }
  .search,
  .trend-select {
    width: 100%;
  }
  .filters {
    width: 100%;
  }
}
</style>
