import { ref } from 'vue'
import { analysisApi, quoteApi, type IndexSummary } from '@/api'

/** 概览行 = 分析摘要 + 迷你走势收盘序列（时间升序） */
export interface OverviewRow extends IndexSummary {
  spark: number[]
}

/** 市场概览数据：一次性拉取指数列表、批量摘要与迷你走势 */
export function useOverview() {
  const loading = ref(false)
  const rows = ref<OverviewRow[]>([])
  const latestDate = ref('')

  async function load() {
    loading.value = true
    try {
      const codes = await quoteApi.list()
      if (!codes.length) {
        rows.value = []
        latestDate.value = ''
        return
      }
      const summaries = await analysisApi.summaries(codes)
      const sparks = await Promise.all(
        codes.map((c) =>
          quoteApi
            .recent(c, 60)
            .then((d) => d.map((x) => x.close).reverse())
            .catch(() => [] as number[]),
        ),
      )
      const sparkMap = new Map(codes.map((c, i) => [c, sparks[i]]))
      rows.value = summaries.map((s) => ({ ...s, spark: sparkMap.get(s.tsCode) ?? [] }))
      latestDate.value = rows.value[0]?.latestDate ?? ''
    } catch {
      rows.value = []
    } finally {
      loading.value = false
    }
  }

  return { loading, rows, latestDate, load }
}
