<template>
  <section class="card compare">
    <div class="compare__head">
      <div class="title">
        <h2>多指数区间对比</h2>
        <span class="hint">以区间首日收盘归一化为 100，比较相对强弱</span>
      </div>
      <el-radio-group v-model="rangeDays" size="small">
        <el-radio-button :value="60">近60日</el-radio-button>
        <el-radio-button :value="120">近120日</el-radio-button>
        <el-radio-button :value="250">近250日</el-radio-button>
      </el-radio-group>
    </div>

    <div class="compare__picks">
      <el-checkbox
        v-for="c in codes"
        :key="c"
        :model-value="selected.includes(c)"
        @change="toggle(c)"
      >
        <span class="pick">
          <i class="dot" :style="{ background: colorOf(c) }" />
          {{ indexName(c) }}
        </span>
      </el-checkbox>
    </div>

    <div class="compare__chart" v-loading="loading">
      <div ref="container" class="compare__canvas" />
    </div>

    <div class="rank">
      <h3>区间涨幅排行</h3>
      <table class="rank__table">
        <thead>
          <tr>
            <th>排名</th>
            <th>指数</th>
            <th style="text-align: right">区间涨幅</th>
            <th style="text-align: right">区间最高</th>
            <th style="text-align: right">区间最低</th>
            <th style="text-align: right">振幅</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, i) in ranking" :key="r.code">
            <td class="num">{{ i + 1 }}</td>
            <td>
              <span class="pick">
                <i class="dot" :style="{ background: colorOf(r.code) }" />
                {{ indexName(r.code) }}
              </span>
            </td>
            <td class="num strong" :class="dirClass(r.change)" style="text-align: right">
              {{ fmtPct(r.change) }}
            </td>
            <td class="num" style="text-align: right">{{ fmtNum(r.high, 2) }}</td>
            <td class="num" style="text-align: right">{{ fmtNum(r.low, 2) }}</td>
            <td class="num" style="text-align: right">{{ fmtPct(r.amp) }}</td>
          </tr>
          <tr v-if="!ranking.length">
            <td colspan="6" class="rank__empty">请至少选择一个指数</td>
          </tr>
        </tbody>
      </table>
    </div>

    <p class="risk">
      风险提示：归一化对比仅反映历史相对表现，不代表未来走势；不构成任何投资建议。
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { quoteApi } from '@/api'
import { dirClass, fmtNum, fmtPct, indexName, shortDate } from '@/utils/format'

const props = defineProps<{
  codes: string[]
  theme: string
}>()

/** 固定的对比配色，与涨跌语义区分开 */
const PALETTE = ['#2f81f7', '#f0a020', '#a371f7', '#14b8a6', '#e5484d', '#8b949e']

const container = ref<HTMLElement | null>(null)
const rangeDays = ref(120)
const selected = ref<string[]>([])
const loading = ref(false)

interface Row {
  code: string
  dates: string[]
  norm: number[]
  change: number
  high: number
  low: number
  amp: number
}

const dataMap = ref<Record<string, Row>>({})
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null

function colorOf(code: string) {
  const i = props.codes.indexOf(code)
  return PALETTE[(i < 0 ? 0 : i) % PALETTE.length]
}

function toggle(code: string) {
  const i = selected.value.indexOf(code)
  if (i >= 0) selected.value.splice(i, 1)
  else selected.value.push(code)
}

async function load() {
  if (!selected.value.length) {
    dataMap.value = {}
    render()
    return
  }
  loading.value = true
  try {
    const results = await Promise.all(
      selected.value.map(async (code) => {
        const rows = (await quoteApi.recent(code, rangeDays.value)).slice().reverse()
        if (!rows.length) return null
        const closes = rows.map((r) => r.close)
        const first = closes[0] ?? 1
        const last = closes[closes.length - 1] ?? first
        const highs = rows.map((r) => r.high ?? r.close)
        const lows = rows.map((r) => r.low ?? r.close)
        const high = Math.max(...highs)
        const low = Math.min(...lows)
        const row: Row = {
          code,
          dates: rows.map((r) => shortDate(r.tradeDate)),
          norm: closes.map((c) => Number(((c / first) * 100).toFixed(2))),
          change: Number(((last / first - 1) * 100).toFixed(2)),
          high,
          low,
          amp: Number(((high / low - 1) * 100).toFixed(2)),
        }
        return row
      }),
    )
    const map: Record<string, Row> = {}
    results.forEach((r) => {
      if (r) map[r.code] = r
    })
    dataMap.value = map
    render()
  } finally {
    loading.value = false
  }
}

