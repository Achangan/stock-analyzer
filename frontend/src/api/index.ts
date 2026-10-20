import axios from 'axios'
import { ElMessage } from 'element-plus'

/** 指数日线（quote-service 已补全 OHLC 字段） */
export interface IndexDaily {
  tsCode: string
  tradeDate: string
  open: number | null
  high: number | null
  low: number | null
  close: number
  preClose: number | null
  pctChg: number | null
  vol: number | null
  amount: number | null
}

/** 指数分析摘要 */
export interface IndexSummary {
  tsCode: string
  latestDate: string
  latestClose: number
  latestPctChg: number | null
  ma5: number | null
  ma20: number | null
  ma60: number | null
  change20d: number | null
  change60d: number | null
  volatility20d: number | null
  ma5OverMa20: boolean | null
  ma20OverMa60: boolean | null
  trend: string | null
  bias20: number | null
}

/** 数据覆盖度 */
export interface IndexCoverage {
  tsCode: string
  startDate: string
  endDate: string
  rows: number
}

const http = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

http.interceptors.response.use(
  (res) => res,
  (err) => {
    const status = err?.response?.status
    const msg = status
      ? `请求失败（HTTP ${status}）`
      : '网络请求失败，请确认后端服务已启动'
    ElMessage.error(msg)
    return Promise.reject(err)
  },
)

export const quoteApi = {
  /** 已入库指数代码 */
  list: () => http.get<string[]>('/quote/index/list').then((r) => r.data),
  /** 最近 N 个交易日（日期降序） */
  recent: (code: string, limit = 61) =>
    http
      .get<IndexDaily[]>(`/quote/index/${encodeURIComponent(code)}/recent`, { params: { limit } })
      .then((r) => r.data),
  /** 指定区间日线（日期升序），start/end 可省略 */
  range: (code: string, start?: string, end?: string) =>
    http
      .get<IndexDaily[]>(`/quote/index/${encodeURIComponent(code)}/range`, { params: { start, end } })
      .then((r) => r.data),
  /** 数据覆盖情况 */
  coverage: () => http.get<IndexCoverage[]>('/quote/coverage').then((r) => r.data),
}

export const analysisApi = {
  summary: (code: string) =>
    http.get<IndexSummary>(`/analysis/index/${encodeURIComponent(code)}/summary`).then((r) => r.data),
  /** 批量摘要，一次拿全部指数，减少往返 */
  summaries: (codes: string[]) =>
    http
      .get<IndexSummary[]>('/analysis/index/summaries', { params: { codes: codes.join(',') } })
      .then((r) => r.data),
}

export interface ReportResult {
  report: string
  generatedAt: string
}

export const reportApi = {
  daily: () => http.post<ReportResult>('/report/daily').then((r) => r.data),
  latest: () => http.get<ReportResult>('/report/latest').then((r) => r.data),
}
