<template>
  <svg
    v-if="points"
    class="spark"
    :class="`spark--${dir}`"
    :width="width"
    :height="height"
    :viewBox="`0 0 ${width} ${height}`"
    preserveAspectRatio="none"
    aria-hidden="true"
  >
    <polygon :points="area" />
    <polyline :points="points" fill="none" stroke-width="1.6" stroke-linejoin="round" />
    <circle v-if="last" :cx="last.x" :cy="last.y" r="1.8" />
  </svg>
  <span v-else class="spark spark--empty">—</span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { direction } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    data: number[]
    width?: number
    height?: number
    dir?: 'up' | 'down' | 'flat'
  }>(),
  { width: 84, height: 30 },
)

const pad = 3

// 未显式指定方向时，用首尾差判断 60 日整体走势
const dir = computed(() => {
  if (props.dir) return props.dir
  const d = props.data
  if (!d || d.length < 2) return 'flat'
  const first = d[0]
  const lastValue = d[d.length - 1]
  if (first === undefined || lastValue === undefined) return 'flat'
  return direction(lastValue - first)
})

const geometry = computed(() => {
  const d = props.data
  if (!d || d.length < 2) return null
  const min = Math.min(...d)
  const max = Math.max(...d)
  const span = max - min || 1
  const w = props.width - pad * 2
  const h = props.height - pad * 2
  return d.map((v, i) => ({
    x: pad + (i / (d.length - 1)) * w,
    y: pad + (1 - (v - min) / span) * h,
  }))
})

const points = computed(() =>
  geometry.value ? geometry.value.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ') : '',
)

const area = computed(() => {
  if (!geometry.value) return ''
  const base = props.height - pad
  return `${pad},${base} ${points.value} ${props.width - pad},${base}`
})

const last = computed(() => (geometry.value ? geometry.value[geometry.value.length - 1] : null))
</script>

<style scoped>
.spark {
  display: block;
}
.spark--up {
  --spark-color: var(--up);
}
.spark--down {
  --spark-color: var(--down);
}
.spark--flat {
  --spark-color: var(--flat);
}
.spark polyline,
.spark circle {
  stroke: var(--spark-color);
  fill: var(--spark-color);
}
.spark polygon {
  fill: var(--spark-color);
  opacity: 0.12;
  stroke: none;
}
.spark--empty {
  color: var(--text-3);
  font-size: 12px;
}
</style>
