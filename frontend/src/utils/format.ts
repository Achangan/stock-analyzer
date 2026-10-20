/** 数字、涨跌、日期格式化工具（前端统一出口，保证口径一致） */

/** 指数代码 → 中文名称 */
const INDEX_NAMES: Record<string, string> = {
  '000300.SH': '沪深300',
  '000905.SH': '中证500',
  '000001.SH': '上证指数',
  '399006.SZ': '创业板指',
  '000688.SH': '科创50',
}

export function indexName(code: string): string {
  return INDEX_NAMES[code] ?? code
}

/** 保留小数位；null/undefined/NaN 统一显示为 '—' */
export function fmtNum(v: number | null | undefined, digits = 2): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '—'
  return v.toFixed(digits)
}

/** 百分比，带正负号 */
export function fmtPct(v: number | null | undefined, digits = 2): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '—'
  const sign = v > 0 ? '+' : ''
  return `${sign}${v.toFixed(digits)}%`
}

/** 千分位整数 */
export function fmtInt(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '—'
  return Math.round(v).toLocaleString('en-US')
}

/** 金额（元）→ 亿/万，便于阅读 */
export function fmtAmount(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '—'
  const abs = Math.abs(v)
  if (abs >= 1e8) return `${(v / 1e8).toFixed(2)} 亿`
  if (abs >= 1e4) return `${(v / 1e4).toFixed(2)} 万`
  return v.toFixed(2)
}

/** 成交量（股）→ 亿手/万手 */
export function fmtVol(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '—'
  const hands = v / 100
  if (Math.abs(hands) >= 1e8) return `${(hands / 1e8).toFixed(2)} 亿手`
  if (Math.abs(hands) >= 1e4) return `${(hands / 1e4).toFixed(2)} 万手`
  return `${hands.toFixed(0)} 手`
}

export type Direction = 'up' | 'down' | 'flat'

/** 涨跌方向：>0 涨、<0 跌、其余平 */
export function direction(v: number | null | undefined): Direction {
  if (v === null || v === undefined || Number.isNaN(v) || v === 0) return 'flat'
  return v > 0 ? 'up' : 'down'
}

/** 方向 → CSS 工具类（红涨绿跌） */
export function dirClass(v: number | null | undefined): string {
  return `c-${direction(v)}`
}

/** 方向 → 实际颜色值，供 ECharts 使用 */
export function dirColor(v: number | null | undefined): string {
  const map: Record<Direction, string> = {
    up: 'var(--up)',
    down: 'var(--down)',
    flat: 'var(--flat)',
  }
  return map[direction(v)]
}

/** 走势标签 */
export function trendTag(trend: string | null | undefined): { text: string; cls: string } {
  if (trend === '多头排列') return { text: '多头排列', cls: 'tag--up' }
  if (trend === '空头排列') return { text: '空头排列', cls: 'tag--down' }
  if (trend === '震荡') return { text: '震荡', cls: 'tag--flat' }
  return { text: '数据不足', cls: 'tag--flat' }
}

/** 乖离率语义：正乖离偏热、负乖离偏冷 */
export function biasTag(bias: number | null | undefined): { text: string; cls: string } | null {
  if (bias === null || bias === undefined || Number.isNaN(bias)) return null
  if (bias > 5) return { text: '偏离MA20过大', cls: 'tag--up' }
  if (bias < -5) return { text: '低于MA20较多', cls: 'tag--down' }
  return { text: '贴合MA20', cls: 'tag--flat' }
}

/** 20261019 / 2026-10-19 → 10-19 */
export function shortDate(date: string | null | undefined): string {
  if (!date) return '—'
  const s = date.replace(/-/g, '')
  if (s.length !== 8) return date
  return `${s.slice(4, 6)}-${s.slice(6, 8)}`
}

/** 日期 → YYYY-MM-DD */
export function isoDate(date: string | null | undefined): string {
  if (!date) return '—'
  const s = date.replace(/-/g, '')
  if (s.length !== 8) return date
  return `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`
}

/** 今天起往前推 N 天的 YYYY-MM-DD */
export function daysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().slice(0, 10)
}
