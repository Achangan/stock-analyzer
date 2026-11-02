"""全市场历史回填（东财并行主通道 + Baostock 兜底，断点续跑）

重复运行只会补缺失区间：
- 从未采集的代码：从 --start 拉到今天
- 已采集但落后最新交易日的代码：从库内最大日期次日续拉
- 已接近最新的代码：跳过

用法：
    python backfill.py                       # 东财并行回填（默认）
    python backfill.py --source baostock     # 仅用 Baostock 单通道
    python backfill.py --limit 200           # 只回填 200 只（试跑）
    python backfill.py --workers 6           # 东财并发数
"""
import argparse
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta

import config
import db_mysql as dbm
import screen_stocks
from stock_client import DAILY_COLUMNS, _fetch_one

EM_URL = ("https://push2his.eastmoney.com/api/qt/stock/kline/get?secid={secid}"
          "&fields1=f1,f2,f3&fields2=f51,f52,f53,f54,f55,f56,f57"
          "&klt=101&fqt=1&end=20500101&lmt=1200")


def load_codes(conn) -> list[str]:
    with conn.cursor() as cur:
        cur.execute("SELECT ts_code FROM stock_basic WHERE status=1 ORDER BY ts_code")
        return [r[0] for r in cur.fetchall()]


def load_coverage(conn) -> dict[str, tuple]:
    with conn.cursor() as cur:
        cur.execute("SELECT ts_code, MIN(trade_date), MAX(trade_date) FROM daily_quote GROUP BY ts_code")
        return {r[0]: (r[1], r[2]) for r in cur.fetchall()}


def plan(codes: list[str], coverage: dict, start: str, today: str) -> list[tuple[str, str]]:
    """返回待补 (代码, 起始日) 列表；数据已覆盖到近 3 日的跳过"""
    start_grace = (datetime.strptime(start, "%Y-%m-%d") + timedelta(days=10)).strftime("%Y-%m-%d")
    fresh_after = (datetime.strptime(today, "%Y-%m-%d") - timedelta(days=3)).strftime("%Y-%m-%d")
    todo = []
    for code in codes:
        mn, mx = coverage.get(code, (None, None))
        full = mn is not None and mn.strftime("%Y-%m-%d") <= start_grace
        if full and mx and mx.strftime("%Y-%m-%d") >= fresh_after:
            continue
        begin = start
        if full and mx:
            begin = (mx + timedelta(days=1)).strftime("%Y-%m-%d")
        if begin <= today:
            todo.append((code, begin))
    return todo


def em_rows(code: str, begin: str) -> list[tuple]:
    """东财前复权日 K → daily_quote 元组；成交量由手换算为股"""
    secid = ("1." if code.endswith(".SH") else "0.") + code.split(".")[0]
    data = screen_stocks.http_json(EM_URL.format(secid=secid), retries=2)
    bars = (data.get("data") or {}).get("klines") or []
    rows, prev = [], None
    for b in bars:
        p = b.split(",")
        if len(p) < 7:
            continue
        date = p[0]
        o, c, h, l = (float(p[1]), float(p[2]), float(p[3]), float(p[4]))
        vol, amount = float(p[5]), float(p[6])
        if prev is not None and date >= begin:
            change = round(c - prev, 4)
            pct = round(change / prev * 100, 4) if prev else None
            rows.append((code, date, o, h, l, c, prev, change, pct, vol * 100, amount))
        prev = c
    return rows


def run_em(todo, conn, workers: int, batch: int) -> tuple[int, int, list]:
    total = ok = 0
    failed: list[tuple[str, str]] = []
    started = time.time()
    for start in range(0, len(todo), batch):
        chunk = todo[start:start + batch]
        with ThreadPoolExecutor(max_workers=workers) as pool:
            results = list(pool.map(lambda t: _em_safe(t), chunk))
        rows = []
        for code, data, err in results:
            if err:
                failed.append((code, err))
            else:
                rows.extend(data)
                ok += 1
        total += dbm.upsert_many(conn, "daily_quote", DAILY_COLUMNS, rows)
        done = min(start + batch, len(todo))
        elapsed = time.time() - started
        speed = done / elapsed if elapsed else 0
        eta = (len(todo) - done) / speed / 60 if speed else 0
        print(f"[{done}/{len(todo)}] 入库 {total} 行，失败 {len(failed)} 只，"
              f"{speed:.1f} 只/秒，剩余约 {eta:.0f} 分钟", flush=True)
    return total, ok, failed


