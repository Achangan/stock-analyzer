<template>
  <header class="app-header">
    <div class="brand">
      <div class="brand__mark">
        <svg viewBox="0 0 32 32" width="24" height="24" aria-hidden="true">
          <path
            d="M3 22 L10 13 L16 18 L23 8 L29 12"
            fill="none"
            stroke="currentColor"
            stroke-width="2.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
          <circle cx="23" cy="8" r="2.4" fill="currentColor" />
        </svg>
      </div>
      <div class="brand__text">
        <h1>A股指数分析系统</h1>
        <p>沪深300 · 中证500 · 上证指数 · 创业板指</p>
      </div>
    </div>

    <div class="actions">
      <span v-if="latestDate" class="data-badge">
        <el-icon><Calendar /></el-icon>
        数据截至 {{ shortDate(latestDate) }}
      </span>

      <button
        class="ghost-btn"
        :class="{ 'is-spinning': loading }"
        type="button"
        title="刷新数据"
        @click="emit('refresh')"
      >
        <el-icon><Refresh /></el-icon>
      </button>

      <button
        class="ghost-btn"
        type="button"
        :title="theme === 'dark' ? '切换到浅色主题' : '切换到深色主题'"
        @click="emit('toggle-theme')"
      >
        <el-icon v-if="theme === 'dark'"><Sunny /></el-icon>
        <el-icon v-else><Moon /></el-icon>
      </button>
    </div>
  </header>
</template>

<script setup lang="ts">
import { Calendar, Refresh, Sunny, Moon } from '@element-plus/icons-vue'
import { shortDate } from '@/utils/format'

defineProps<{
  latestDate?: string
  loading?: boolean
  theme: string
}>()

const emit = defineEmits<{
  (e: 'refresh'): void
  (e: 'toggle-theme'): void
}>()
</script>

<style scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 24px;
  background: color-mix(in srgb, var(--bg) 88%, transparent);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--border);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.brand__mark {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  color: #fff;
  background: linear-gradient(135deg, var(--accent), #14b8a6);
  box-shadow: 0 4px 14px var(--accent-soft);
  flex-shrink: 0;
}

.brand__text h1 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.2px;
  white-space: nowrap;
}

.brand__text p {
  margin: 1px 0 0;
  font-size: 12px;
  color: var(--text-3);
  white-space: nowrap;
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.data-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 11px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--text-2);
  background: var(--bg-soft);
  border: 1px solid var(--border);
  white-space: nowrap;
}

.ghost-btn {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  cursor: pointer;
  color: var(--text-2);
  background: var(--bg-soft);
  border: 1px solid var(--border);
  transition: all 0.16s ease;
}
.ghost-btn:hover {
  color: var(--accent);
  border-color: var(--accent);
  background: var(--accent-soft);
}
.ghost-btn.is-spinning :deep(svg) {
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 768px) {
  .app-header {
    padding: 10px 14px;
  }
  .brand__text p {
    display: none;
  }
  .data-badge {
    display: none;
  }
}
</style>
