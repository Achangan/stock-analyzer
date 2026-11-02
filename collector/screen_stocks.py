"""全市场选股筛选：腾讯快照 + 前复权日 K，多策略融合打分取 Top N

用法：
    python screen_stocks.py                # 默认 Top20
    python screen_stocks.py --top 30

数据基准：最近一个交易日收盘，结果写入 screen_result 供接口展示。

算法（v2）：五组因子 + 市场状态自适应权重 + 风险扣分
    动量：20 日 / 5 日涨幅（60% / 40%）
    趋势：均线排列（MA5>MA20>MA60）+ MA20 斜率
    量能：量比（理想 1.8 倍）+ 20 日日均成交额
    估值：行业内部 PE / PB 百分位（越低越好，行业样本 <4 只退回全市场）
    质量：60 日上涨天数占比 + 近 20 日最大单日跌幅
市场状态由沪深300 的 MA20/MA60 与 20 日收益判定，进攻/均衡/防守切换权重；
风险扣分覆盖波动率、60 日回撤、RSI 过热、MA20 正乖离、闪崩，上限 20 分。
"""
import argparse
import json
import math
import statistics
import time
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

import db_mysql as dbm

HEADERS = {
    "User-Agent": ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                   "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"),
    "Referer": "https://quote.eastmoney.com/",
    "Accept": "*/*",
}

SNAP_URL = "https://qt.gtimg.cn/q="
KLINE_URL = ("https://push2his.eastmoney.com/api/qt/stock/kline/get?secid={secid}"
             "&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57"
             "&klt=101&fqt=1&end=20500101&lmt=150")


def http_json(url: str, timeout: int = 20, retries: int = 4) -> dict:
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # 网络抖动/限流退避重试
            last = e
            time.sleep(0.5 * (i + 1))
    raise RuntimeError(f"请求失败: {last}")


def secid(ts_code: str) -> str:
    code, market = ts_code.split(".")
    return ("1." if market == "SH" else "0.") + code


def load_universe(conn) -> dict[str, tuple[str, str | None]]:
    with conn.cursor() as cur:
        cur.execute("SELECT ts_code, name, industry FROM stock_basic WHERE status=1")
        return {r[0]: (r[1], r[2]) for r in cur.fetchall()}


def load_benchmark_db(conn) -> list[list]:
    """东财不可用时，用库内沪深300 日线判断市场状态"""
    with conn.cursor() as cur:
        cur.execute("SELECT trade_date, open, close, high, low, vol, amount FROM index_daily "
                    "WHERE ts_code='000300.SH' ORDER BY trade_date DESC LIMIT 150")
        rows = cur.fetchall()[::-1]
    return [[r[0].strftime("%Y-%m-%d")] + [str(x) for x in r[1:]] for r in rows]


def fetch_snapshot(codes: list[str], batch: int = 60) -> dict[str, dict]:
    """腾讯快照（批量），返回价格/涨跌幅/换手/PE/PB/总市值（亿）/成交量"""
    out: dict[str, dict] = {}
    for i in range(0, len(codes), batch):
        chunk = codes[i:i + batch]
        url = SNAP_URL + ",".join(("sh" if c.endswith(".SH") else "sz") + c[:6] for c in chunk)
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                text = r.read().decode("gbk", errors="ignore")
        except Exception:
            continue
        for line in text.splitlines():
            if '="' not in line:
                continue
            p = line.split('="', 1)[1].rstrip('";').split("~")
            if len(p) < 47 or not p[2]:
                continue
            market = "SH" if line.startswith("v_sh") else "SZ"
            out[f"{p[2]}.{market}"] = {
                "name": p[1], "price": num(p[3]), "pct": num(p[32]),
                "volume": num(p[6]), "turnover": num(p[38]), "pe": num(p[39]),
                "mcap": num(p[45]), "pb": num(p[46]),
            }
        time.sleep(0.05)
    return out


