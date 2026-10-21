<template>
  <section class="card pos">
    <div class="pos__head">
      <div class="title">
        <h2>持仓管理</h2>
        <span class="hint">按仓位公式（单笔风险 1% ÷ 止损 5% → 单仓上限 20%）提示风险</span>
      </div>
      <div class="actions">
        <el-button type="primary" :icon="Plus" @click="dialogVisible = true">录入持仓</el-button>
      </div>
    </div>

    <div v-if="data.list.length" class="metrics">
      <div class="metric">
        <span class="metric__label">总市值</span>
        <span class="num metric__value">{{ fmtNum(data.totalMarketValue, 2) }}</span>
      </div>
      <div class="metric">
        <span class="metric__label">仓位上限</span>
        <span class="num metric__value">{{ data.positionLimitPct }}%</span>
      </div>
    </div>

    <el-table v-if="data.list.length" :data="data.list" row-key="id" style="width: 100%">
      <el-table-column label="指数" min-width="150">
        <template #default="{ row }">
          <div class="idx">
            <span class="idx__name">{{ row.name || indexName(row.tsCode) }}</span>
            <span class="idx__code num">{{ row.tsCode }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="成本价" width="110" align="right">
        <template #default="{ row }">
          <span class="num">{{ fmtNum(row.costPrice, 2) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="最新价" width="110" align="right">
        <template #default="{ row }">
          <span class="num">{{ fmtNum(row.latestPrice, 2) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="持仓量" width="120" align="right">
        <template #default="{ row }">
          <span class="num">{{ fmtNum(row.shares, 2) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="浮盈浮亏" width="140" align="right">
        <template #default="{ row }">
          <span class="num strong" :class="dirClass(row.pnlPct)">{{ fmtNum(row.pnl, 2) }} ({{ fmtPct(row.pnlPct) }})</span>
        </template>
      </el-table-column>
      <el-table-column label="仓位占比" width="140" align="right">
        <template #default="{ row }">
          <div class="bar">
            <div class="bar__fill" :class="{ 'bar__fill--warn': row.overWeight }" :style="{ width: Math.min(row.weightPct, 100) + '%' }" />
          </div>
          <span class="num" :class="{ 'c-up': row.overWeight }">{{ fmtNum(row.weightPct, 2) }}%</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" align="center">
        <template #default="{ row }">
          <el-button size="small" type="danger" :icon="Delete" @click="remove(row.id)">删</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-else class="empty">
      <span class="empty__icon">💼</span>
      <span>暂无持仓记录，点击「录入持仓」添加</span>
    </div>

    <p class="risk">{{ data.disclaimer || '盈亏按最新收盘估算，仅供参考，不构成投资建议，不承诺任何收益。' }}</p>

    <el-dialog v-model="dialogVisible" title="录入持仓" width="400px">
      <el-form label-width="80px">
        <el-form-item label="指数">
          <el-select v-model="form.tsCode" class="w-full">
            <el-option v-for="c in codes" :key="c" :label="indexName(c) + ' ' + c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="可选" />
        </el-form-item>
        <el-form-item label="成本价">
          <el-input-number v-model="form.costPrice" :min="0.0001" :precision="4" class="w-full" />
        </el-form-item>
        <el-form-item label="持仓量">
          <el-input-number v-model="form.shares" :min="0.01" :precision="2" class="w-full" />
        </el-form-item>
        <el-form-item label="买入日期">
          <el-date-picker v-model="form.buyDate" type="date" value-format="YYYY-MM-DD" class="w-full" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">确定</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { positionApi, quoteApi, type PositionRow, type PositionSummary } from '@/api'
import { dirClass, fmtNum, fmtPct, indexName } from '@/utils/format'

const codes = ref<string[]>([])
const data = ref<PositionSummary>({ list: [], totalMarketValue: 0, positionLimitPct: 20, disclaimer: '' })
const dialogVisible = ref(false)
const form = reactive({ tsCode: '', name: '', costPrice: 1, shares: 1, buyDate: '', note: '' })

async function load() {
  try {
    data.value = await positionApi.pnl()
  } catch {
    // 拦截器已提示
  }
}

async function save() {
  if (!form.tsCode) {
    ElMessage.warning('请选择指数')
    return
  }
  try {
    await positionApi.add({
      tsCode: form.tsCode,
      name: form.name || undefined,
      costPrice: form.costPrice,
      shares: form.shares,
      buyDate: form.buyDate || undefined,
      note: form.note || undefined,
    })
    dialogVisible.value = false
    ElMessage.success('已录入')
    await load()
  } catch {
    // 拦截器已提示
  }
}

async function remove(id: number) {
  try {
    await positionApi.delete(id)
    await load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(async () => {
  await load()
  try {
    codes.value = await quoteApi.list()
  } catch {
    // 拦截器已提示
  }
})
</script>

<style scoped>
.pos {
  padding: 16px 18px;
}
.pos__head {
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

.metrics {
  display: flex;
  gap: 10px;
  margin: 14px 0 6px;
}
.metric {
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
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

.bar {
  display: inline-block;
  width: 60px;
  height: 6px;
  border-radius: 3px;
  background: var(--bg-soft);
  border: 1px solid var(--border);
  overflow: hidden;
  vertical-align: middle;
  margin-right: 4px;
}
.bar__fill {
  height: 100%;
  border-radius: 3px;
  background: var(--up);
}
.bar__fill--warn {
  background: var(--down);
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
