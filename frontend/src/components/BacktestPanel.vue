<template>
  <section class="card bt">
    <div class="bt__head">
      <div class="title">
        <h2>策略回测</h2>
        <span class="hint">双均线金叉买入 / 死叉卖出，收盘价成交，未计手续费与滑点</span>
      </div>
    </div>

    <div class="form">
      <el-select v-model="form.tsCode" class="form__item" placeholder="指数">
        <el-option v-for="c in codes" :key="c" :label="indexName(c)" :value="c" />
      </el-select>
      <el-input-number v-model="form.fast" :min="2" :max="60" class="form__num" />
      <span class="form__sep">快 / 慢线</span>
      <el-input-number v-model="form.slow" :min="5" :max="250" class="form__num" />
      <el-date-picker
        v-model="range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        class="form__range"
      />
      <el-button type="primary" :loading="loading" :icon="VideoPlay" @click="run">开始回测</el-button>
    </div>

    <div v-if="result" class="metrics">
      <div class="metric">
        <span class="metric__label">策略收益</span>
        <span class="num metric__value" :class="dirClass(result.totalReturn)">{{ fmtPct(result.totalReturn) }}</span>
      </div>
      <div class="metric">
        <span class="metric__label">买入持有</span>
        <span class="num metric__value" :class="dirClass(result.benchmarkReturn)">{{ fmtPct(result.benchmarkReturn) }}</span>
      </div>
      <div class="metric">
        <span class="metric__label">最大回撤</span>
        <span class="num metric__value c-down">{{ fmtPct(result.maxDrawdown) }}</span>
      </div>
      <div class="metric">
        <span class="metric__label">胜率</span>
        <span class="num metric__value">{{ fmtPct(result.winRate) }}</span>
      </div>
      <div class="metric">
        <span class="metric__label">交易次数</span>
        <span class="num metric__value">{{ result.trades }}</span>
      </div>
      <div class="metric">
        <span class="metric__label">区间</span>
        <span class="num metric__value metric__value--sm">{{ result.startDate }} ~ {{ result.endDate }}</span>
      </div>
    </div>

    <div v-show="result" ref="chartRef" class="chart" />

    <div v-if="!result && !loading" class="empty">
      <span class="empty__icon">📈</span>
      <span>选择指数与均线参数，点击「开始回测」</span>
    </div>

    <p v-if="result" class="risk">{{ result.disclaimer }}</p>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { VideoPlay } from '@element-plus/icons-vue'
import { backtestApi, quoteApi, type BacktestResult } from '@/api'
import { dirClass, fmtPct, indexName } from '@/utils/format'

const codes = ref<string[]>([])
const form = reactive({ tsCode: '000300.SH', fast: 5, slow: 20 })
const range = ref<[string, string] | null>(null)
const result = ref<BacktestResult | null>(null)
const loading = ref(false)

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let ro: ResizeObserver | null = null

async function run() {
  loading.value = true
  try {
    result.value = await backtestApi.maCross({
      tsCode: form.tsCode,
      fast: form.fast,
      slow: form.slow,
      start: range.value?.[0],
      end: range.value?.[1],
    })
    requestAnimationFrame(() => renderChart())
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function cssVar(name: string) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

function renderChart() {
  if (!chartRef.value || !result.value) return
  if (!chart) chart = echarts.init(chartRef.value)
  const up = cssVar('--up') || '#e5484d'
  const text3 = cssVar('--text-3') || '#888'
  const border = cssVar('--border') || '#333'
  const points = result.value.nav
  chart.setOption({
    backgroundColor: 'transparent',
    grid: [
      { left: 56, right: 16, top: 30, height: '52%' },
      { left: 56, right: 16, top: '72%', height: '18%' },
    ],
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    tooltip: { trigger: 'axis' },
    legend: { data: ['策略净值', '买入持有'], textStyle: { color: text3 }, top: 0 },
    xAxis: [
      { type: 'category', data: points.map((p) => p.date), gridIndex: 0, axisLabel: { color: text3 }, axisLine: { lineStyle: { color: border } } },
      { type: 'category', data: points.map((p) => p.date), gridIndex: 1, show: false },
    ],
    yAxis: [
      { type: 'value', scale: true, gridIndex: 0, axisLabel: { color: text3 }, splitLine: { lineStyle: { color: border } } },
      { type: 'value', gridIndex: 1, max: 0, axisLabel: { color: text3, formatter: '{value}%' }, splitLine: { show: false } },
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1] },
      { type: 'slider', xAxisIndex: [0, 1], bottom: 4, height: 18, textStyle: { color: text3 } },
    ],
    series: [
      { name: '策略净值', type: 'line', data: points.map((p) => p.nav), showSymbol: false, lineStyle: { width: 2, color: up }, itemStyle: { color: up }, xAxisIndex: 0, yAxisIndex: 0 },
      { name: '买入持有', type: 'line', data: points.map((p) => p.bench), showSymbol: false, lineStyle: { width: 1.4, color: text3, type: 'dashed' }, itemStyle: { color: text3 }, xAxisIndex: 0, yAxisIndex: 0 },
      { name: '回撤', type: 'line', data: points.map((p) => p.dd), showSymbol: false, areaStyle: { opacity: 0.35 }, lineStyle: { width: 1, color: '#e8a33d' }, itemStyle: { color: '#e8a33d' }, xAxisIndex: 1, yAxisIndex: 1 },
    ],
  } as echarts.EChartsOption)
  chart.resize()
}

watch(result, () => {
  if (!result.value && chart) {
    chart.dispose()
    chart = null
  }
})

onMounted(async () => {
  try {
    codes.value = await quoteApi.list()
  } catch {
    // 拦截器已提示
  }
  if (chartRef.value) {
    ro = new ResizeObserver(() => chart?.resize())
    ro.observe(chartRef.value)
  }
})

onBeforeUnmount(() => {
  ro?.disconnect()
  chart?.dispose()
})
</script>

<style scoped>
.bt {
  padding: 16px 18px;
}
.bt__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
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

.form {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 14px;
}
.form__item {
  width: 150px;
}
.form__num {
  width: 110px;
}
.form__sep {
  font-size: 12px;
  color: var(--text-3);
}
.form__range {
  width: 260px;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
  margin-top: 14px;
}
.metric {
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.metric__label {
  font-size: 12px;
  color: var(--text-3);
}
.metric__value {
  font-size: 18px;
  font-weight: 700;
}
.metric__value--sm {
  font-size: 13px;
  font-weight: 500;
}

.chart {
  margin-top: 14px;
  height: 420px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 60px 0;
  color: var(--text-3);
  font-size: 13px;
}
.empty__icon {
  font-size: 30px;
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
</style>
