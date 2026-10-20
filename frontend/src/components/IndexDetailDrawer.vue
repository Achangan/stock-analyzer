<template>
  <el-drawer
    v-model="visible"
    :size="drawerSize"
    :with-header="false"
    direction="rtl"
    class="detail-drawer"
    @opened="onOpened"
  >
    <div class="detail">
      <!-- 头部 -->
      <header class="detail__head">
        <div class="head__left">
          <h2>
            {{ indexName(code) }}
            <span class="code num">{{ code }}</span>
          </h2>
          <div class="head__price" v-if="summary">
            <span class="price num" :class="dirClass(summary.latestPctChg)">
              {{ fmtNum(summary.latestClose, 2) }}
            </span>
            <span class="change num" :class="dirClass(summary.latestPctChg)">
              {{ fmtPct(summary.latestPctChg) }}
            </span>
            <span class="date num">{{ isoDate(summary.latestDate) }}</span>
          </div>
        </div>
        <button class="close-btn" type="button" title="关闭" @click="visible = false">✕</button>
      </header>

      <div class="detail__body">
        <!-- 关键指标 -->
        <div class="metrics" v-if="summary">
          <div v-for="m in metrics" :key="m.label" class="metric">
            <span class="metric__label">{{ m.label }}</span>
            <span class="metric__value num" :class="m.cls">{{ m.value }}</span>
          </div>
        </div>

        <!-- K 线 -->
        <section class="block">
          <div class="block__head">
            <h3>K 线走势</h3>
            <el-radio-group v-model="rangeKey" size="small">
              <el-radio-button v-for="o in RANGE_OPTIONS" :key="o.key" :value="o.key">
                {{ o.label }}
              </el-radio-button>
            </el-radio-group>
          </div>
          <KlineChart :key="chartKey" :bars="bars" :loading="loading" :theme="theme" />
          <p class="hint">
            蜡烛图红色为收涨、绿色为收跌（A股口径）；叠加 MA5 / MA20 / MA60，副图为成交量。
          </p>
        </section>

        <!-- 技术信号解读 -->
        <section class="block">
          <div class="block__head">
            <h3>技术信号解读</h3>
            <span class="head__note">基于库内日线推导，非投资建议</span>
          </div>
          <ul class="signals">
            <li v-for="(s, i) in signals" :key="i" class="signal">
              <span class="signal__label">{{ s.label }}</span>
              <span class="tag" :class="s.cls">{{ s.text }}</span>
              <span class="signal__desc">{{ s.desc }}</span>
            </li>
          </ul>
          <p class="risk">
            风险提示：以上指标仅由历史行情计算得出，不构成任何买卖建议；市场有风险，投资需谨慎。
          </p>
        </section>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import KlineChart from './KlineChart.vue'
import { useKline, RANGE_OPTIONS } from '@/composables/useKline'
import { dirClass, fmtNum, fmtPct, indexName, isoDate } from '@/utils/format'
import type { IndexSummary } from '@/api'

