import { ref } from 'vue'
import { quoteApi, type IndexDaily } from '@/api'

export interface RangeOption {
  key: string
  label: string
  days: number
}

/** K 线区间选项：days=0 表示取全部历史 */
export const RANGE_OPTIONS: RangeOption[] = [
  { key: '60', label: '近60日', days: 60 },
  { key: '120', label: '近120日', days: 120 },
  { key: '250', label: '近250日', days: 250 },
  { key: 'all', label: '全部', days: 0 },
]

const DEFAULT_RANGE: RangeOption = { key: '120', label: '近120日', days: 120 }

/** K 线数据：按区间加载指定指数日线（时间升序） */
export function useKline(initialKey = '120') {
  const loading = ref(false)
  const bars = ref<IndexDaily[]>([])
  const rangeKey = ref(initialKey)

  async function load(code: string) {
    if (!code) return
    loading.value = true
    try {
      const opt = RANGE_OPTIONS.find((o) => o.key === rangeKey.value) ?? DEFAULT_RANGE
      const data =
        opt.days === 0
          ? await quoteApi.range(code)
          : (await quoteApi.recent(code, opt.days)).slice().reverse()
      bars.value = data
    } catch {
      bars.value = []
    } finally {
      loading.value = false
    }
  }

  return { loading, bars, rangeKey, load }
}
