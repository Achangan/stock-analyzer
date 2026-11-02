<template>
  <section class="card screen">
    <div class="screen__head">
      <div class="title">
        <h2>策略榜单</h2>
        <span class="hint">全市场多因子筛选：动量 / 趋势 / 量能 / 波动 / 回撤加权，同行业最多 2 只</span>
      </div>
      <div class="actions">
        <el-select v-model="tradeDate" placeholder="选择交易日" class="date-select" @change="load">
          <el-option v-for="d in dates" :key="d" :label="d" :value="d" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" row-key="tsCode" style="width: 100%">
      <el-table-column label="#" width="56" align="center">
        <template #default="{ row }">
          <span class="rank num">{{ row.rankNo }}</span>
        </template>
      </el-table-column>
      <el-table-column label="股票" min-width="150">
        <template #default="{ row }">
          <div class="stock">
            <span class="stock__name">{{ row.name }}</span>
            <span class="stock__code num">{{ row.tsCode }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="行业" prop="industry" min-width="130" show-overflow-tooltip />
      <el-table-column label="收盘" width="88" align="right">
        <template #default="{ row }">
          <span class="num">{{ fmtNum(row.close) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="涨跌" width="88" align="right">
        <template #default="{ row }">
          <span class="num" :class="dirClass(row.pctChg)">{{ fmtPct(row.pctChg) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="20日" width="88" align="right">
        <template #default="{ row }">
          <span class="num" :class="dirClass(row.mom20)">{{ fmtPct(row.mom20, 1) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="5日" width="84" align="right">
        <template #default="{ row }">
          <span class="num" :class="dirClass(row.mom5)">{{ fmtPct(row.mom5, 1) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="量比" width="76" align="right">
        <template #default="{ row }">
          <span class="num">{{ fmtNum(row.volratio, 2) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="RSI" width="70" align="right">
        <template #default="{ row }">
          <span class="num">{{ fmtNum(row.rsi14, 0) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="波动率" width="88" align="right">
        <template #default="{ row }">
          <span class="num">{{ fmtNum(row.vol20, 1) }}%</span>
        </template>
      </el-table-column>
      <el-table-column label="回撤" width="80" align="right">
        <template #default="{ row }">
          <span class="num">{{ fmtNum(row.drawdown60, 1) }}%</span>
        </template>
      </el-table-column>
      <el-table-column label="市值(亿)" width="94" align="right">
        <template #default="{ row }">
          <span class="num">{{ fmtNum(row.mcap, 0) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="PE" width="76" align="right">
        <template #default="{ row }">
          <span class="num">{{ fmtNum(row.pe, 1) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="评分" width="82" align="right">
        <template #default="{ row }">
          <span class="score num">{{ fmtNum(row.score, 1) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="96" align="center" fixed="right">
        <template #default="{ row }">
          <el-button size="small" :icon="Plus" @click="addWatch(row)">加自选</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && rows.length === 0" description="暂无榜单数据，先运行 collector/screen_stocks.py 生成" />

    <p class="risk">{{ disclaimer }}</p>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { portfolioApi, screenApi, type ScreenRow } from '@/api'
import { dirClass, fmtNum, fmtPct } from '@/utils/format'

const rows = ref<ScreenRow[]>([])
const dates = ref<string[]>([])
const tradeDate = ref('')
const loading = ref(false)
const disclaimer = ref('策略榜单基于历史行情量化筛选，仅供参考，不构成投资建议，不承诺任何收益。')

async function load() {
  loading.value = true
  try {
    const res = await screenApi.list(tradeDate.value || undefined, 20)
    rows.value = res.list
    if (res.tradeDate) tradeDate.value = res.tradeDate
    disclaimer.value = res.disclaimer
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function addWatch(row: ScreenRow) {
  try {
    await portfolioApi.addWatch(row.tsCode, '策略榜单')
    ElMessage.success(`${row.name} 已加入自选`)
  } catch {
    // 拦截器已提示，重复添加时后端唯一键会拒绝
  }
}

onMounted(async () => {
  try {
    dates.value = await screenApi.dates()
  } catch {
    // 拦截器已提示
  }
  await load()
})
</script>

<style scoped>
.screen {
  padding: 16px 18px;
}
.screen__head {
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
.actions {
  display: flex;
  gap: 8px;
}
.date-select {
  width: 150px;
}

.rank {
  font-weight: 600;
  color: var(--text-2);
}
.stock {
  display: flex;
  align-items: center;
  gap: 8px;
}
.stock__name {
  font-weight: 600;
}
.stock__code {
  font-size: 12px;
  color: var(--text-3);
}
.score {
  font-weight: 600;
  color: var(--accent, var(--text));
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
