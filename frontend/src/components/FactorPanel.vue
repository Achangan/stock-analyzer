<template>
  <section class="card factor">
    <div class="factor__head">
      <div class="title">
        <h2>因子权重打分</h2>
        <span class="hint">四因子 min-max 归一化加权，先跑指数轮动；权重总和自动归一到 100%</span>
      </div>
      <div class="actions">
        <el-select v-model="selectedConfig" placeholder="选择权重配置" clearable class="cfg-select" @change="applyConfig">
          <el-option v-for="c in configs" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-button :icon="FolderAdd" @click="saveConfig">存为配置</el-button>
        <el-button type="primary" :loading="loading" :icon="Refresh" @click="run">重新打分</el-button>
      </div>
    </div>

    <div class="weights">
      <div v-for="f in factorDefs" :key="f.key" class="weight">
        <div class="weight__label">
          <span>{{ f.label }}</span>
          <span class="weight__hint">{{ f.hint }}</span>
          <span class="num weight__val">{{ weights[f.key] }}%</span>
        </div>
        <el-slider v-model="weights[f.key]" :min="0" :max="100" :show-tooltip="false" @input="normalize(f.key)" />
      </div>
    </div>

    <el-table v-loading="loading" :data="list" row-key="tsCode" style="width: 100%">
      <el-table-column label="排名" width="64" align="center" type="index" />
      <el-table-column label="指数" min-width="140">
        <template #default="{ row }">
          <div class="idx">
            <span class="idx__name">{{ indexName(row.tsCode) }}</span>
            <span class="idx__code num">{{ row.tsCode }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="综合评分" min-width="180" sortable prop="score">
        <template #default="{ row }">
          <div class="score">
            <div class="score__bar">
              <div class="score__fill" :style="{ width: row.score + '%' }" />
            </div>
            <span class="num strong">{{ fmtNum(row.score, 2) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="动量(20日)" min-width="110" align="right">
        <template #default="{ row }">
          <span class="num" :class="dirClass(row.raw.mom20)">{{ fmtPct(row.raw.mom20) }}</span>
          <span class="norm num">{{ fmtNum(row.raw.mom20Norm, 0) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="趋势" min-width="100" align="center">
        <template #default="{ row }">
          <span class="tag" :class="trendTag(row.trend).cls">{{ trendTag(row.trend).text }}</span>
          <span class="norm num">{{ fmtNum(row.raw.trendNorm, 0) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="波动率(20日)" min-width="110" align="right">
        <template #default="{ row }">
          <span class="num">{{ fmtPct(row.raw.vol) }}</span>
          <span class="norm num">{{ fmtNum(row.raw.volNorm, 0) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="MA20乖离" min-width="110" align="right">
        <template #default="{ row }">
          <span class="num" :class="dirClass(row.raw.bias)">{{ fmtPct(row.raw.bias) }}</span>
          <span class="norm num">{{ fmtNum(row.raw.biasNorm, 0) }}</span>
        </template>
      </el-table-column>
    </el-table>

    <p class="risk">{{ disclaimer || '因子评分基于历史行情，仅供参考，不构成投资建议，不承诺任何收益。' }}</p>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { FolderAdd, Refresh } from '@element-plus/icons-vue'
import { factorApi, type FactorConfig, type FactorRow } from '@/api'
import { dirClass, fmtNum, fmtPct, indexName, trendTag } from '@/utils/format'

const factorDefs = [
  { key: 'mom20', label: '动量 · 20日涨幅', hint: '越高越好' },
  { key: 'trend', label: '趋势 · 均线排列', hint: '越高越好' },
  { key: 'vol', label: '波动率 · 20日年化', hint: '越低越好' },
  { key: 'bias', label: '乖离率 · MA20', hint: '越低越好' },
] as const

type FactorKey = (typeof factorDefs)[number]['key']

const weights = reactive<Record<FactorKey, number>>({ mom20: 40, trend: 30, vol: 20, bias: 10 })
const list = ref<FactorRow[]>([])
const configs = ref<FactorConfig[]>([])
const selectedConfig = ref<number | null>(null)
const loading = ref(false)
const disclaimer = ref('')

/** 拖动某因子后，把其余因子等比压缩/放大，保持总和 100 */
function normalize(changed: FactorKey) {
  const others = factorDefs.map((f) => f.key).filter((k) => k !== changed)
  const rest = 100 - weights[changed]
  const otherSum = others.reduce((s, k) => s + weights[k], 0)
  if (otherSum === 0) {
    others.forEach((k) => (weights[k] = Math.round(rest / others.length)))
  } else {
    others.forEach((k) => (weights[k] = Math.round((weights[k] / otherSum) * rest)))
  }
  // 修正四舍五入误差
  const drift = 100 - factorDefs.reduce((s, f) => s + weights[f.key], 0)
  const first = others[0]
  if (first) weights[first] += drift
}

async function run() {
  loading.value = true
  try {
    const res = await factorApi.score({ mom20: weights.mom20 / 100, trend: weights.trend / 100, vol: weights.vol / 100, bias: weights.bias / 100 })
    list.value = res.list
    disclaimer.value = res.disclaimer
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function loadConfigs() {
  try {
    configs.value = await factorApi.configs()
  } catch {
    // 拦截器已提示
  }
}

function applyConfig(id: number | null) {
  const cfg = configs.value.find((c) => c.id === id)
  if (!cfg) return
  try {
    const w = JSON.parse(cfg.weights) as Record<FactorKey, number>
    const sum = Object.values(w).reduce((s, v) => s + v, 0) || 1
    factorDefs.forEach((f) => (weights[f.key] = Math.round(((w[f.key] ?? 0) / sum) * 100)))
  } catch {
    ElMessage.error('配置解析失败')
  }
}

async function saveConfig() {
  try {
    const { value } = await ElMessageBox.prompt('给这套权重起个名字', '存为配置', {
      inputPlaceholder: '如：稳健轮动',
      inputValidator: (v) => (v && v.trim() ? true : '名称不能为空'),
    })
    const name = value!.trim()
    await factorApi.saveConfig({
      name,
      weights: { mom20: weights.mom20 / 100, trend: weights.trend / 100, vol: weights.vol / 100, bias: weights.bias / 100 },
    })
    ElMessage.success('已保存')
    await loadConfigs()
  } catch {
    // 用户取消或保存失败
  }
}

onMounted(() => {
  run()
  loadConfigs()
})
</script>

<style scoped>
.factor {
  padding: 16px 18px;
}
.factor__head {
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
.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.cfg-select {
  width: 160px;
}

.weights {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 6px 24px;
  margin: 14px 0 6px;
  padding: 12px 16px;
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.weight__label {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
}
.weight__hint {
  font-size: 11px;
  color: var(--text-3);
}
.weight__val {
  margin-left: auto;
  font-size: 13px;
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
.norm {
  margin-left: 6px;
  font-size: 11px;
  color: var(--text-3);
}

.score {
  display: flex;
  align-items: center;
  gap: 10px;
}
.score__bar {
  flex: 1;
  height: 8px;
  border-radius: 4px;
  background: var(--bg-soft);
  border: 1px solid var(--border);
  overflow: hidden;
}
.score__fill {
  height: 100%;
  border-radius: 4px;
  background: linear-gradient(90deg, var(--up), color-mix(in srgb, var(--up) 60%, #fff 0%));
  transition: width 0.3s ease;
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