const ranking = computed(() =>
  Object.values(dataMap.value).sort((a, b) => b.change - a.change),
)

function render() {
  if (!chart) return
  const rows = Object.values(dataMap.value)
  const firstRow = rows[0]
  if (!firstRow) {
    chart.clear()
    return
  }
  const text3 = getComputedStyle(document.documentElement).getPropertyValue('--text-3').trim() || '#6b7684'
  const border = getComputedStyle(document.documentElement).getPropertyValue('--border').trim() || '#232c38'

  const dates = firstRow.dates
  chart.setOption(
    {
      animation: false,
      backgroundColor: 'transparent',
      grid: { left: 46, right: 18, top: 18, bottom: 34 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: getComputedStyle(document.documentElement).getPropertyValue('--card').trim() || '#161b22',
        borderColor: border,
        textStyle: { color: getComputedStyle(document.documentElement).getPropertyValue('--text').trim() || '#e6edf3', fontSize: 12 },
      },
      legend: { show: false },
      xAxis: {
        type: 'category',
        data: dates,
        boundaryGap: false,
        axisLine: { lineStyle: { color: border } },
        axisTick: { show: false },
        axisLabel: { color: text3, fontSize: 11 },
      },
      yAxis: {
        type: 'value',
        scale: true,
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: {
          color: text3,
          fontSize: 11,
          formatter: (v: number) => v.toFixed(0),
        },
        splitLine: { lineStyle: { color: border, type: 'dashed', opacity: 0.6 } },
      },
      series: rows.map((r) => ({
        name: indexName(r.code),
        type: 'line',
        data: r.norm,
        showSymbol: false,
        smooth: true,
        lineStyle: { width: 1.8, color: colorOf(r.code) },
        itemStyle: { color: colorOf(r.code) },
      })),
    },
    true,
  )
}

watch(rangeDays, load)
watch(selected, load, { deep: true })
watch(() => props.theme, render)
watch(() => props.codes, (codes) => {
  if (!selected.value.length && codes.length) selected.value = [...codes]
}, { immediate: true })

onMounted(() => {
  if (!container.value) return
  chart = echarts.init(container.value)
  render()
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(container.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.compare {
  padding: 16px 18px;
}
.compare__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
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
.compare__picks {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  margin-bottom: 12px;
}
.pick {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-2);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.compare__chart {
  position: relative;
  min-height: 340px;
}
.compare__canvas {
  width: 100%;
  height: 340px;
}

.rank {
  margin-top: 18px;
}
.rank h3 {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
}
.rank__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.rank__table th {
  text-align: left;
  font-weight: 600;
  color: var(--text-3);
  font-size: 12px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-soft);
}
.rank__table td {
  padding: 9px 10px;
  border-bottom: 1px solid var(--border);
  color: var(--text-2);
}
.rank__table tbody tr:hover td {
  background: var(--card-hover);
}
.strong {
  font-weight: 600;
}
.rank__empty {
  text-align: center;
  color: var(--text-3);
  padding: 20px;
}

.risk {
  margin: 14px 0 0;
  padding: 9px 12px;
  font-size: 12px;
  color: var(--warn);
  background: color-mix(in srgb, var(--warn) 10%, transparent);
  border-left: 2px solid var(--warn);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
}

@media (max-width: 768px) {
  .compare__canvas {
    height: 260px;
  }
  .rank__table {
    font-size: 12px;
  }
  .rank__table th:nth-child(4),
  .rank__table td:nth-child(4) {
    display: none;
  }
}
</style>
