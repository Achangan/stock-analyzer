<template>
  <section class="card wl">
    <div class="wl__head">
      <div class="title">
        <h2>自选跟踪 + 预警</h2>
        <span class="hint">维护自选列表与预警规则，触发后自动记录事件</span>
      </div>
      <div class="actions">
        <el-badge :value="unreadCount" :hidden="unreadCount === 0">
          <el-button :icon="Bell" @click="eventsVisible = true">预警事件</el-button>
        </el-badge>
        <el-button type="primary" :loading="evaluating" :icon="Refresh" @click="evaluate">立即评估</el-button>
      </div>
    </div>

    <div class="add-bar">
      <el-select v-model="addCode" placeholder="添加自选指数" clearable class="add-select">
        <el-option v-for="c in available" :key="c" :label="indexName(c) + ' ' + c" :value="c" />
      </el-select>
      <el-input v-model="addNote" placeholder="备注" class="add-input" />
      <el-button :icon="Plus" @click="addWatch">添加</el-button>
    </div>

    <el-table :data="list" row-key="id" style="width: 100%">
      <el-table-column label="指数" min-width="160">
        <template #default="{ row }">
          <div class="idx" :class="{ 'idx--alert': unreadCodes.includes(row.tsCode) }">
            <span class="idx__name">{{ indexName(row.tsCode) }}</span>
            <span class="idx__code num">{{ row.tsCode }}</span>
            <el-tag v-if="unreadCodes.includes(row.tsCode)" size="small" type="danger" class="idx__tag">新预警</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="备注" prop="note" min-width="140" />
      <el-table-column label="操作" width="140" align="center">
        <template #default="{ row }">
          <el-button size="small" :icon="Bell" @click="openRule(row.tsCode)">加规则</el-button>
          <el-button size="small" type="danger" :icon="Delete" @click="remove(row.id)">删</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="rule-card">
      <h3>预警规则</h3>
      <el-table :data="rules" row-key="id" style="width: 100%" size="small">
        <el-table-column label="指数" min-width="120">
          <template #default="{ row }">
            {{ row.tsCode === '*' ? '全部自选' : indexName(row.tsCode) }}
          </template>
        </el-table-column>
        <el-table-column label="规则" min-width="110" prop="ruleType" />
        <el-table-column label="阈值" width="90" align="right">
          <template #default="{ row }">
            <span class="num">{{ row.threshold }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="72" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="toggleRule(row.id)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center">
          <template #default="{ row }">
            <el-button size="small" type="danger" :icon="Delete" @click="removeRule(row.id)" />
          </template>
        </el-table-column>
      </el-table>
    </div>

    <p class="risk">预警基于历史行情规则触发，仅供参考，不构成投资建议。</p>

    <!-- 新增规则弹窗 -->
    <el-dialog v-model="ruleDialog" title="新增预警规则" width="400px">
      <el-form label-width="80px">
        <el-form-item label="指数">
          <el-select v-model="ruleForm.tsCode" class="w-full">
            <el-option label="全部自选 (*)" value="*" />
            <el-option v-for="c in list" :key="c.tsCode" :label="indexName(c.tsCode)" :value="c.tsCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="规则类型">
          <el-select v-model="ruleForm.ruleType" class="w-full">
            <el-option label="MA20 乖离率超阈值" value="bias20" />
            <el-option label="当日涨跌幅超阈值" value="pct_chg" />
            <el-option label="收盘价跌破 MA20" value="ma20_break" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="ruleForm.ruleType !== 'ma20_break'" label="阈值(%)">
          <el-input-number v-model="ruleForm.threshold" :min="0.01" :step="0.5" class="w-full" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialog = false">取消</el-button>
        <el-button type="primary" @click="saveRule">确定</el-button>
      </template>
    </el-dialog>

    <!-- 预警事件抽屉 -->
    <el-drawer v-model="eventsVisible" title="预警事件" size="400px">
      <div class="events">
        <div v-for="e in events" :key="e.id" class="event" :class="{ 'event--read': e.readFlag }">
          <div class="event__head">
            <span class="event__code">{{ indexName(e.tsCode) }}</span>
            <span class="event__date num">{{ e.tradeDate }}</span>
          </div>
          <div class="event__msg">{{ e.message }}</div>
          <div class="event__foot">
            <el-button v-if="!e.readFlag" size="small" @click="markRead(e.id)">标记已读</el-button>
            <span v-else class="event__read">已读</span>
          </div>
        </div>
        <div v-if="events.length === 0" class="empty">
          <span>暂无预警事件</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="eventsVisible = false">关闭</el-button>
        <el-button type="primary" @click="markAllRead">全部已读</el-button>
      </template>
    </el-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Bell, Delete, Plus, Refresh } from '@element-plus/icons-vue'
