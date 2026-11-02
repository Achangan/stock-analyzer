"""数据巡检：覆盖度、完整性、异常值与污染检查

用法：
    python check_data.py            # 打印巡检报告，发现严重问题返回码 1
    python check_data.py --no-fail  # 始终返回 0

检查项：
    1. 各表行数 / 代码数 / 日期范围
    2. 个股覆盖率（stock_basic vs daily_quote）
    3. 交易日完整性（以 index_daily 交易日历为基准，统计缺口与滞后）
    4. 行情异常（OHLC 非法、涨跌幅与收盘价不一致、零成交量）
    5. 指数代码混入个股表检查
"""
import argparse
import bisect
from collections import Counter
from datetime import datetime

import db_mysql as dbm

INDEX_CODES = ("000001.SH", "000300.SH", "000905.SH", "399006.SZ", "000688.SH")


def scalar(cur, sql, args=None):
    cur.execute(sql, args)
    row = cur.fetchone()
    return row[0] if row else None


def main() -> int:
    parser = argparse.ArgumentParser(description="数据巡检")
    parser.add_argument("--no-fail", action="store_true", help="无论是否有问题都返回 0")
    args = parser.parse_args()

    conn = dbm.get_conn()
    cur = conn.cursor()
    issues: list[str] = []
    print("=" * 66)
    print(f"数据巡检 {datetime.now():%Y-%m-%d %H:%M:%S}")
    print("=" * 66)

    total_stocks = scalar(cur, "SELECT COUNT(*) FROM stock_basic WHERE status=1")
    covered, rows, min_d, max_d = (
        scalar(cur, "SELECT COUNT(DISTINCT ts_code) FROM daily_quote"),
        scalar(cur, "SELECT COUNT(*) FROM daily_quote"),
        scalar(cur, "SELECT MIN(trade_date) FROM daily_quote"),
        scalar(cur, "SELECT MAX(trade_date) FROM daily_quote"),
    )
    idx_codes, idx_rows = (
        scalar(cur, "SELECT COUNT(DISTINCT ts_code) FROM index_daily"),
        scalar(cur, "SELECT COUNT(*) FROM index_daily"),
    )
    print(f"[概览] stock_basic {total_stocks} 只 | daily_quote {covered} 只 / {rows:,} 行 "
          f"| {min_d} ~ {max_d}")
    print(f"[概览] index_daily {idx_codes} 只 / {idx_rows:,} 行")

    ratio = covered / total_stocks * 100 if total_stocks else 0
    print(f"[覆盖] 个股覆盖率 {ratio:.1f}%（{covered}/{total_stocks}）")
    if ratio < 95:
        issues.append(f"个股覆盖率仅 {ratio:.1f}%，低于 95%")

    # 交易日历：index_daily 与「当日覆盖数 >=100 的个股日期」取并集
    cur.execute("SELECT DISTINCT trade_date FROM index_daily")
    cal = {r[0] for r in cur.fetchall()}
    cur.execute("SELECT trade_date FROM daily_quote GROUP BY trade_date HAVING COUNT(*) >= 100")
    cal |= {r[0] for r in cur.fetchall()}
    calendar = sorted(cal)
    cal_str = [d.strftime("%Y-%m-%d") for d in calendar]
    latest_cal = calendar[-1] if calendar else None
    print(f"[日历] 交易日 {len(calendar)} 天，最新 {latest_cal}")

    # 逐代码完整性
    cur.execute("SELECT ts_code, COUNT(*), MIN(trade_date), MAX(trade_date) "
                "FROM daily_quote GROUP BY ts_code")
    per_code = cur.fetchall()
    stale, gaps, newish = [], [], 0
    for code, n, mn, mx in per_code:
        expected = bisect.bisect_right(calendar, mx) - bisect.bisect_left(calendar, mn)
        missing = expected - n
        if missing > 0:
            gaps.append((code, missing, n))
        if latest_cal and mx < latest_cal:
            stale.append((code, mx))
        if calendar and mn > calendar[min(20, len(calendar) - 1)]:
            newish += 1
    gaps.sort(key=lambda x: -x[1])
    stale.sort(key=lambda x: x[1])
    bad_gap = [g for g in gaps if g[1] / max(g[1] + g[2], 1) > 0.05]
    print(f"[完整] 存在缺口代码 {len(gaps)} 只，其中缺口 >5% 的 {len(bad_gap)} 只；"
          f"滞后于最新交易日 {len(stale)} 只；区间起点在 2023 年后 {newish} 只（可能上市较晚）")
    if bad_gap:
        print("        缺口示例：" + "; ".join(f"{c} 缺{m}" for c, m, _ in bad_gap[:5]))
    if stale:
        print("        滞后示例：" + "; ".join(f"{c} 至{d}" for c, d in stale[:5]))
    if bad_gap:
        issues.append(f"{len(bad_gap)} 只股票存在 >5% 的交易日缺口")
    if stale:
        issues.append(f"{len(stale)} 只股票未更新到最新交易日 {latest_cal}")

    # 行情异常
    bad_ohlc = scalar(cur, "SELECT COUNT(*) FROM daily_quote WHERE close<=0 OR high<low "
                           "OR high<GREATEST(open,close) OR low>LEAST(open,close)")
    bad_pct = scalar(cur, "SELECT COUNT(*) FROM daily_quote WHERE pre_close>0 "
                          "AND ABS(pct_chg-(close/pre_close-1)*100)>0.5")
    zero_vol = scalar(cur, "SELECT COUNT(*) FROM daily_quote WHERE vol<=0 OR amount<=0")
    print(f"[异常] OHLC 非法 {bad_ohlc} 行 | 涨跌幅不一致 {bad_pct} 行 | 零成交 {zero_vol} 行")
    if bad_ohlc:
        issues.append(f"{bad_ohlc} 行 OHLC 数据非法")
    if bad_pct:
        issues.append(f"{bad_pct} 行涨跌幅与收盘价不一致")

    # 指数代码污染个股表
    pollution = scalar(cur, "SELECT COUNT(*) FROM daily_quote WHERE ts_code IN "
                            "('000001.SH','000300.SH','000905.SH','399006.SZ','000688.SH')")
    print(f"[污染] 指数代码混入 daily_quote：{pollution} 行")
    if pollution:
        issues.append(f"daily_quote 混入指数行情 {pollution} 行")

    # 指数表自身完整性
    print("[指数] index_daily 完整性")
    cur.execute("SELECT ts_code, COUNT(*), MIN(trade_date), MAX(trade_date) "
                "FROM index_daily GROUP BY ts_code ORDER BY ts_code")
    idx_stale = []
    for code, n, mn, mx in cur.fetchall():
        expected = bisect.bisect_right(calendar, mx) - bisect.bisect_left(calendar, mn)
        flag = "" if n == expected else f"（缺 {expected - n} 天）"
        if latest_cal and mx < latest_cal:
            flag += f"（滞后至 {mx}）"
            idx_stale.append(code)
        print(f"        指数 {code}: {n} 行 {mn} ~ {mx}{flag}")
    if idx_stale:
        issues.append(f"{len(idx_stale)} 个指数未更新到最新交易日 {latest_cal}")

    print("=" * 66)
    if issues:
        print(f"巡检结论：发现 {len(issues)} 类问题")
        for i, msg in enumerate(issues, 1):
            print(f"  {i}. {msg}")
    else:
        print("巡检结论：未发现异常")
    conn.close()
    return 0 if args.no_fail or not issues else 1


if __name__ == "__main__":
    raise SystemExit(main())
