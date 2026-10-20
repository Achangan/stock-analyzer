<template>
  <div class="cards">
    <template v-if="loading">
      <div v-for="i in 4" :key="i" class="card stat">
        <div class="skeleton" style="width: 42%; height: 13px" />
        <div class="skeleton" style="width: 68%; height: 26px; margin-top: 12px" />
        <div class="skeleton" style="width: 52%; height: 12px; margin-top: 10px" />
      </div>
    </template>

    <template v-else>
      <div class="card card--hover stat">
        <div class="stat__label">最新交易日</div>
        <div class="stat__value num">{{ latestDate ? isoDate(latestDate) : '—' }}</div>
        <div class="stat__sub">已入库指数 {{ rows.length }} 个</div>
      </div>

      <div class="card card--hover stat">
        <div class="stat__label">当日平均涨跌幅</div>
        <div class="stat__value num" :class="dirClass(avgPct)">{{ fmtPct(avgPct) }}</div>
        <div class="stat__sub">
          <template v-if="upCount + downCount > 0">
            其中 <span class="c-up num">上涨 {{ upCount }}</span> /
            <span class="c-down num">下跌 {{ downCount }}</span>
          </template>
          <template v-else>—</template>
        </div>
      </div>

      <div class="card card--hover stat">
        <div class="stat__label">均线趋势分布</div>
        <div class="stat__value" :class="dominant.cls">{{ dominant.text }}</div>
        <div class="stat__sub chips">
          <span class="tag tag--up">多头 {{ trendCounts.bull }}</span>
          <span class="tag tag--flat">震荡 {{ trendCounts.range }}</span>
          <span class="tag tag--down">空头 {{ trendCounts.bear }}</span>
        </div>
      </div>

      <div class="card card--hover stat">
        <div class="stat__label">20日年化波动率（均值）</div>
        <div class="stat__value num">{{ fmtNum(avgVol, 2) }}<span class="unit">%</span></div>
        <div class="stat__sub">
          <template v-if="strongest && weakest">
            最强 <span class="c-up">{{ indexName(strongest.tsCode) }}</span> ·
            最弱 <span class="c-down">{{ indexName(weakest.tsCode) }}</span>
          </template>
          <template v-else>—</template>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { dirClass, fmtNum, fmtPct, indexName, isoDate } from '@/utils/format'
import type { OverviewRow } from '@/composables/useOverview'

const props = defineProps<{
  rows: OverviewRow[]
  loading?: boolean
}>()

const latestDate = computed(() => props.rows[0]?.latestDate ?? '')

const valid = computed(() => props.rows.filter((r) => r.latestPctChg !== null))

const avgPct = computed(() => {
  if (!valid.value.length) return null
  return valid.value.reduce((s, r) => s + (r.latestPctChg as number), 0) / valid.value.length
})

const upCount = computed(() => valid.value.filter((r) => (r.latestPctChg as number) > 0).length)
const downCount = computed(() => valid.value.filter((r) => (r.latestPctChg as number) < 0).length)

const trendCounts = computed(() => ({
  bull: props.rows.filter((r) => r.trend === '多头排列').length,
  bear: props.rows.filter((r) => r.trend === '空头排列').length,
  range: props.rows.filter((r) => r.trend === '震荡').length,
}))

const dominant = computed(() => {
  const { bull, bear, range } = trendCounts.value
  if (!bull && !bear && !range) return { text: '—', cls: 'c-flat' }
  const max = Math.max(bull, bear, range)
  if (bull === max && bull > 0) return { text: `偏多 · ${bull} 个`, cls: 'c-up' }
  if (bear === max && bear > 0) return { text: `偏空 · ${bear} 个`, cls: 'c-down' }
  return { text: `震荡 · ${range} 个`, cls: 'c-flat' }
})

const avgVol = computed(() => {
  const v = props.rows.map((r) => r.volatility20d).filter((x): x is number => x !== null)
  if (!v.length) return null
  return v.reduce((s, x) => s + x, 0) / v.length
})

const strongest = computed(() => {
  if (!valid.value.length) return null
  return [...valid.value].sort((a, b) => (b.latestPctChg as number) - (a.latestPctChg as number))[0]
})

const weakest = computed(() => {
  if (!valid.value.length) return null
  return [...valid.value].sort((a, b) => (a.latestPctChg as number) - (b.latestPctChg as number))[0]
})
</script>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.stat {
  padding: 16px 18px;
}
.stat__label {
  font-size: 12px;
  color: var(--text-3);
  letter-spacing: 0.3px;
}
.stat__value {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 650;
  line-height: 1.25;
  font-variant-numeric: tabular-nums;
}
.stat__value .unit {
  margin-left: 2px;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-3);
}
.stat__sub {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
.stat__sub.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 9px;
}

@media (max-width: 1200px) {
  .cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 640px) {
  .cards {
    grid-template-columns: 1fr;
  }
}
</style>
