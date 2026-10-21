"""个股行情客户端：Baostock 免费通道（无需 token）

定位：daily_quote 表（个股日线）与 stock_basic 表（股票列表）的数据来源。

- 复权口径：前复权（adjustflag=2），均线/回测等指标连续，可反复回填（主键去重）
- 速度：单只约 0.1~0.8s（一年数据），全市场 5000+ 只集中在夜间/回填执行；
  日常增量建议只采自选或指定代码
- 字段映射：volume(股) → vol，amount(元) → amount，pctChg → pct_chg；
  change 由 close - preclose 计算
"""
from datetime import datetime, timedelta

import config

DAILY_FIELDS = "date,code,open,high,low,close,preclose,volume,amount,pctChg"
DAILY_COLUMNS = ["ts_code", "trade_date", "open", "high", "low", "close",
                 "pre_close", "change", "pct_chg", "vol", "amount"]


def _to_bs_code(ts_code: str) -> str:
    """600519.SH -> sh.600519"""
    code, market = ts_code.split(".")
    return f"{market.lower()}.{code}"


def _to_ts_code(bs_code: str) -> str:
    """sh.600519 -> 600519.SH"""
    market, code = bs_code.split(".")
    return f"{code}.{market.upper()}"


def _f(v):
    """空串转 None，其余转 float"""
    return float(v) if v not in ("", None) else None


def fetch_stock_basic(conn, db) -> int:
    """更新股票列表（仅沪深 A 股，status=1）与所属行业"""
    import baostock as bs

    lg = bs.login()
    if lg.error_code != "0":
        db.log_collect(conn, "baostock", "stock_basic", 0, "failed", lg.error_msg)
        return 0
    try:
        rs = bs.query_stock_basic()
        basic: dict[str, tuple] = {}
        while rs.error_code == "0" and rs.next():
            d = rs.get_row_data()  # code, code_name, ipoDate, outDate, type, status
            if d[4] != "1":  # 1=股票，2=指数，3=其他
                continue
            basic[_to_ts_code(d[0])] = (d[1], d[2] or None, d[3] or None, d[5])

        industry: dict[str, str] = {}
        rs2 = bs.query_stock_industry()
        while rs2.error_code == "0" and rs2.next():
            d = rs2.get_row_data()  # updateDate, code, code_name, industry, classification
            industry[_to_ts_code(d[1])] = d[3] or None

        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        rows = [
            (code, name, industry.get(code), list_date, delist_date,
             1 if status == "1" else 0, now)
            for code, (name, list_date, delist_date, status) in basic.items()
        ]
        n = db.upsert_many(conn, "stock_basic",
                           ["ts_code", "name", "industry", "list_date", "delist_date",
                            "status", "updated_at"], rows)
        db.log_collect(conn, "baostock", "stock_basic", n, "success",
                       f"行业 {len(industry)} 条")
        return n
    finally:
        bs.logout()


def fetch_daily_batch(conn, db, codes: list[str], start: str | None = None,
                      backfill: bool = False, batch_size: int = 50) -> dict:
    """批量采集个股日线。

    backfill=True 时从 start/config.HISTORY_START 全量拉取；
    否则按库内最新日期次日增量。
    """
    import baostock as bs

    if not codes:
        return {"rows": 0, "ok": 0, "failed": []}

    lg = bs.login()
    if lg.error_code != "0":
        db.log_collect(conn, "baostock", "daily_quote:batch", 0, "failed", lg.error_msg)
        return {"rows": 0, "ok": 0, "failed": codes}

    total, ok, failed = 0, 0, []
    buffer: list[tuple] = []
    try:
        for i, ts_code in enumerate(codes, 1):
            if backfill:
                begin = start or config.HISTORY_START
            else:
                last = db.last_trade_date(conn, "daily_quote", ts_code)
                begin = ((datetime.strptime(last, "%Y-%m-%d") + timedelta(days=1))
                         .strftime("%Y-%m-%d")) if last else (start or config.HISTORY_START)
            end = datetime.now().strftime("%Y-%m-%d")
            if begin > end:
                ok += 1
                continue
            # 长时间批量拉取会掉登录态（"用户未登录"），重登后重试一次
            for attempt in (1, 2):
                try:
                    buffer.extend(_fetch_one(bs, ts_code, begin, end))
                    ok += 1
                    break
                except Exception as e:
                    if attempt == 1 and "未登录" in str(e):
                        print(f"[relogin] 登录态失效，重新登录后继续（{ts_code}）")
                        bs.logout()
                        bs.login()
                        continue
                    failed.append((ts_code, str(e)))
                    break

            if i % batch_size == 0 or i == len(codes):
                total += db.upsert_many(conn, "daily_quote", DAILY_COLUMNS, buffer)
                buffer = []
                print(f"[{i}/{len(codes)}] 累计入库 {total} 行，失败 {len(failed)} 只")

        msg = f"代码 {len(codes)} 只，失败 {len(failed)} 只"
        if failed:
            msg += "：" + "; ".join(f"{c}:{e}" for c, e in failed[:5])
        db.log_collect(conn, "baostock", "daily_quote:batch", total,
                       "success" if not failed else "partial", msg)
        return {"rows": total, "ok": ok, "failed": failed}
    finally:
        bs.logout()


def _fetch_one(bs, ts_code: str, start: str, end: str) -> list[tuple]:
    """单只个股日线，返回可入库元组；查询异常抛错由上层记录"""
    rs = bs.query_history_k_data_plus(
        _to_bs_code(ts_code), DAILY_FIELDS,
        start_date=start, end_date=end, frequency="d", adjustflag="2")
    if rs.error_code != "0":
        raise RuntimeError(rs.error_msg)
    rows = []
    while rs.error_code == "0" and rs.next():
        d = rs.get_row_data()  # date,code,open,high,low,close,preclose,volume,amount,pctChg
        close, pre = _f(d[5]), _f(d[6])
        change = round(close - pre, 4) if close is not None and pre is not None else None
        rows.append((ts_code, d[0], _f(d[2]), _f(d[3]), _f(d[4]), close,
                     pre, change, _f(d[9]), _f(d[7]), _f(d[8])))
    return rows
