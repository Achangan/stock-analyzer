<template>
  <div class="app">
    <AppHeader
      :latest-date="latestDate"
      :loading="loading"
      :theme="theme"
      @refresh="refresh"
      @toggle-theme="toggleTheme"
    />

    <div class="layout">
      <nav class="seg">
        <button
          v-for="t in tabs"
          :key="t.key"
          class="seg__btn"
          :class="{ 'is-active': active === t.key }"
          type="button"
          @click="active = t.key"
        >
          <el-icon><component :is="t.icon" /></el-icon>
          {{ t.label }}
        </button>
      </nav>

      <main class="main">
        <div v-if="active === 'overview'" class="pane">
          <OverviewCards :rows="rows" :loading="loading" />
          <IndexTable :rows="rows" :loading="loading" @select="openDetail" />
        </div>

        <div v-else-if="active === 'compare'" class="pane">
          <ComparePanel :codes="codes" :theme="theme" />
        </div>

        <div v-else-if="active === 'stock'" class="pane">
          <StockPanel :theme="theme" />
        </div>

        <div v-else-if="active === 'factor'" class="pane">
          <FactorPanel />
        </div>

        <div v-else-if="active === 'backtest'" class="pane">
          <BacktestPanel />
        </div>

        <div v-else-if="active === 'watchlist'" class="pane">
          <WatchlistPanel />
        </div>

        <div v-else-if="active === 'position'" class="pane">
          <PositionPanel />
        </div>

        <div v-else-if="active === 'report'" class="pane">
          <ReportPanel />
        </div>

        <div v-else-if="active === 'learn'" class="pane">
          <LearnPanel />
        </div>

        <div v-else class="pane">
          <CoveragePanel />
        </div>
      </main>

      <footer class="footer">
        <span>数据来源：Tushare Pro / Baostock 回填 · 库内共 {{ rows.length }} 个指数</span>
        <span>本系统仅用于数据分析与复盘，不构成投资建议</span>
      </footer>
    </div>

    <IndexDetailDrawer
      v-model="detailVisible"
      :code="currentCode"
      :summary="currentSummary"
      :theme="theme"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Bell, Coin, DataLine, Document, Files, Histogram, Monitor, Reading, TrendCharts, Wallet } from '@element-plus/icons-vue'
import AppHeader from '@/components/AppHeader.vue'
import OverviewCards from '@/components/OverviewCards.vue'
import IndexTable from '@/components/IndexTable.vue'
import ComparePanel from '@/components/ComparePanel.vue'
import ReportPanel from '@/components/ReportPanel.vue'
import CoveragePanel from '@/components/CoveragePanel.vue'
import LearnPanel from '@/components/LearnPanel.vue'
import FactorPanel from '@/components/FactorPanel.vue'
import BacktestPanel from '@/components/BacktestPanel.vue'
import WatchlistPanel from '@/components/WatchlistPanel.vue'
import PositionPanel from '@/components/PositionPanel.vue'
import StockPanel from '@/components/StockPanel.vue'
import IndexDetailDrawer from '@/components/IndexDetailDrawer.vue'
import { useOverview } from '@/composables/useOverview'

type Theme = 'dark' | 'light'

const tabs = [
  { key: 'overview', label: '市场概览', icon: Monitor },
  { key: 'compare', label: '指数对比', icon: TrendCharts },
  { key: 'stock', label: '个股行情', icon: Coin },
  { key: 'factor', label: '因子打分', icon: Histogram },
  { key: 'backtest', label: '策略回测', icon: DataLine },
  { key: 'watchlist', label: '自选预警', icon: Bell },
  { key: 'position', label: '持仓管理', icon: Wallet },
  { key: 'report', label: '每日报告', icon: Document },
  { key: 'learn', label: '理财学习', icon: Reading },
  { key: 'coverage', label: '数据覆盖', icon: Files },
]

const active = ref('overview')
const theme = ref<Theme>('dark')

const { loading, rows, latestDate, load } = useOverview()
const codes = computed(() => rows.value.map((r) => r.tsCode))

const detailVisible = ref(false)
const currentCode = ref('')
const currentSummary = computed(
  () => rows.value.find((r) => r.tsCode === currentCode.value) ?? null,
)

function openDetail(code: string) {
  currentCode.value = code
  detailVisible.value = true
}

function refresh() {
  load()
}

function applyTheme() {
  document.documentElement.setAttribute('data-theme', theme.value)
}

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  applyTheme()
  localStorage.setItem('sa-theme', theme.value)
}

watch(theme, applyTheme)

onMounted(() => {
  const saved = localStorage.getItem('sa-theme')
  if (saved === 'light' || saved === 'dark') theme.value = saved
  applyTheme()
  load()
})
</script>

<style scoped>
.app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.layout {
  flex: 1;
  width: 100%;
  max-width: 1440px;
  margin: 0 auto;
  padding: 18px 24px 32px;
}

.seg {
  display: inline-flex;
  gap: 3px;
  padding: 4px;
  border-radius: 11px;
  background: var(--bg-soft);
  border: 1px solid var(--border);
  margin-bottom: 18px;
  max-width: 100%;
  overflow-x: auto;
}
.seg__btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 15px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-2);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.16s ease;
}
.seg__btn:hover {
  color: var(--text);
}
.seg__btn.is-active {
  background: var(--card);
  color: var(--text);
  box-shadow: var(--shadow);
}

.main {
  min-height: 320px;
}
.pane {
  display: flex;
  flex-direction: column;
  gap: 16px;
  animation: paneIn 0.22s ease;
}
@keyframes paneIn {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 28px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
  font-size: 12px;
  color: var(--text-3);
}

@media (max-width: 768px) {
  .layout {
    padding: 14px 14px 24px;
  }
  .seg {
    width: 100%;
  }
}
</style>