def _em_safe(item):
    code, begin = item
    try:
        return code, em_rows(code, begin), None
    except Exception as e:
        return code, [], str(e)


def run_bs(todo, conn, batch: int, delay: float) -> tuple[int, int, list]:
    import baostock as bs

    lg = bs.login()
    if lg.error_code != "0":
        print(f"[error] Baostock 登录失败：{lg.error_msg}")
        return 0, 0, todo
    total = ok = 0
    failed: list[tuple[str, str]] = []
    buffer: list[tuple] = []
    started = time.time()
    try:
        for i, (code, begin) in enumerate(todo, 1):
            end = datetime.now().strftime("%Y-%m-%d")
            for attempt in (1, 2):
                try:
                    buffer.extend(_fetch_one(bs, code, begin, end))
                    ok += 1
                    break
                except Exception as e:
                    if attempt == 1 and "未登录" in str(e):
                        bs.logout()
                        bs.login()
                        continue
                    failed.append((code, str(e)))
                    break

            if i % batch == 0 or i == len(todo):
                total += dbm.upsert_many(conn, "daily_quote", DAILY_COLUMNS, buffer)
                buffer = []
                elapsed = time.time() - started
                speed = i / elapsed if elapsed else 0
                eta = (len(todo) - i) / speed / 60 if speed else 0
                print(f"[{i}/{len(todo)}] 入库 {total} 行，失败 {len(failed)} 只，"
                      f"{speed:.2f} 只/秒，剩余约 {eta:.0f} 分钟", flush=True)
            time.sleep(delay)
    finally:
        bs.logout()
    return total, ok, failed


def main() -> int:
    parser = argparse.ArgumentParser(description="全市场历史回填（可断点续跑）")
    parser.add_argument("--start", default=config.HISTORY_START, help="回填起始日 YYYY-MM-DD")
    parser.add_argument("--limit", type=int, help="最多处理 N 只，用于试跑")
    parser.add_argument("--batch", type=int, default=50, help="每 N 只提交一次事务")
    parser.add_argument("--delay", type=float, default=0.05, help="Baostock 每只请求间隔秒数")
    parser.add_argument("--source", choices=("em", "baostock"), default="em",
                        help="主通道：em=东财并行（快），baostock=单通道")
    parser.add_argument("--workers", type=int, default=6, help="东财并发数")
    args = parser.parse_args()

    conn = dbm.get_conn()
    codes = load_codes(conn)
    if args.limit:
        codes = codes[:args.limit]
    today = datetime.now().strftime("%Y-%m-%d")
    todo = plan(codes, load_coverage(conn), args.start, today)
    print(f"[info] 股票 {len(codes)} 只，待补 {len(todo)} 只，起始 {args.start}，截止 {today}，"
          f"通道 {args.source}")
    if not todo:
        print("[done] 全部已覆盖，无需回填")
        return 0

    if args.source == "em":
        total, ok, failed = run_em(todo, conn, args.workers, args.batch)
        if failed:
            print(f"[info] 东财失败 {len(failed)} 只，转 Baostock 兜底")
            t2, o2, f2 = run_bs(failed, conn, batch=args.batch, delay=args.delay)
            total += t2
            ok += o2
            failed = f2
    else:
        total, ok, failed = run_bs(todo, conn, batch=args.batch, delay=args.delay)

    msg = f"待补 {len(todo)} 只，成功 {ok} 只，失败 {len(failed)} 只"
    if failed:
        msg += "：" + "; ".join(f"{c}:{e}" for c, e in failed[:8])
    dbm.log_collect(conn, args.source, "daily_quote:backfill", total,
                    "success" if not failed else "partial", msg)
    print(f"[done] 入库 {total} 行，成功 {ok} 只，失败 {len(failed)} 只")
    if failed:
        print("[warn] 失败示例：" + "; ".join(f"{c}:{e}" for c, e in failed[:8]))
    conn.close()
    return 0 if not failed else 2


if __name__ == "__main__":
    raise SystemExit(main())