def num(v, default=0.0) -> float:
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def fetch_klines(codes: list[str], workers: int = 8) -> dict[str, list[list]]:
    """东方财富日 K；限流时快速失败，交给 Baostock 兜底"""
    def one(code: str):
        try:
            node = (http_json(KLINE_URL.format(secid=secid(code)), retries=1).get("data") or {})
            return code, node.get("klines") or []
        except Exception:
            return code, []

    out = {}
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for code, bars in pool.map(one, codes):
            if bars:
                out[code] = bars
    return out


def fetch_klines_baostock(codes: list[str], start: str = "2025-11-01") -> dict[str, list[list]]:
    """东方财富限流时的兜底通道：Baostock 前复权日线（单线程，较慢）"""
    import baostock as bs

    out: dict[str, list[list]] = {}
    if not codes:
        return out
    lg = bs.login()
    if lg.error_code != "0":
        print(f"[warn] baostock 登录失败：{lg.error_msg}")
        return out
    try:
        for i, code in enumerate(codes, 1):
            c, market = code.split(".")
            rs = bs.query_history_k_data_plus(
                f"{market.lower()}.{c}", "date,open,high,low,close,volume",
                start_date=start, frequency="d", adjustflag="2")
            bars = []
            while rs.error_code == "0" and rs.next():
                d = rs.get_row_data()  # date,open,high,low,close,volume
                bars.append([d[0], d[1], d[4], d[2], d[3], d[5]])
            if bars:
                out[code] = bars
            if i % 50 == 0:
                print(f"[info] baostock 兜底进度 {i}/{len(codes)}")
    finally:
        bs.logout()
    return out


def indicators(bars: list[list]) -> dict | None:
    """bar=date,open,close,high,low,vol(手),amount(元)，至少 140 根"""
    if len(bars) < 140:
        return None
    parsed = [b.split(",") if isinstance(b, str) else b for b in bars]
    # 停牌日行情字段为空，剔除后再算指标
    parsed = [b for b in parsed if len(b) > 5 and num(b[2]) > 0]
    if len(parsed) < 140:
        return None
    closes = [num(b[2]) for b in parsed]
    highs = [num(b[3]) for b in parsed]
    vols = [num(b[5]) for b in parsed]
    amounts = [num(b[6], -1.0) if len(b) > 6 else -1.0 for b in parsed]
    c = closes[-1]
    ma = lambda n: sum(closes[-n:]) / n
    ma5, ma20, ma60 = ma(5), ma(20), ma(60)
    ma20_prev = sum(closes[-25:-5]) / 20  # 5 个交易日前的 MA20

    rets = [closes[i] / closes[i - 1] - 1 for i in range(len(closes) - 20, len(closes))]
    vol20 = statistics.pstdev(rets) * math.sqrt(252) * 100

    gains = [max(closes[i] - closes[i - 1], 0) for i in range(1, len(closes))]
    losses = [max(closes[i - 1] - closes[i], 0) for i in range(1, len(closes))]
    ag, al = statistics.fmean(gains[-14:]), statistics.fmean(losses[-14:])
    rsi = 100.0 if al == 0 else 100 - 100 / (1 + ag / al)

    peak = max(highs[-60:])
    avg20 = statistics.fmean(vols[-20:])
    daily = [closes[i] / closes[i - 1] - 1 for i in range(1, len(closes))]
    # 20 日均成交额：优先用接口 amount；Baostock 兜底无 amount 时按 量(股)*价 估算
    recent_amount = amounts[-20:]
    if all(a > 0 for a in recent_amount):
        amount20 = statistics.fmean(recent_amount)
    else:
        amount20 = statistics.fmean(vols[-20:]) * c
    return {
        "last_date": parsed[-1][0],
        "close": c,
        "mom5": (c / closes[-6] - 1) * 100,
        "mom20": (c / closes[-21] - 1) * 100,
        "mom60": (c / closes[-61] - 1) * 100,
        "trend": (0.5 if ma5 > ma20 else 0.0) + (0.5 if ma20 > ma60 else 0.0),
        "above_ma60": c > ma60,
        "ma20_slope": (ma20 / ma20_prev - 1) * 100,      # MA20 5 日斜率 %
        "bias20": (c / ma20 - 1) * 100,
        "vol20": vol20,
        "rsi14": rsi,
        "volratio": (statistics.fmean(vols[-5:]) / avg20) if avg20 else 1.0,
        "drawdown60": (peak - c) / peak * 100,
        "amount20": amount20,                            # 20 日均成交额（元）
        "win60": sum(1 for r in daily[-60:] if r > 0) / 60 * 100,  # 60 日上涨占比
        "max_drop20": min(daily[-20:]) * 100,             # 近 20 日最大单日跌幅 %
    }


