<template>
  <section class="card coverage">
    <div class="coverage__head">
      <div class="title">
        <h2>数据覆盖度</h2>
        <span class="hint">来自 quote-service /coverage，用于确认回填完整性</span>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="summary" v-if="rows.length">
      <div class="summary__item">
        <span class="summary__label">指数数量</span>
        <span class="summary__value num">{{ rows.length }}</span>
      </div>
      <div class="summary__item">
        <span class="summary__label">记录总数</span>
        <span class="summary__value num">{{ fmtInt(totalRows) }}</span>
      </div>
      <div class="summary__item">
        <span class="summary__label">最早交易日</span>
        <span class="summary__value num">{{ isoDate(minDate) }}</span>
      </div>
      <div class="summary__item">
        <span class="summary__label">最新交易日</span>
        <span class="summary__value num">{{ isoDate(maxDate) }}</span>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" style="width: 100%">
      <el-table-column label="指数" min-width="140">
        <template #default="{ row }">
          <div class="idx">
            <span class="idx__name">{{ indexName(row.tsCode) }}</span>
            <span class="idx__code num">{{ row.tsCode }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="起始日" min-width="120">
        <template #default="{ row }">
          <span class="num">{{ isoDate(row.startDate) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="最新日" min-width="120">
        <template #default="{ row }">
          <span class="num">{{ isoDate(row.endDate) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="交易日数" prop="rows" min-width="110" align="right" sortable>
        <template #default="{ row }">
          <span class="num">{{ fmtInt(row.rows) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="完整性" min-width="180">
        <template #default="{ row }">
          <div class="bar">
            <div class="bar__fill" :style="{ width: `${(row.rows / maxRows) * 100}%` }" />
            <span class="bar__text num">{{ ((row.rows / maxRows) * 100).toFixed(0) }}%</span>
          </div>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty">
          <span class="empty__icon">🗄️</span>
          <span>暂无覆盖数据，请先运行采集脚本入库</span>
        </div>
      </template>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { quoteApi, type IndexCoverage } from '@/api'
import { fmtInt, indexName, isoDate } from '@/utils/format'

const rows = ref<IndexCoverage[]>([])
const loading = ref(false)

const maxRows = computed(() => Math.max(1, ...rows.value.map((r) => r.rows)))
const totalRows = computed(() => rows.value.reduce((s, r) => s + r.rows, 0))
const minDate = computed(() => rows.value.map((r) => r.startDate).sort()[0] ?? '')
const maxDate = computed(() => rows.value.map((r) => r.endDate).sort().slice(-1)[0] ?? '')

async function load() {
  loading.value = true
  try {
    rows.value = await quoteApi.coverage()
  } catch {
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.coverage {
  padding: 16px 18px 6px;
}
.coverage__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}
.title h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.hint {
  font-size: 12px;
  color: var(--text-3);
}

.summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  background: var(--border);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
  margin-bottom: 14px;
}
.summary__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 11px 13px;
  background: var(--bg-soft);
}
.summary__label {
  font-size: 12px;
  color: var(--text-3);
}
.summary__value {
  font-size: 16px;
  font-weight: 600;
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

.bar {
  position: relative;
  height: 20px;
  border-radius: 6px;
  background: var(--bg-soft);
  overflow: hidden;
}
.bar__fill {
  height: 100%;
  background: linear-gradient(90deg, var(--accent), #14b8a6);
  opacity: 0.85;
  transition: width 0.4s ease;
}
.bar__text {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  font-size: 12px;
  color: var(--text);
}

@media (max-width: 900px) {
  .summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