const props = defineProps<{
  modelValue: boolean
  code: string
  summary: IndexSummary | null
  theme: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const drawerSize = computed(() => (typeof window !== 'undefined' && window.innerWidth < 768 ? '100%' : '62%'))

const { loading, bars, rangeKey, load } = useKline('120')

// 抽屉首次打开时容器尺寸可能为 0，动画结束后重建图表以保证 ECharts 拿到真实尺寸
const chartKey = ref(0)

function onOpened() {
  chartKey.value++
}

watch(
  [() => props.modelValue, () => props.code],
  ([open, code]) => {
    if (open && code) load(code)
  },
  { immediate: true },
)

watch(rangeKey, () => {
  if (props.modelValue && props.code) load(props.code)
})

const metrics = computed(() => {
  const s = props.summary
  if (!s) return []
  return [
    { label: 'MA5', value: fmtNum(s.ma5, 2), cls: '' },
    { label: 'MA20', value: fmtNum(s.ma20, 2), cls: '' },
    { label: 'MA60', value: fmtNum(s.ma60, 2), cls: '' },
    { label: '近20日', value: fmtPct(s.change20d), cls: dirClass(s.change20d) },
    { label: '近60日', value: fmtPct(s.change60d), cls: dirClass(s.change60d) },
    { label: 'MA20乖离', value: fmtPct(s.bias20), cls: dirClass(s.bias20) },
    { label: '20日年化波动', value: `${fmtNum(s.volatility20d, 2)}%`, cls: '' },
    { label: '数据天数', value: `${bars.value.length || '—'}`, cls: '' },
  ]
})

interface Signal {
  label: string
  text: string
  cls: string
  desc: string
}

const signals = computed<Signal[]>(() => {
  const s = props.summary
  if (!s) return []
  const out: Signal[] = []

  // 1. 均线排列
  if (s.trend === '多头排列') out.push({ label: '均线排列', text: '多头排列', cls: 'tag--up', desc: 'MA5>MA20>MA60，短中长期均线依次向上，趋势偏强。' })
  else if (s.trend === '空头排列') out.push({ label: '均线排列', text: '空头排列', cls: 'tag--down', desc: 'MA5<MA20<MA60，均线依次向下，趋势偏弱。' })
  else if (s.trend === '震荡') out.push({ label: '均线排列', text: '震荡交织', cls: 'tag--flat', desc: '均线未形成一致方向，处于震荡格局。' })

  // 2. MA5 / MA20 关系
  if (s.ma5 !== null && s.ma20 !== null) {
    const above = s.ma5 > s.ma20
    out.push({
      label: '短期强弱',
      text: above ? 'MA5 上穿 MA20' : 'MA5 位于 MA20 下方',
      cls: above ? 'tag--up' : 'tag--down',
      desc: above ? '短期动能在中期均线之上。' : '短期动能弱于中期均线。',
    })
  }

  // 3. MA20 / MA60 关系
  if (s.ma20 !== null && s.ma60 !== null) {
    const above = s.ma20 > s.ma60
    out.push({
      label: '中期强弱',
      text: above ? 'MA20 上穿 MA60' : 'MA20 位于 MA60 下方',
      cls: above ? 'tag--up' : 'tag--down',
      desc: above ? '中期趋势强于长期均线。' : '中期趋势弱于长期均线。',
    })
  }

  // 4. MA20 乖离率
  if (s.bias20 !== null) {
    if (s.bias20 > 5) out.push({ label: 'MA20 乖离', text: `+${s.bias20.toFixed(2)}%`, cls: 'tag--up', desc: '价格高于 MA20 较多，短线偏热，注意回调风险。' })
    else if (s.bias20 < -5) out.push({ label: 'MA20 乖离', text: `${s.bias20.toFixed(2)}%`, cls: 'tag--down', desc: '价格低于 MA20 较多，短线偏冷，谨防弱势延续。' })
    else out.push({ label: 'MA20 乖离', text: `${s.bias20.toFixed(2)}%`, cls: 'tag--flat', desc: '价格围绕 MA20 波动，偏离度有限。' })
  }

  // 5. 动量
  if (s.change20d !== null) {
    const up = s.change20d > 0
    out.push({
      label: '20日动量',
      text: fmtPct(s.change20d),
      cls: up ? 'tag--up' : 'tag--down',
      desc: up ? '近 20 个交易日累计上涨。' : '近 20 个交易日累计下跌。',
    })
  }
  if (s.change60d !== null) {
    const up = s.change60d > 0
    out.push({
      label: '60日动量',
      text: fmtPct(s.change60d),
      cls: up ? 'tag--up' : 'tag--down',
      desc: up ? '近 60 个交易日累计上涨。' : '近 60 个交易日累计下跌。',
    })
  }

  // 6. 波动率
  if (s.volatility20d !== null) {
    const v = s.volatility20d
    const high = v >= 25
    const mid = v >= 15
    out.push({
      label: '20日波动率',
      text: `${v.toFixed(2)}%`,
      cls: high ? 'tag--up' : mid ? 'tag--flat' : 'tag--accent',
      desc: high ? '年化波动率偏高，价格波动剧烈。' : mid ? '年化波动率中等。' : '年化波动率较低，走势相对平稳。',
    })
  }

  return out
})
</script>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.detail__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 22px 16px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-soft);
}
.head__left h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.code {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-3);
}
.head__price {
  margin-top: 8px;
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.price {
  font-size: 26px;
  font-weight: 650;
}
.change {
  font-size: 15px;
  font-weight: 600;
}
.date {
  font-size: 12px;
  color: var(--text-3);
}
.close-btn {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--card);
  color: var(--text-2);
  cursor: pointer;
  transition: all 0.16s ease;
}
.close-btn:hover {
  color: var(--up);
  border-color: var(--up);
}

.detail__body {
  padding: 16px 22px 28px;
  overflow: auto;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  background: var(--border);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
  margin-bottom: 18px;
}
.metric {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 11px 12px;
  background: var(--card);
}
.metric__label {
  font-size: 12px;
  color: var(--text-3);
}
.metric__value {
  font-size: 15px;
  font-weight: 600;
}

.block {
  margin-top: 18px;
}
.block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.block__head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}
.head__note {
  font-size: 12px;
  color: var(--text-3);
}
.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-3);
}

.signals {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.signal {
  display: grid;
  grid-template-columns: 88px 150px 1fr;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
}
.signal:last-child {
  border-bottom: none;
}
.signal__label {
  font-size: 13px;
  color: var(--text-2);
}
.signal__desc {
  font-size: 12px;
  color: var(--text-3);
}

.risk {
  margin: 12px 0 0;
  padding: 9px 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--warn);
  background: color-mix(in srgb, var(--warn) 10%, transparent);
  border-left: 2px solid var(--warn);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
}

@media (max-width: 768px) {
  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .signal {
    grid-template-columns: 1fr;
    gap: 4px;
  }
  .detail__body {
    padding: 14px 14px 24px;
  }
}
</style>
