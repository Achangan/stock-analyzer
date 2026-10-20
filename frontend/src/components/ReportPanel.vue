<template>
  <section class="card report">
    <div class="report__head">
      <div class="title">
        <h2>每日分析报告</h2>
        <span class="hint">汇总 4 大指数的最新收盘、均线与区间涨跌</span>
      </div>
      <div class="report__actions">
        <el-button type="primary" :loading="generating" :icon="Document" @click="generate">
          生成报告
        </el-button>
        <el-button :disabled="!content" :icon="CopyDocument" @click="copy">复制</el-button>
        <el-button :disabled="!content" :icon="Download" @click="download">下载 .md</el-button>
      </div>
    </div>

    <div v-if="content" class="report__meta">
      <span class="badge">生成时间 {{ generatedAt || '—' }}</span>
      <span class="badge num">{{ content.length }} 字符</span>
    </div>

    <div v-if="generating" class="report__body report__body--loading">
      <div class="skeleton" style="height: 16px; width: 40%" />
      <div class="skeleton" style="height: 16px; width: 72%; margin-top: 12px" />
      <div class="skeleton" style="height: 16px; width: 58%; margin-top: 12px" />
      <div class="skeleton" style="height: 16px; width: 66%; margin-top: 12px" />
    </div>

    <pre v-else-if="content" class="report__body">{{ content }}</pre>

    <div v-else class="empty">
      <span class="empty__icon">📄</span>
      <span>还没有报告，点击「生成报告」即可按最新行情生成</span>
    </div>

    <p class="risk">
      风险提示：报告由历史行情自动汇总生成，仅供复盘参考，不构成投资建议。
    </p>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Document, Download } from '@element-plus/icons-vue'
import { reportApi } from '@/api'

const content = ref('')
const generatedAt = ref('')
const generating = ref(false)

async function generate() {
  generating.value = true
  try {
    const res = await reportApi.daily()
    content.value = res.report ?? ''
    generatedAt.value = res.generatedAt ?? ''
    if (content.value) ElMessage.success('报告已生成')
  } catch {
    // 拦截器已提示错误
  } finally {
    generating.value = false
  }
}

async function copy() {
  if (!content.value) return
  try {
    await navigator.clipboard.writeText(content.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动选择文本')
  }
}

function download() {
  if (!content.value) return
  const blob = new Blob([content.value], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `指数分析报告_${(generatedAt.value || '').slice(0, 10) || 'latest'}.md`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  ElMessage.success('已开始下载')
}
</script>

<style scoped>
.report {
  padding: 16px 18px;
}
.report__head {
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
.report__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.report__meta {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.badge {
  font-size: 12px;
  color: var(--text-3);
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 2px 10px;
}

.report__body {
  margin: 14px 0 0;
  padding: 16px 18px;
  max-height: 520px;
  overflow: auto;
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-family: var(--mono);
  font-size: 13px;
  line-height: 1.75;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
}
.report__body--loading {
  display: block;
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
