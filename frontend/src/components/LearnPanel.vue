<template>
  <section class="card learn">
    <div class="learn__head">
      <div class="title">
        <h2>理财学习</h2>
        <span class="hint">内容来自理财知识库（VitePress 源文件实时渲染）</span>
      </div>
    </div>

    <div class="learn__body">
      <aside class="tree">
        <el-tree
          :data="tree"
          :props="{ label: 'label', children: 'children' }"
          node-key="path"
          :default-expanded-keys="['guide', 'daily']"
          highlight-current
          @node-click="onNodeClick"
        />
      </aside>

      <article class="article">
        <div v-if="loading" class="loading">
          <div class="skeleton" style="height: 20px; width: 45%" />
          <div class="skeleton" style="height: 14px; width: 80%; margin-top: 16px" />
          <div class="skeleton" style="height: 14px; width: 66%; margin-top: 10px" />
          <div class="skeleton" style="height: 14px; width: 74%; margin-top: 10px" />
        </div>
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div v-else-if="html" class="markdown-body" v-html="html" />
        <div v-else class="empty">
          <span class="empty__icon">📚</span>
          <span>从左侧目录选择一篇主题文章或每日日志开始学习</span>
        </div>
      </article>
    </div>

    <p class="risk">风险提示：学习内容仅为个人笔记整理，不构成任何投资建议。市场有风险，投资需谨慎。</p>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { marked } from 'marked'
import { learnApi, type LearnNode } from '@/api'

const tree = ref<LearnNode[]>([])
const html = ref('')
const loading = ref(false)

async function loadTree() {
  try {
    tree.value = await learnApi.tree()
  } catch {
    // 拦截器已提示
  }
}

async function onNodeClick(node: LearnNode) {
  if (!node.path) return
  loading.value = true
  try {
    const res = await learnApi.file(node.path)
    html.value = marked.parse(res.content, { async: false })
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

onMounted(loadTree)
</script>

<style scoped>
.learn {
  padding: 16px 18px;
}
.learn__head {
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

.learn__body {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 16px;
  margin-top: 14px;
  min-height: 420px;
}
.tree {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-soft);
  padding: 10px;
  max-height: 640px;
  overflow: auto;
}
.tree :deep(.el-tree) {
  background: transparent;
  color: var(--text-2);
}
.tree :deep(.el-tree-node__content:hover),
.tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: var(--card);
  color: var(--text);
}

.article {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 20px 26px;
  max-height: 640px;
  overflow: auto;
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

.markdown-body {
  font-size: 14px;
  line-height: 1.8;
  color: var(--text);
}
.markdown-body :deep(h1) {
  font-size: 22px;
  margin: 0 0 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border);
}
.markdown-body :deep(h2) {
  font-size: 18px;
  margin: 22px 0 10px;
}
.markdown-body :deep(h3) {
  font-size: 15px;
  margin: 18px 0 8px;
}
.markdown-body :deep(p) {
  margin: 8px 0;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 22px;
  margin: 8px 0;
}
.markdown-body :deep(li) {
  margin: 4px 0;
}
.markdown-body :deep(a) {
  color: var(--up);
}
.markdown-body :deep(blockquote) {
  margin: 10px 0;
  padding: 8px 14px;
  border-left: 3px solid var(--warn);
  background: color-mix(in srgb, var(--warn) 8%, transparent);
  color: var(--text-2);
}
.markdown-body :deep(code) {
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 1px 6px;
  font-family: var(--mono);
  font-size: 12px;
}
.markdown-body :deep(pre) {
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
  overflow: auto;
}
.markdown-body :deep(pre code) {
  background: transparent;
  border: none;
  padding: 0;
}
.markdown-body :deep(table) {
  border-collapse: collapse;
  margin: 12px 0;
  width: 100%;
  font-size: 13px;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--border);
  padding: 6px 10px;
  text-align: left;
}
.markdown-body :deep(th) {
  background: var(--bg-soft);
}
.markdown-body :deep(hr) {
  border: none;
  border-top: 1px solid var(--border);
  margin: 18px 0;
}
.markdown-body :deep(img) {
  max-width: 100%;
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

@media (max-width: 900px) {
  .learn__body {
    grid-template-columns: 1fr;
  }
  .tree {
    max-height: 240px;
  }
}
</style>
