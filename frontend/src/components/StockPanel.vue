<template>
  <div class="stock-panel">
    <!-- 搜索栏 -->
    <div class="toolbar card">
      <div class="toolbar__row">
        <el-input
          v-model="keyword"
          placeholder="输入代码或名称，如 600519 / 贵州茅台"
          clearable
          class="toolbar__search"
          @keyup.enter="doSearch"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="industry" placeholder="全部行业" clearable class="toolbar__select" @change="doSearch">
          <el-option v-for="i in industries" :key="i" :label="i" :value="i" />
        </el-select>
        <el-button type="primary" :loading="listLoading" @click="doSearch">搜索</el-button>
        <el-button @click="doReset">重置</el-button>
      </div>
      <div class="toolbar__hint">
        库内共 {{ total }} 只上市股票 · 已有日线数据 {{ dataCodes }} 只
      </div>
    </div>

    <!-- 列表 -->
    <div class="card">
      <el-table
        v-loading="listLoading"
        :data="rows"
        style="width: 100%"
        row-key="tsCode"
        highlight-current-row
        @row-click="openDetail"
        @sort-change="onSortChange"
        empty-text="无匹配股票"
      >
        <el-table-column prop="tsCode" label="代码" width="120">
          <template #default="{ row }">
            <span class="num code">{{ row.tsCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="industry" label="行业" min-width="180">
          <template #default="{ row }">{{ row.industry || '—' }}</template>
        </el-table-column>
        <el-table-column prop="listDate" label="上市日期" width="110">
          <template #default="{ row }">{{ row.listDate || '—' }}</template>
        </el-table-column>
        <el-table-column prop="latestClose" label="最新价" width="120" align="right" sortable="custom">
          <template #default="{ row }">
            <span v-if="row.latestClose != null" class="num">
              {{ row.latestClose.toFixed(2) }}
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="latestPct" label="涨跌幅" width="120" align="right" sortable="custom">
          <template #default="{ row }">
            <span
              v-if="row.latestPct != null"
              class="num dir"
              :class="dirClass(row.latestPct)"
            >
              {{ fmtPct(row.latestPct) }}
            </span>
            <span v-else class="muted">未入库</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          background
          layout="prev, pager, next, jumper, total"
          :current-page="page"
          :page-size="size"
          :total="total"
          @current-change="onPage"
        />
      </div>
    </div>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawerVisible" size="560px" :with-header="false" direction="rtl">
      <div class="detail-drawer">
        <header class="drawer-head">
          <div>
            <h2>
              {{ currentDetail?.name || '-' }}
              <span class="code num">{{ currentCode }}</span>
            </h2>
            <div v-if="currentDetail" class="head-meta">
              <span v-if="currentDetail.latestClose != null" class="num" :class="dirClass(currentDetail.latestPct ?? 0)">
                {{ currentDetail.latestClose.toFixed(2) }}
              </span>
              <span v-if="currentDetail.latestPct != null" class="num" :class="dirClass(currentDetail.latestPct)">
                {{ fmtPct(currentDetail.latestPct) }}
              </span>
              <span v-if="currentDetail.endDate" class="muted">截至 {{ currentDetail.endDate }}</span>
            </div>
          </div>
          <button class="close-btn" @click="drawerVisible = false">✕</button>
        </header>

        <!-- 基本信息 -->
        <div v-if="currentDetail" class="info-grid">
          <div class="info-item"><span>行业</span><b>{{ currentDetail.industry || '—' }}</b></div>
          <div class="info-item"><span>上市</span><b>{{ currentDetail.listDate || '—' }}</b></div>
          <div class="info-item"><span>数据区间</span><b>{{ currentDetail.startDate || '—' }} ~ {{ currentDetail.endDate || '—' }}</b></div>
          <div class="info-item"><span>日线条数</span><b class="num">{{ currentDetail.rowsCount }}</b></div>
        </div>

        <!-- K 线 -->
        <section class="block">
          <div class="block-head">
            <h3>K 线走势</h3>
            <el-radio-group v-model="rangeKey" size="small">
              <el-radio-button v-for="o in RANGE_OPTIONS" :key="o.key" :value="o.key">{{ o.label }}</el-radio-button>
            </el-radio-group>
          </div>
          <KlineChart :key="chartKey" :bars="bars" :loading="klineLoading" :theme="theme" />
        </section>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'
import KlineChart from '@/components/KlineChart.vue'
import { stockApi, type StockBrief, type StockDetail, type IndexDaily } from '@/api'

const props = defineProps<{ theme: string }>()

const industries = ref<string[]>([])
const keyword = ref('')
const industry = ref<string | undefined>(undefined)
const page = ref(1)
const size = 20
const total = ref(0)
const rows = ref<StockBrief[]>([])
const listLoading = ref(false)
const sort = ref<'close' | 'pct' | undefined>(undefined)
const order = ref<'asc' | 'desc' | undefined>(undefined)

// 详情抽屉
const drawerVisible = ref(false)
const currentCode = ref('')
const currentDetail = ref<StockDetail | null>(null)
const bars = ref<IndexDaily[]>([])
const klineLoading = ref(false)
const rangeKey = ref<'m3' | 'm6' | 'y1' | 'all'>('y1')
const RANGE_OPTIONS = [
  { key: 'm3', label: '3月' },
  { key: 'm6', label: '6月' },
  { key: 'y1', label: '1年' },
  { key: 'all', label: '全部' },
] as const
const chartKey = ref(0)

const dataCodes = computed(() => rows.value.filter((r) => r.latestClose != null).length)

function dirClass(pct: number) {
  return pct > 0 ? 'up' : pct < 0 ? 'down' : 'flat'
}
function fmtPct(pct: number | null) {
  if (pct == null) return '—'
  const s = pct > 0 ? '+' : ''
  return s + pct.toFixed(2) + '%'
}

async function loadIndustries() {
  try { industries.value = await stockApi.industries() } catch {}
}

async function doSearch() {
  page.value = 1
  await loadList()
}

function doReset() {
  keyword.value = ''
  industry.value = undefined
  page.value = 1
  loadList()
}

async function loadList() {
  listLoading.value = true
  try {
    const r = await stockApi.list(
      keyword.value.trim() || undefined,
      industry.value,
      page.value,
      size,
      sort.value,
      order.value,
    )
    rows.value = r.list
    total.value = r.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    listLoading.value = false
  }
}

/** 服务端排序：最新价 / 涨跌幅，空值由后端统一排最后 */
function onSortChange({ prop, order: o }: { prop: string; order: string | null }) {
  if (!o) {
    sort.value = undefined
    order.value = undefined
  } else {
    sort.value = prop === 'latestClose' ? 'close' : prop === 'latestPct' ? 'pct' : undefined
    order.value = o === 'descending' ? 'desc' : 'asc'
  }
  page.value = 1
  loadList()
}

function onPage(p: number) {
  page.value = p
  loadList()
}

async function openDetail(row: StockBrief) {
  currentCode.value = row.tsCode
  drawerVisible.value = true
  try {
    currentDetail.value = await stockApi.detail(row.tsCode)
  } catch {
    currentDetail.value = null
  }
  rangeKey.value = 'y1'
  await loadBars()
}

function loadBars() {
  klineLoading.value = true
  const now = new Date()
  const end = now.toISOString().slice(0, 10)
  const k = rangeKey.value
  const startDate = new Date(now)
  if (k === 'm3') startDate.setMonth(startDate.getMonth() - 3)
  else if (k === 'm6') startDate.setMonth(startDate.getMonth() - 6)
  else if (k === 'y1') startDate.setFullYear(startDate.getFullYear() - 1)
  const start = startDate.toISOString().slice(0, 10)
  const p = k === 'all'
    ? stockApi.range(currentCode.value)
    : stockApi.range(currentCode.value, start, end)
  return p.then((d) => {
    bars.value = d
    chartKey.value++
  }).catch(() => {
    bars.value = []
  }).finally(() => {
    klineLoading.value = false
  })
}

watch(rangeKey, () => { if (drawerVisible.value) loadBars() })

onMounted(() => {
  loadIndustries()
  loadList()
})
</script>

<style scoped>
.stock-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 14px 16px;
}
.toolbar__row {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.toolbar__search {
  flex: 1;
  min-width: 220px;
  max-width: 400px;
}
.toolbar__select { width: 200px; }
.toolbar__hint {
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-3);
}
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.num { font-variant-numeric: tabular-nums; }
.code { color: var(--text-2); font-size: 13px; }
.dir.up { color: var(--up, #e5484d); }
.dir.down { color: var(--down, #12a461); }
.dir.flat { color: var(--text-2); }
.muted { color: var(--text-3); }

/* drawer */
.detail-drawer {
  padding: 20px 22px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.drawer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}
.drawer-head h2 { margin: 0; font-size: 20px; font-weight: 600; }
.drawer-head .code { margin-left: 8px; font-size: 14px; color: var(--text-3); }
.head-meta { margin-top: 6px; display: flex; gap: 10px; align-items: baseline; }
.head-meta .num { font-size: 18px; }
.head-meta .muted { font-size: 12px; color: var(--text-3); }
.close-btn {
  border: none; background: transparent; color: var(--text-2);
  width: 32px; height: 32px; cursor: pointer; font-size: 16px;
  border-radius: 6px;
}
.close-btn:hover { background: var(--bg-soft); color: var(--text); }

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px 16px;
  padding: 12px 14px;
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
}
.info-item { display: flex; flex-direction: column; gap: 2px; }
.info-item span { color: var(--text-3); font-size: 11px; }
.info-item b { font-weight: 500; }

.block .block-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.block-head h3 { margin: 0; font-size: 14px; font-weight: 500; color: var(--text-2); }
</style>
