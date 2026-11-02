"""策略回测：用库内全市场日线验证 v2 多因子选股的历史有效性

用法：
    python backtest_strategy.py                    # 10 日调仓，持有 10 日，Top20
    python backtest_strategy.py --rebalance 20 --top 30
    python backtest_strategy.py --start 2023-08-01 --end 2026-11-02

口径说明：
    - 因子只用调仓日及之前的日线，收益用其后 hold 个交易日收盘价，无未来函数
    - 估值组依赖 PE/PB（日线无此字段），回测按其余四组权重归一化，不代表线上完整评分
    - 样本池剔除 ST/退市名称、价格 2~150 元、20 日均成交额 < 5000 万，并复用线上硬门槛
    - 交易成本按单边 0.05%、按换手率扣减
"""
import argparse
import json
import math
import statistics
from datetime import datetime

import pymysql

import db_mysql as dbm
from screen_stocks import assign_rank

INDEX_CODE = "000300.SH"
FIELDS = ("mom5", "mom20", "mom60", "trend", "ma20_slope", "bias20", "vol20", "rsi14",
          "volratio", "drawdown60", "amount20", "win60", "max_drop20", "fwd")

# 市场状态权重去掉估值组后，按剩余四组归一化
REGIME_WEIGHTS = {
    "进攻": {"mom": 0.34, "trend": 0.30, "vol": 0.12, "quality": 0.12},
    "均衡": {"mom": 0.26, "trend": 0.26, "vol": 0.14, "quality": 0.16},
    "防守": {"mom": 0.18, "trend": 0.22, "vol": 0.12, "quality": 0.22},
}


def load_meta(conn) -> dict[str, tuple[str, str]]:
    with conn.cursor() as cur:
        cur.execute("SELECT ts_code, name, industry FROM stock_basic")
        return {r[0]: (r[1] or "", r[2] or "未分类") for r in cur.fetchall()}


def load_index(conn) -> list[tuple[str, float]]:
    with conn.cursor() as cur:
        cur.execute("SELECT trade_date, close FROM index_daily WHERE ts_code=%s "
                    "ORDER BY trade_date", (INDEX_CODE,))
        return [(r[0].strftime("%Y-%m-%d"), float(r[1])) for r in cur.fetchall()]


def regime_at(closes: list[float], n: int) -> tuple[str, float]:
    """用截至第 n 根（含）的沪深300 判断市场状态"""
    if n < 60:
        return "均衡", 0.0
    c = closes[n]
    ma20 = sum(closes[n - 19:n + 1]) / 20
    ma60 = sum(closes[n - 59:n + 1]) / 60
    ret20 = (c / closes[n - 20] - 1) * 100
    if c > ma20 > ma60 and ret20 > 0:
        return "进攻", ret20
    if c < ma60 or ret20 < -5:
        return "防守", ret20
    return "均衡", ret20


def iter_stocks(conn):
    """流式读取全市场日线（按代码+日期排序），逐只产出，避免一次性载入内存"""
    cur = conn.cursor(pymysql.cursors.SSCursor)
    cur.execute("SELECT ts_code, trade_date, high, low, close, vol, amount "
                "FROM daily_quote ORDER BY ts_code, trade_date")
    code, bars = None, []
    for c, d, high, low, close, vol, amount in cur:
        if c != code:
            if bars:
                yield code, bars
            code, bars = c, []
        bars.append((d.strftime("%Y-%m-%d"), float(high or 0), float(low or 0),
                     float(close), float(vol or 0), float(amount or 0)))
    if bars:
        yield code, bars
    cur.close()


