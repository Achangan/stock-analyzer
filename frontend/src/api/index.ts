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

/** 学习模块：知识库目录树节点 */
export interface LearnNode {
  label: string
  path?: string
  children?: LearnNode[]
}

export interface LearnFile {
  path: string
  title: string
  content: string
}

export const learnApi = {
  tree: () => http.get<LearnNode[]>('/report/learn/tree').then((r) => r.data),
  file: (path: string) =>
    http.get<LearnFile>('/report/learn/file', { params: { path } }).then((r) => r.data),
}

/** 因子打分 */
export interface FactorRow {
  tsCode: string
  latestClose: number
  trend: string | null
  score: number
  raw: Record<string, number | null>
}

export interface FactorConfig {
  id: number
  name: string
  weights: string
  isDefault: boolean
  updatedAt: string
}

export interface FactorScoreResult {
  weights: Record<string, number>
  list: FactorRow[]
  disclaimer: string
}

export const factorApi = {
  score: (weights: Record<string, number>, configId?: number) =>
    http
      .get<FactorScoreResult>('/analysis/factor/score', {
        params: configId ? { configId } : { weights: JSON.stringify(weights) },
      })
      .then((r) => r.data),
  configs: () => http.get<FactorConfig[]>('/analysis/factor/configs').then((r) => r.data),
  saveConfig: (body: { id?: number; name: string; weights: Record<string, number> }) =>
    http.post<{ id: number; ok: boolean }>('/analysis/factor/config', body).then((r) => r.data),
  setDefault: (id: number) =>
    http.post(`/analysis/factor/config/${id}/default`).then((r) => r.data),
  deleteConfig: (id: number) =>
    http.delete(`/analysis/factor/config/${id}`).then((r) => r.data),
}

/** 策略回测 */
export interface NavPoint {
  date: string
  nav: number
  bench: number
  dd: number
}

export interface BacktestResult {
  id: number
  tsCode: string
  strategy: string
  fast: number
  slow: number
  startDate: string
  endDate: string
  totalReturn: number
  benchmarkReturn: number
  maxDrawdown: number
  winRate: number
  trades: number
  nav: NavPoint[]
  disclaimer: string
}

export interface BacktestHistoryRow {
  id: number
  tsCode: string
  strategy: string
  params: string
  startDate: string
  endDate: string
  totalReturn: number
  maxDrawdown: number
  winRate: number
  createdAt: string
}

export const backtestApi = {
  maCross: (body: { tsCode: string; fast: number; slow: number; start?: string; end?: string; initial?: number }) =>
    http.post<BacktestResult>('/analysis/backtest/ma-cross', body).then((r) => r.data),
  history: (tsCode?: string, limit = 20) =>
    http
      .get<BacktestHistoryRow[]>('/analysis/backtest/history', { params: { tsCode, limit } })
      .then((r) => r.data),
}

/** 自选 / 预警 */
export interface WatchItem {
  id: number
  tsCode: string
  note: string | null
  sort: number
  createdAt: string
}

export interface WatchlistResult {
  list: WatchItem[]
  unreadCodes: string[]
}

export interface AlertRule {
  id: number
  tsCode: string
  ruleType: string
  threshold: number | null
  enabled: boolean
  createdAt: string
}

export interface AlertEvent {
  id: number
  ruleId: number
  tsCode: string
  tradeDate: string | null
  message: string
  readFlag: boolean
  createdAt: string
}

export const portfolioApi = {
  watchlist: () => http.get<WatchlistResult>('/portfolio/watchlist').then((r) => r.data),
  addWatch: (tsCode: string, note?: string) =>
    http.post<{ id: number; ok: boolean }>('/portfolio/watchlist', { tsCode, note }).then((r) => r.data),
  deleteWatch: (id: number) => http.delete(`/portfolio/watchlist/${id}`).then((r) => r.data),
  rules: () => http.get<AlertRule[]>('/portfolio/alert/rules').then((r) => r.data),
  addRule: (body: { tsCode: string; ruleType: string; threshold?: number }) =>
    http.post<{ id: number; ok: boolean }>('/portfolio/alert/rule', body).then((r) => r.data),
  toggleRule: (id: number) => http.post(`/portfolio/alert/rule/${id}/toggle`).then((r) => r.data),
  deleteRule: (id: number) => http.delete(`/portfolio/alert/rule/${id}`).then((r) => r.data),
  events: (unread = false, limit = 50) =>
    http.get<AlertEvent[]>('/portfolio/alert/events', { params: { unread, limit } }).then((r) => r.data),
  evaluate: () =>
    http
      .post<{ evaluated: number; triggered: string[]; disclaimer: string }>('/portfolio/alert/evaluate')
      .then((r) => r.data),
  markRead: (id: number) => http.post(`/portfolio/alert/events/${id}/read`).then((r) => r.data),
  markAllRead: () => http.post('/portfolio/alert/events/read-all').then((r) => r.data),
}

/** 持仓管理 */
export interface PositionRow {
  id: number
  tsCode: string
  name: string | null
  costPrice: number
  shares: number
  buyDate: string | null
  note: string | null
  costValue: number
  latestPrice?: number
  marketValue?: number
  pnl?: number
  pnlPct?: number
  weightPct?: number
  overWeight?: boolean
}

export interface PositionSummary {
  totalMarketValue: number
  positionLimitPct: number
  list: PositionRow[]
  disclaimer: string
}

export const positionApi = {
  pnl: () => http.get<PositionSummary>('/portfolio/position/pnl').then((r) => r.data),
  add: (body: { tsCode: string; name?: string; costPrice: number; shares: number; buyDate?: string; note?: string }) =>
    http.post<{ id: number; ok: boolean }>('/portfolio/position', body).then((r) => r.data),
  delete: (id: number) => http.delete(`/portfolio/position/${id}`).then((r) => r.data),
}

/** 个股（daily_quote + stock_basic） */
export interface StockBrief {
  tsCode: string
  name: string
  industry: string | null
  listDate: string | null
  latestClose: number | null
  latestPct: number | null
}

export interface StockDetail {
  tsCode: string
  name: string
  industry: string | null
  listDate: string | null
  startDate: string | null
  endDate: string | null
  rowsCount: number
  latestClose: number | null
  latestPct: number | null
}

export interface StockListResult {
  list: StockBrief[]
  total: number
  page: number
  size: number
}

export const stockApi = {
  industries: () => http.get<string[]>('/quote/stock/industries').then((r) => r.data),
  list: (
    keyword?: string,
    industry?: string,
    page = 1,
    size = 20,
    sort?: 'close' | 'pct',
    order?: 'asc' | 'desc',
  ) =>
    http
      .get<StockListResult>('/quote/stock/list', {
        params: { keyword, industry, page, size, sort, order },
      })
      .then((r) => r.data),
  detail: (code: string) => http.get<StockDetail>(`/quote/stock/${encodeURIComponent(code)}`).then((r) => r.data),
  recent: (code: string, limit = 61) =>
    http
      .get<IndexDaily[]>(`/quote/stock/${encodeURIComponent(code)}/recent`, { params: { limit } })
      .then((r) => r.data),
  range: (code: string, start?: string, end?: string) =>
    http
      .get<IndexDaily[]>(`/quote/stock/${encodeURIComponent(code)}/range`, { params: { start, end } })
      .then((r) => r.data),
}
