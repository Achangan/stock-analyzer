<template>
  <div class="kline">
    <div v-if="loading" class="kline__mask skeleton" />
    <div v-show="!loading && !bars.length" class="empty">
      <span class="empty__icon">📉</span>
      <span>该区间暂无行情数据</span>
    </div>
    <div ref="container" class="kline__canvas" />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { IndexDaily } from '@/api'
import { shortDate } from '@/utils/format'

const props = defineProps<{
  bars: IndexDaily[]
  loading?: boolean
  theme: string
}>()

const container = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null
/** 是否已真正绘制过：容器初始为 0 尺寸时 setOption 会画不出内容 */
let drawn = false

/** 从主题变量里取实际颜色（ECharts 需要具体色值） */
function cssVar(name: string, fallback: string) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

function palette() {
  return {
    up: cssVar('--up', '#e5484d'),
    down: cssVar('--down', '#12a461'),
    text: cssVar('--text-2', '#9aa7b4'),
    text3: cssVar('--text-3', '#6b7684'),
    border: cssVar('--border', '#232c38'),
    ma5: '#f0a020',
    ma20: '#2f81f7',
    ma60: '#a371f7',
  }
}

/** 简单移动平均；不足窗口返回 null */
function ma(values: number[], n: number): (number | null)[] {
  return values.map((_, i) => {
    if (i < n - 1) return null
    let sum = 0
    for (let j = i - n + 1; j <= i; j++) sum += values[j] ?? 0
    return Number((sum / n).toFixed(2))
  })
}

function render() {
  if (!chart) return
  // 抽屉动画期间容器尺寸为 0，此时 setOption 画不出内容，等 ResizeObserver 回调再来
  const el = container.value
  if (!el || !el.clientWidth || !el.clientHeight) return

  const bars = props.bars
  if (!bars.length) {
    chart.clear()
    drawn = false
    return
  }
  const c = palette()
  const dates = bars.map((b) => shortDate(b.tradeDate))
  const kline = bars.map((b) => [b.open, b.close, b.low, b.high])
  const closes = bars.map((b) => b.close)
  const vols = bars.map((b) => ({
    value: b.vol ?? 0,
    itemStyle: { color: (b.pctChg ?? 0) >= 0 ? c.up : c.down, opacity: 0.55 },
  }))

  // 默认只展示最后 180 根，避免"全部"区间过密
  const visible = 180
  const start = bars.length > visible ? Number((100 - (visible / bars.length) * 100).toFixed(2)) : 0

  chart.setOption(
    {
      animation: false,
      backgroundColor: 'transparent',
      textStyle: { color: c.text },
      grid: [
        { left: 58, right: 20, top: 18, height: '60%' },
        { left: 58, right: 20, top: '76%', height: '15%' },
      ],
      axisPointer: {
        link: [{ xAxisIndex: 'all' }],
        label: { backgroundColor: c.border },
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross' },
        backgroundColor: cssVar('--card', '#161b22'),
        borderColor: c.border,
        textStyle: { color: cssVar('--text', '#e6edf3'), fontSize: 12 },
      },
      xAxis: [
        {
          type: 'category',
          data: dates,
          gridIndex: 0,
          boundaryGap: true,
          axisLine: { lineStyle: { color: c.border } },
          axisLabel: { show: false },
          axisTick: { show: false },
          splitLine: { show: false },
        },
        {
          type: 'category',
          data: dates,
          gridIndex: 1,
          boundaryGap: true,
          axisLine: { lineStyle: { color: c.border } },
          axisTick: { show: false },
          axisLabel: { color: c.text3, fontSize: 11 },
          splitLine: { show: false },
        },
      ],
      yAxis: [
        {
          scale: true,
          gridIndex: 0,
          splitNumber: 4,
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: c.text3, fontSize: 11 },
          splitLine: { lineStyle: { color: c.border, type: 'dashed', opacity: 0.6 } },
        },
        {
          scale: true,
          gridIndex: 1,
          splitNumber: 2,
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: {
            color: c.text3,
            fontSize: 10,
            formatter: (v: number) => (v >= 1e8 ? `${(v / 1e8).toFixed(0)}亿` : `${(v / 1e4).toFixed(0)}万`),
          },
          splitLine: { show: false },
        },
      ],
      dataZoom: [
        { type: 'inside', xAxisIndex: [0, 1], start, end: 100 },
        {
          type: 'slider',
          xAxisIndex: [0, 1],
          bottom: 2,
          height: 16,
          start,
          end: 100,
          borderColor: c.border,
          fillerColor: 'rgba(47,129,247,0.15)',
          handleStyle: { color: c.ma20 },
          textStyle: { color: c.text3, fontSize: 10 },
          dataBackground: {
            lineStyle: { color: c.border },
            areaStyle: { color: 'rgba(47,129,247,0.12)' },
          },
        },
      ],
      series: [
        {
          name: 'K线',
          type: 'candlestick',
          xAxisIndex: 0,
          yAxisIndex: 0,
          data: kline,
          itemStyle: {
            color: c.up,
            color0: c.down,
            borderColor: c.up,
            borderColor0: c.down,
          },
        },
        {
          name: 'MA5',
          type: 'line',
          xAxisIndex: 0,
          yAxisIndex: 0,
          data: ma(closes, 5),
          smooth: true,
          showSymbol: false,
          lineStyle: { width: 1.2, color: c.ma5 },
        },
        {
          name: 'MA20',
          type: 'line',
          xAxisIndex: 0,
          yAxisIndex: 0,
          data: ma(closes, 20),
          smooth: true,
          showSymbol: false,
          lineStyle: { width: 1.2, color: c.ma20 },
        },
        {
          name: 'MA60',
          type: 'line',
          xAxisIndex: 0,
          yAxisIndex: 0,
          data: ma(closes, 60),
          smooth: true,
          showSymbol: false,
          lineStyle: { width: 1.2, color: c.ma60 },
        },
        {
          name: '成交量',
          type: 'bar',
          xAxisIndex: 1,
          yAxisIndex: 1,
          data: vols,
          barWidth: '60%',
        },
      ],
      legend: {
        data: ['MA5', 'MA20', 'MA60'],
        top: 0,
        right: 8,
        itemWidth: 14,
        itemHeight: 6,
        textStyle: { color: c.text3, fontSize: 11 },
      },
    },
    true,
  )
  drawn = true
}

onMounted(() => {
  if (!container.value) return
  chart = echarts.init(container.value)
  render()
  observer = new ResizeObserver(() => {
    if (!chart) return
    chart.resize()
    // 首次因尺寸为 0 没画出内容时，尺寸就绪后补一次绘制
    if (!drawn) render()
  })
  observer.observe(container.value)
  // 抽屉展开动画结束后再兜底绘制一次
  requestAnimationFrame(() => {
    chart?.resize()
    if (!drawn) render()
  })
})

watch(() => props.bars, render)
watch(() => props.theme, render)

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.kline {
  position: relative;
  width: 100%;
}
.kline__canvas {
  width: 100%;
  height: 400px;
}
.kline__mask {
  position: absolute;
  inset: 0;
  z-index: 2;
  border-radius: var(--radius-sm);
  opacity: 0.7;
}
@media (max-width: 768px) {
  .kline__canvas {
    height: 300px;
  }
}
</style>