def stock_rows(code: str, bars: list[tuple], reb: set[str], hold: int,
               min_amount: float, min_price: float, max_price: float) -> list[tuple]:
    """单只股票在调仓日的因子快照（含未来 hold 日收益）"""
    dates = [b[0] for b in bars]
    highs = [b[1] for b in bars]
    closes = [b[3] for b in bars]
    vols = [b[4] for b in bars]
    amounts = [b[5] for b in bars]
    out = []
    for i, d in enumerate(dates):
        if d not in reb or i < 139:
            continue
        c = closes[i]
        if not (min_price <= c <= max_price):
            continue
        win = closes[i - 60:i + 1]
        daily = [win[k] / win[k - 1] - 1 for k in range(1, len(win))]
        ma5 = sum(closes[i - 4:i + 1]) / 5
        ma20 = sum(closes[i - 19:i + 1]) / 20
        ma60 = sum(closes[i - 59:i + 1]) / 60
        ma20_prev = sum(closes[i - 24:i - 4]) / 20
        above = c > ma60
        slope = (ma20 / ma20_prev - 1) * 100
        if not above or slope <= 0:
            continue
        ret20 = [closes[k] / closes[k - 1] - 1 for k in range(i - 19, i + 1)]
        vol20 = statistics.pstdev(ret20) * math.sqrt(252) * 100
        if vol20 > 60:
            continue
        gains = [max(x, 0) for x in daily[-14:]]
        losses = [max(-x, 0) for x in daily[-14:]]
        ag, al = sum(gains) / 14, sum(losses) / 14
        rsi = 100.0 if al == 0 else 100 - 100 / (1 + ag / al)
        if not (40 <= rsi <= 78):
            continue
        mom20 = (c / closes[i - 20] - 1) * 100
        mom5 = (c / closes[i - 5] - 1) * 100
        if not (0 <= mom20 <= 40):
            continue
        mom60 = (c / closes[i - 60] - 1) * 100
        if mom60 > 120:
            continue
        peak = max(highs[i - 59:i + 1])
        drawdown60 = (peak - c) / peak * 100
        if drawdown60 > 25:
            continue
        avg20 = sum(vols[i - 19:i + 1]) / 20
        amt20 = sum(amounts[i - 19:i + 1]) / 20
        if amt20 <= 0:
            amt20 = avg20 * c
        if amt20 < min_amount:
            continue
        volratio = (sum(vols[i - 4:i + 1]) / 5) / avg20 if avg20 else 1.0
        max_drop20 = min(ret20) * 100
        win60 = sum(1 for x in daily[-60:] if x > 0) / len(daily[-60:]) * 100
        fwd = (closes[i + hold] / c - 1) if i + hold < len(closes) else None
        out.append((d, code, mom5, mom20, mom60,
                    0.5 * (ma5 > ma20) + 0.5 * (ma20 > ma60), slope,
                    (c / ma20 - 1) * 100, vol20, rsi, volratio, drawdown60,
                    amt20, win60, max_drop20, fwd))
    return out


def avg_rank(vals: list[float]) -> list[float]:
    """平均秩（并列取平均）"""
    order = sorted(range(len(vals)), key=lambda i: vals[i])
    ranks = [0.0] * len(vals)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and vals[order[j + 1]] == vals[order[i]]:
            j += 1
        mid = (i + j) / 2 + 1
        for k in range(i, j + 1):
            ranks[order[k]] = mid
        i = j + 1
    return ranks


def spearman(xs: list[float], ys: list[float]) -> float | None:
    n = len(xs)
    if n < 5:
        return None
    rx, ry = avg_rank(xs), avg_rank(ys)
    mx, my = sum(rx) / n, sum(ry) / n
    cov = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
    vx = sum((a - mx) ** 2 for a in rx)
    vy = sum((b - my) ** 2 for b in ry)
    return cov / math.sqrt(vx * vy) if vx > 0 and vy > 0 else None