# 市场状态 → 五组因子权重（和为 1，风险另作扣分）
REGIME_WEIGHTS = {
    "进攻": {"mom": 0.34, "trend": 0.30, "vol": 0.12, "value": 0.12, "quality": 0.12},
    "均衡": {"mom": 0.26, "trend": 0.26, "vol": 0.14, "value": 0.18, "quality": 0.16},
    "防守": {"mom": 0.18, "trend": 0.22, "vol": 0.12, "value": 0.26, "quality": 0.22},
}


def detect_regime(bench_bars: list[list]) -> tuple[str, float]:
    """用沪深300 判定市场状态：站上 MA20/MA60 且 20 日上涨为进攻，跌破 MA60 为防守"""
    closes = [num(b.split(",")[2] if isinstance(b, str) else b[2]) for b in bench_bars]
    if len(closes) < 61:
        return "均衡", 0.0
    c = closes[-1]
    ma20 = sum(closes[-20:]) / 20
    ma60 = sum(closes[-60:]) / 60
    ret20 = (c / closes[-21] - 1) * 100
    if c > ma20 > ma60 and ret20 > 0:
        return "进攻", ret20
    if c < ma60 or ret20 < -5:
        return "防守", ret20
    return "均衡", ret20


def assign_rank(rows: list[dict], key: str, out_key: str, higher_better: bool = True,
                group_key: str | None = None, min_group: int = 4) -> None:
    """百分位排名（0~100，并列取平均）；group_key 存在时优先在组内排名，样本不足退回全样本"""

    def rank(subset: list[dict]) -> None:
        n = len(subset)
        buckets: dict[float, list[dict]] = defaultdict(list)
        for r in subset:
            buckets[r[key]].append(r)
        start = 0
        for value in sorted(buckets):
            members = buckets[value]
            avg = (start + start + len(members) - 1) / 2 / (n - 1) * 100 if n > 1 else 50.0
            score = avg if higher_better else 100 - avg
            for r in members:
                r[out_key] = score
            start += len(members)

    rank(rows)  # 先算全样本，作为兜底
    if group_key:
        buckets: dict[str, list[dict]] = defaultdict(list)
        for r in rows:
            buckets[r[group_key]].append(r)
        for subset in buckets.values():
            if len(subset) >= min_group:
                rank(subset)


