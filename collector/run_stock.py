"""个股采集入口（Baostock 通道）

用法：
    python run_stock.py --list                          # 更新股票列表 + 行业
    python run_stock.py --codes 600519.SH,000001.SZ     # 指定代码增量采集
    python run_stock.py --codes 600519.SH --backfill    # 指定代码全量回填
    python run_stock.py --watchlist                     # 采集自选股增量
    python run_stock.py --all --backfill                # 全市场回填（约 5000 只，耗时长）
    python run_stock.py --all --limit 100               # 只采前 100 只，用于试跑
"""
import argparse
import sys

import config
import db_mysql as dbm
import stock_client


def normalize_code(raw: str) -> str:
    """600519 / 600519.SH / sh.600519 -> 600519.SH"""
    s = raw.strip().upper()
    if s.startswith(("SH.", "SZ.")):
        s = s[3:] + "." + s[:2]
    if "." in s:
        code, market = s.split(".")
        if market not in ("SH", "SZ"):
            raise ValueError(f"暂只支持沪深 A 股: {raw}")
        return f"{code}.{market}"
    if len(s) == 6 and s.isdigit():
        if s[0] in "69":
            return f"{s}.SH"
        if s[0] in "03":
            return f"{s}.SZ"
    raise ValueError(f"无法识别的代码: {raw}")


def load_watchlist(conn) -> list[str]:
    with conn.cursor() as cur:
        cur.execute("SELECT ts_code FROM watchlist ORDER BY sort, id")
        return [r[0] for r in cur.fetchall()]


def load_all_codes(conn) -> list[str]:
    with conn.cursor() as cur:
        cur.execute("SELECT ts_code FROM stock_basic WHERE status=1 ORDER BY ts_code")
        return [r[0] for r in cur.fetchall()]


def main() -> int:
    parser = argparse.ArgumentParser(description="个股日线采集（Baostock）")
    src = parser.add_mutually_exclusive_group()
    src.add_argument("--codes", help="逗号分隔的股票代码，如 600519.SH,000001.SZ")
    src.add_argument("--watchlist", action="store_true", help="采集自选列表中的股票")
    src.add_argument("--all", action="store_true", help="采集 stock_basic 中全部上市股票")
    parser.add_argument("--list", action="store_true", help="仅更新股票列表与行业")
    parser.add_argument("--backfill", action="store_true", help="从 HISTORY_START 全量回填")
    parser.add_argument("--start", help="回填起始日 YYYY-MM-DD（默认 config.HISTORY_START）")
    parser.add_argument("--limit", type=int, help="最多采集 N 只，用于试跑")
    args = parser.parse_args()

    conn = dbm.get_conn()
    dbm.init_db(conn)

    if args.list:
        n = stock_client.fetch_stock_basic(conn, dbm)
        print(f"[done] 股票列表更新 {n} 条")
        if not (args.codes or args.watchlist or args.all):
            return 0

    if args.codes:
        codes = [normalize_code(c) for c in args.codes.split(",") if c.strip()]
    elif args.watchlist:
        codes = load_watchlist(conn)
        if not codes:
            print("[warn] 自选列表为空，请先在前端「自选预警」添加或使用 --codes")
            return 1
    elif args.all:
        codes = load_all_codes(conn)
        if not codes:
            print("[info] 股票列表为空，先更新列表")
            stock_client.fetch_stock_basic(conn, dbm)
            codes = load_all_codes(conn)
    else:
        parser.print_help()
        return 0

    if args.limit:
        codes = codes[:args.limit]
    print(f"[info] 待采集 {len(codes)} 只，模式={'全量回填' if args.backfill else '增量'}")
    result = stock_client.fetch_daily_batch(conn, dbm, codes, start=args.start,
                                            backfill=args.backfill)
    print(f"[done] 入库 {result['rows']} 行，成功 {result['ok']} 只，失败 {len(result['failed'])} 只")
    return 0 if not result["failed"] else 2


if __name__ == "__main__":
    sys.exit(main())