def score_rows(raw: list[tuple], regime: str) -> list[dict]:
    """横截面打分：因子百分位 → 四组子分 → 风险扣分"""
    rows = []
    for t in raw:
        d = {"code": t[1]}
        for k, v in zip(FIELDS, t[2:]):
            d[k] = v
        rows.append(d)
    assign_rank(rows, "mom20", "mom20_p")
    assign_rank(rows, "mom5", "mom5_p")
    assign_rank(rows, "ma20_slope", "ma20_slope_p")
    assign_rank(rows, "amount20", "amount20_p")
    assign_rank(rows, "win60", "win60_p")
    assign_rank(rows, "max_drop20", "max_drop20_p")
    w = REGIME_WEIGHTS[regime]
    total_w = sum(w.values())
    for r in rows:
        mom = 0.6 * r["mom20_p"] + 0.4 * r["mom5_p"]
        trend = 0.6 * r["trend"] * 100 + 0.4 * r["ma20_slope_p"]
        vol = (0.6 * max(0.0, 100 - abs(r["volratio"] - 1.8) / 1.8 * 100)
               + 0.4 * r["amount20_p"])
        quality = 0.5 * r["win60_p"] + 0.5 * r["max_drop20_p"]
        pen = 0.0
        if r["vol20"] > 45:
            pen += min((r["vol20"] - 45) * 0.5, 8)
        if r["drawdown60"] > 15:
            pen += min((r["drawdown60"] - 15) * 0.5, 6)
        if r["rsi14"] > 72:
            pen += min((r["rsi14"] - 72) * 0.8, 6)
        if r["bias20"] > 12:
            pen += min((r["bias20"] - 12) * 0.6, 5)
        if r["max_drop20"] < -8:
            pen += min((-r["max_drop20"] - 8) * 1.0, 5)
        r["score"] = ((w["mom"] * mom + w["trend"] * trend + w["vol"] * vol
                       + w["quality"] * quality) / total_w - min(pen, 20.0))
    rows.sort(key=lambda r: -r["score"])
    return rows


def pick_portfolio(rows: list[dict], meta, top: int, max_per_industry: int = 2) -> list[dict]:
    """按分数取 TopN，同行业最多 max_per_industry 只"""
    picked, count = [], {}
    for r in rows:
        ind = meta.get(r["code"], ("", "未分类"))[1]
        if count.get(ind, 0) >= max_per_industry:
            continue
        picked.append(r)
        count[ind] = count.get(ind, 0) + 1
        if len(picked) >= top:
            break
    return picked