import { portfolioApi, quoteApi, type WatchItem, type AlertRule, type AlertEvent } from '@/api'
import { indexName } from '@/utils/format'

const list = ref<WatchItem[]>([])
const rules = ref<AlertRule[]>([])
const events = ref<AlertEvent[]>([])
const unreadCodes = ref<string[]>([])
const available = ref<string[]>([])
const addCode = ref('')
const addNote = ref('')
const evaluating = ref(false)
const ruleDialog = ref(false)
const ruleForm = ref({ tsCode: '*', ruleType: 'bias20', threshold: 0.5 })
const eventsVisible = ref(false)

const unreadCount = computed(() => events.value.filter((e) => !e.readFlag).length)

async function load() {
  try {
    const res = await portfolioApi.watchlist()
    list.value = res.list
    unreadCodes.value = res.unreadCodes
  } catch {
    // 拦截器已提示
  }
}

async function loadRules() {
  try {
    rules.value = await portfolioApi.rules()
  } catch {
    // 拦截器已提示
  }
}

async function loadEvents() {
  try {
    events.value = await portfolioApi.events(true)
  } catch {
    // 拦截器已提示
  }
}

async function addWatch() {
  if (!addCode.value) return
  await portfolioApi.addWatch(addCode.value, addNote.value)
  addCode.value = ''
  addNote.value = ''
  ElMessage.success('已添加自选')
  await load()
}

async function remove(id: number) {
  await portfolioApi.deleteWatch(id)
  await load()
}

function openRule(tsCode: string) {
  ruleForm.value.tsCode = tsCode
  ruleDialog.value = true
}

async function saveRule() {
  await portfolioApi.addRule(ruleForm.value)
  ruleDialog.value = false
  await loadRules()
}

async function toggleRule(id: number) {
  await portfolioApi.toggleRule(id)
}

async function removeRule(id: number) {
  await portfolioApi.deleteRule(id)
  await loadRules()
}

async function evaluate() {
  evaluating.value = true
  try {
    await portfolioApi.evaluate()
    ElMessage.success('评估完成')
    await load()
    await loadEvents()
  } catch {
    // 拦截器已提示
  } finally {
    evaluating.value = false
  }
}

async function markRead(id: number) {
  await portfolioApi.markRead(id)
  await loadEvents()
  await load()
}

async function markAllRead() {
  await portfolioApi.markAllRead()
  await loadEvents()
  await load()
}

onMounted(async () => {
  await load()
  await loadRules()
  await loadEvents()
  try {
    available.value = await quoteApi.list()
  } catch {
    // 拦截器已提示
  }
})
</script>

<style scoped>
.wl {
  padding: 16px 18px;
}
.wl__head {
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

.add-bar {
  display: flex;
  gap: 8px;
  margin: 14px 0 10px;
  flex-wrap: wrap;
}
.add-select {
  width: 220px;
}
.add-input {
  width: 200px;
}

.idx {
  display: flex;
  align-items: center;
  gap: 8px;
}
.idx__name {
  font-weight: 600;
}
.idx__code {
  font-size: 12px;
  color: var(--text-3);
}
.idx__tag {
  margin-left: 4px;
}
.idx--alert {
  color: var(--up);
}

.rule-card {
  margin-top: 14px;
  padding: 12px 14px;
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.rule-card h3 {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
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

.events {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.event {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
}
.event--read {
  opacity: 0.6;
}
.event__head {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}
.event__code {
  font-weight: 600;
}
.event__date {
  color: var(--text-3);
}
.event__msg {
  margin-top: 6px;
  font-size: 13px;
}
.event__foot {
  margin-top: 8px;
  text-align: right;
}
.event__read {
  font-size: 12px;
  color: var(--text-3);
}
.empty {
  text-align: center;
  color: var(--text-3);
  padding: 40px 0;
}
</style>