def save_results(trade_date: str, picked: list[dict]) -> None:
    """结果写入 screen_result，供 analysis-service 读取展示"""
    from datetime import datetime

    columns = ["trade_date", "ts_code", "name", "industry", "rank_no", "close", "pct_chg",
               "pe", "pb", "mcap", "mom5", "mom20", "trend", "volratio", "vol20",
               "rsi14", "drawdown60", "score", "created_at"]
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    rows = [(trade_date, r["code"], r["name"], r["industry"], i, round(r["price"], 4),
             round(r["pct"], 4), round(r["pe"], 4), round(r["pb"], 4), round(r["mcap"], 4),
             round(r["mom5"], 4), round(r["mom20"], 4), round(r["trend"], 2),
             round(r["volratio"], 4), round(r["vol20"], 4), round(r["rsi14"], 2),
             round(r["drawdown60"], 4), round(r["score"], 2), now)
            for i, r in enumerate(picked, 1)]
    conn = dbm.get_conn()
    try:
        with conn.cursor() as cur:  # 同一天重跑时先清空，避免残留旧排名
            cur.execute("DELETE FROM screen_result WHERE trade_date=%s", (trade_date,))
        conn.commit()
        dbm.upsert_many(conn, "screen_result", columns, rows)
    finally:
        conn.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="全市场多因子选股")
    parser.add_argument("--top", type=int, default=20)
    parser.add_argument("--max-candidates", type=int, default=1200)
    parser.add_argument("--min-mcap", type=float, default=50.0, help="总市值下限（亿）")
    parser.add_argument("--max-mcap", type=float, default=1500.0, help="总市值上限（亿）")
    args = parser.parse_args()

    conn = dbm.get_conn()
    meta = load_universe(conn)
    print(f"[info] 上市股票 {len(meta)} 只，拉取全市场快照…")
    snaps = fetch_snapshot(sorted(meta.keys()))
    print(f"[info] 快照返回 {len(snaps)} 只")

    cands = []
    for code, info in snaps.items():
        name = info["name"] or meta.get(code, ("", None))[0]
        industry = meta.get(code, ("", None))[1] or "未分类"
        if not code.startswith(("60", "00", "30")):  # 仅沪深主板/中小板/创业板
            continue
        if "ST" in name or "退" in name or name.startswith(("N", "C")):
            continue
        price, pct = info["price"], info["pct"]
        turnover, pe, pb = info["turnover"], info["pe"], info["pb"]
        mcap = info["mcap"]
        if price < 2 or price > 150 or mcap <= 0 or info["volume"] <= 0:
            continue
        if not (args.min_mcap <= mcap <= args.max_mcap):
            continue
        if not (1.5 <= turnover <= 15.0):
            continue
        if not (0 < pe <= 60) or not (0.5 <= pb <= 10):
            continue
        limit = 19.0 if code.startswith("30") else 9.5
        if abs(pct) >= limit:
            continue
        cands.append({"code": code, "name": name, "industry": industry,
                      "price": price, "pct": pct, "turnover": turnover, "pe": pe,
                      "pb": pb, "mcap": mcap})

    print(f"[info] 基础面/流动性过滤后 {len(cands)} 只")
    cands.sort(key=lambda r: -r["turnover"])
    cands = cands[:args.max_candidates]

    print(f"[info] 拉取 {len(cands)} 只近 150 日 K 线…")
    klines = fetch_klines([r["code"] for r in cands])
    print(f"[info] 东财 K 线成功 {len(klines)} 只")
    missing = [r["code"] for r in cands if r["code"] not in klines]
    if missing:
        print(f"[info] 东方财富缺 {len(missing)} 只，改用 Baostock 兜底（较慢）…")
        klines.update(fetch_klines_baostock(missing))
    print(f"[info] K 线合计可用 {len(klines)} 只")

    bench = fetch_klines(["000300.SH"]).get("000300.SH", [])
    if not bench:
        bench = load_benchmark_db(conn)
        print(f"[info] 东财基准缺失，改用库内沪深300（{len(bench)} 根）")
    regime, bench_ret20 = detect_regime(bench)
    weights = REGIME_WEIGHTS[regime]
    print(f"[info] 市场状态【{regime}】沪深300 20 日 {bench_ret20:+.1f}%，"
          f"权重 动量{weights['mom']:.0%}/趋势{weights['trend']:.0%}/"
          f"量能{weights['vol']:.0%}/估值{weights['value']:.0%}/质量{weights['quality']:.0%}")

    rows = []
    for r in cands:
        ind = indicators(klines.get(r["code"], []))
        if not ind:
            continue
        # 硬门槛：中期趋势向上、强度适中、流动性充足、风险可控
        if not ind["above_ma60"] or ind["ma20_slope"] <= 0:
            continue
        if not (40 <= ind["rsi14"] <= 78):
            continue
        if not (0 <= ind["mom20"] <= 40) or ind["mom60"] > 120:
            continue
        if ind["vol20"] > 60 or ind["drawdown60"] > 25:
            continue
        if ind["amount20"] < 5e7:  # 20 日日均成交额不低于 5000 万
            continue
        r.update(ind)
        rows.append(r)
    if not rows:
        print("[warn] 无有效候选")
        return 1
    print(f"[info] K 线有效候选 {len(rows)} 只")

    # 因子百分位（0~100）：估值在行业内比较，其余全样本比较
    assign_rank(rows, "mom20", "mom20_p")
    assign_rank(rows, "mom5", "mom5_p")
    assign_rank(rows, "ma20_slope", "ma20_slope_p")
    assign_rank(rows, "amount20", "amount20_p")
    assign_rank(rows, "win60", "win60_p")
    assign_rank(rows, "max_drop20", "max_drop20_p")
    assign_rank(rows, "pe", "pe_ind_p", higher_better=False, group_key="industry")
    assign_rank(rows, "pb", "pb_ind_p", higher_better=False, group_key="industry")

    ideal_ratio = 1.8  # 量比 1.8 倍视为温和放量，偏离越远得分越低
    for r in rows:
        r["mom_score"] = 0.6 * r["mom20_p"] + 0.4 * r["mom5_p"]
        r["trend_score"] = 0.6 * r["trend"] * 100 + 0.4 * r["ma20_slope_p"]
        r["vol_score"] = (0.6 * max(0.0, 100 - abs(r["volratio"] - ideal_ratio) / ideal_ratio * 100)
                          + 0.4 * r["amount20_p"])
        r["value_score"] = 0.55 * r["pe_ind_p"] + 0.45 * r["pb_ind_p"]
        r["quality_score"] = 0.5 * r["win60_p"] + 0.5 * r["max_drop20_p"]

        # 风险扣分：波动、回撤、RSI 过热、正乖离、闪崩，合计上限 20 分
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
        r["penalty"] = min(pen, 20.0)
        r["score"] = round(
            weights["mom"] * r["mom_score"] + weights["trend"] * r["trend_score"]
            + weights["vol"] * r["vol_score"] + weights["value"] * r["value_score"]
            + weights["quality"] * r["quality_score"] - r["penalty"], 2)

    rows.sort(key=lambda r: -r["score"])

    # 行业分散：同一行业最多 2 只
    picked, per_industry = [], {}
    for r in rows:
        if per_industry.get(r["industry"], 0) >= 2:
            continue
        picked.append(r)
        per_industry[r["industry"]] = per_industry.get(r["industry"], 0) + 1
        if len(picked) >= args.top:
            break

    print(f"\n市场状态 {regime}｜沪深300 20 日 {bench_ret20:+.1f}%")
    print("排名 | 代码 | 名称 | 行业 | 收盘 | 涨跌% | PE | PB | 市值亿 | 20日% | 排列 | 量比 | "
          "RSI | 回撤% | 动量 | 趋势 | 量能 | 估值 | 质量 | 扣分 | 评分")
    for i, r in enumerate(picked, 1):
        print(f"{i} | {r['code']} | {r['name']} | {r['industry'][:12]} | {r['price']:.2f} | "
              f"{r['pct']:+.2f} | {r['pe']:.1f} | {r['pb']:.2f} | {r['mcap']:.0f} | "
              f"{r['mom20']:+.1f} | {r['trend']:.1f} | {r['volratio']:.2f} | "
              f"{r['rsi14']:.0f} | {r['drawdown60']:.1f} | "
              f"{r['mom_score']:.0f} | {r['trend_score']:.0f} | {r['vol_score']:.0f} | "
              f"{r['value_score']:.0f} | {r['quality_score']:.0f} | "
              f"-{r['penalty']:.0f} | {r['score']:.1f}")

    trade_date = picked[0]["last_date"] if picked else None
    if trade_date:
        try:
            save_results(trade_date, picked)
            print(f"\n[done] 已写入 screen_result：{trade_date} 共 {len(picked)} 条")
        except Exception as e:
            print(f"\n[warn] 写入 screen_result 失败：{e}")
    print("\n免责声明：以上为基于历史行情的量化筛选结果，仅供参考，不构成投资建议。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