def main() -> int:
    parser = argparse.ArgumentParser(description="多因子选股回测")
    parser.add_argument("--start", default="2023-08-01")
    parser.add_argument("--end", default=datetime.now().strftime("%Y-%m-%d"))
    parser.add_argument("--rebalance", type=int, default=10, help="调仓间隔（交易日）")
    parser.add_argument("--top", type=int, default=20, help="组合持仓数")
    parser.add_argument("--cost", type=float, default=0.0005, help="单边手续费")
    parser.add_argument("--min-amount", type=float, default=5e7)
    parser.add_argument("--min-price", type=float, default=2.0)
    parser.add_argument("--max-price", type=float, default=150.0)
    parser.add_argument("--json", default=r"D:\stock-analyzer\logs\backtest_v2.json")
    args = parser.parse_args()

    conn = dbm.get_conn()
    meta = load_meta(conn)
    bench = load_index(conn)
    bench_dates = [d for d, _ in bench]
    bench_closes = [c for _, c in bench]
    cal = bench_dates
    lo = next((i for i, d in enumerate(cal) if d >= args.start), None)
    hi = max(i for i, d in enumerate(cal) if d <= args.end)
    if lo is None or lo > hi:
        print("[warn] 回测区间内无交易日")
        return 1
    reb = cal[lo:hi + 1:args.rebalance]
    hold = args.rebalance
    print(f"[backtest] {reb[0]} ~ {reb[-1]} | 调仓 {hold} 日 | 持仓 {args.top} | "
          f"区间内 {len(reb)} 个调仓日")

    # 交易日 → 沪深300 下标，用于组合基准收益
    bidx = {d: i for i, d in enumerate(bench_dates)}
    reb_set = set(reb)
    raw_by_date: dict[str, list[tuple]] = {d: [] for d in reb}

    checked = kept = 0
    for code, bars in iter_stocks(conn):
        if code[:2] not in ("60", "00", "30", "68"):
            continue
        name = meta.get(code, ("", ""))[0]
        if "ST" in name or "退" in name:
            continue  # 与线上一致剔除此类标的（用当前名称，存在一定幸存者偏差）
        checked += 1
        rows = stock_rows(code, bars, reb_set, hold, args.min_amount,
                          args.min_price, args.max_price)
        if rows:
            kept += 1
            for r in rows:
                raw_by_date[r[0]].append(r)
    conn.close()

    print(f"[universe] 扫描 {checked} 只，命中候选 {kept} 只")

    nav, navs, prev = 1.0, [], set()
    ic_list, factor_ic = [], {k: [] for k in ("mom20", "mom5", "ma20_slope", "volratio",
                                             "amount20", "win60", "max_drop20")}
    groups = {i: [] for i in range(5)}
    detail = []
    periods = {"ret": [], "bench": [], "excess": [], "dates": []}
    bench_map = dict(zip(bench_dates, bench_closes))

    for k, d in enumerate(reb, 1):
        raw = raw_by_date.get(d, [])
        if len(raw) < 20:
            detail.append({"date": d, "candidates": len(raw), "skipped": True})
            continue
        n_bench = bidx.get(d)
        if n_bench is None:
            detail.append({"date": d, "candidates": len(raw), "skipped": True})
            continue
        regime, ret20 = regime_at(bench_closes, n_bench)
        rows = score_rows(raw, regime)
        with_fwd = [r for r in rows if r["fwd"] is not None]
        if len(with_fwd) < 20:
            detail.append({"date": d, "candidates": len(raw), "skipped": True})
            continue

        ic = spearman([r["score"] for r in with_fwd], [r["fwd"] for r in with_fwd])
        ic_list.append(ic)
        for key, icd in factor_ic.items():
            v = spearman([r[key] for r in with_fwd], [r["fwd"] for r in with_fwd])
            if v is not None:
                icd.append(v)

        # 五分组（G1 分数最高）
        gsize = len(with_fwd) // 5
        if gsize >= 5:
            for gi in range(5):
                seg = with_fwd[gi * gsize:(gi + 1) * gsize]
                groups[gi].append(sum(r["fwd"] for r in seg) / len(seg))

        picked = pick_portfolio(rows, meta, args.top)
        picked = [r for r in picked if r["fwd"] is not None]
        if picked:
            gross = sum(r["fwd"] for r in picked) / len(picked)
            codes = {r["code"] for r in picked}
            turnover = len(codes - prev) / len(codes)
            net = gross - 2 * turnover * args.cost
            prev = codes
            nav *= 1 + net
            navs.append(nav)
            bret = bench_map.get(d)
            e = bench_dates[bidx[d] + hold] if bidx[d] + hold < len(bench_dates) else None
            bret = (bench_map[e] / bret - 1) if (bret and e) else None
            periods["ret"].append(net)
            periods["bench"].append(bret if bret is not None else 0.0)
            periods["excess"].append(net - (bret or 0.0))
            periods["dates"].append(d)
            detail.append({"date": d, "regime": regime, "bench20": round(ret20, 2),
                           "candidates": len(with_fwd), "ic": round(ic, 4) if ic else None,
                           "gross": round(gross * 100, 2), "cost": round(2 * turnover * args.cost * 100, 2),
                           "net": round(net * 100, 2),
                           "bench": round(bret * 100, 2) if bret is not None else None,
                           "nav": round(nav, 4), "picks": [r["code"] for r in picked]})
        if k % 10 == 0 or k == len(reb):
            print(f"[{k}/{len(reb)}] {d} 候选 {len(with_fwd)} 净值 {nav:.3f}")

    period_ret = periods["ret"]
    if not period_ret:
        print("[warn] 无有效调仓期，回测终止")
        return 1

    def stats(vals):
        vals = [v for v in vals if v is not None]
        if not vals:
            return None
        m = statistics.fmean(vals)
        s = statistics.pstdev(vals) if len(vals) > 1 else 0.0
        return m, s, (m / s if s else 0.0), len(vals)

    ic = stats(ic_list)
    total_ret = nav - 1
    years = len(period_ret) * hold / 252
    ann = (nav ** (1 / years) - 1) if years > 0 else 0.0
    peak, mdd = 1.0, 0.0
    for v in [1.0] + navs:
        peak = max(peak, v)
        mdd = max(mdd, (peak - v) / peak)
    bench_total = 1.0
    for b in periods["bench"]:
        bench_total *= 1 + b
    win = sum(1 for r in period_ret if r > 0) / len(period_ret)

    print("\n" + "=" * 66)
    print(f"回测结果 {reb[0]} ~ {periods['dates'][-1]}｜{len(period_ret)} 个调仓期")
    print("=" * 66)
    if ic:
        m, s, ir, n = ic
        t = ir * math.sqrt(n) if s else 0.0
        pos = sum(1 for v in ic_list if v and v > 0) / len([v for v in ic_list if v is not None])
        print(f"[IC] 综合评分 RankIC 均值 {m:+.3f}｜标准差 {s:.3f}｜ICIR {ir:+.2f}｜"
              f"t≈{t:.1f}｜正 IC 占比 {pos:.0%}")
    print("[因子 IC] " + "｜".join(
        f"{k} {statistics.fmean(v):+.3f}" for k, v in factor_ic.items() if v))
    print("[分组] 10 日平均收益（G1 分数最高）: " + "｜".join(
        f"G{i + 1} {statistics.fmean(g)*100:+.2f}%" for i, g in groups.items() if g)
        + f"｜多空价差 {(statistics.fmean(groups[0]) - statistics.fmean(groups[4]))*100:+.2f}%"
        if groups[0] and groups[4] else "")
    print(f"[组合] 累计 {total_ret*100:+.1f}%｜年化 {ann*100:+.1f}%｜最大回撤 {mdd*100:.1f}%｜"
          f"胜率 {win:.0%}｜期末净值 {nav:.3f}")
    print(f"[基准] 沪深300 同期 {bench_total*100-100:+.1f}%｜"
          f"平均每期超额 {statistics.fmean(periods['excess'])*100:+.2f}%")

    summary = {
        "start": reb[0], "end": periods["dates"][-1], "rebalance": hold,
        "top": args.top, "periods": len(period_ret),
        "total_return": round(total_ret * 100, 2),
        "annual_return": round(ann * 100, 2),
        "max_drawdown": round(mdd * 100, 2),
        "win_rate": round(win * 100, 2),
        "benchmark_return": round(bench_total * 100 - 100, 2),
        "rank_ic_mean": round(ic[0], 4) if ic else None,
        "rank_ic_std": round(ic[1], 4) if ic else None,
        "rank_ic_ir": round(ic[2], 3) if ic else None,
        "factor_ic": {k: round(statistics.fmean(v), 4) for k, v in factor_ic.items() if v},
        "groups": {f"G{i + 1}": round(statistics.fmean(g) * 100, 3)
                   for i, g in groups.items() if g},
        "detail": detail,
        "note": "估值组（PE/PB）无历史数据，权重已归一化到其余四组",
    }
    try:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=1)
        print(f"[done] 明细写入 {args.json}")
    except Exception as e:
        print(f"[warn] 明细写入失败: {e}")
    print("\n免责声明：历史回测不代表未来收益，结果仅用于策略有效性评估。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
