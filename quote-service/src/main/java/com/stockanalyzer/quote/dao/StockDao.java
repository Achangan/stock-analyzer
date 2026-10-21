package com.stockanalyzer.quote.dao;

import com.stockanalyzer.quote.model.IndexDaily;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 个股数据访问（daily_quote + stock_basic） */
@Repository
public class StockDao {

    private static final String DAILY_COLS =
            "ts_code, trade_date, open, high, low, close, pre_close, pct_chg, vol, amount";

    private final JdbcTemplate jdbc;

    public StockDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 行业下拉选项 */
    public List<String> findIndustries() {
        return jdbc.queryForList(
                "SELECT DISTINCT industry FROM stock_basic WHERE industry IS NOT NULL AND industry <> '' ORDER BY industry",
                String.class);
    }

    /** 股票列表（分页 + 关键词 + 行业 + 排序）。keyword 匹配 code 或 name（前后模糊） */
    public List<StockBrief> findList(String keyword, String industry, int page, int size,
                                     String sort, String order) {
        StringBuilder sql = new StringBuilder(
                "SELECT b.ts_code, b.name, b.industry, b.list_date, "
                        + "q.close AS latest_close, q.pct_chg AS latest_pct "
                        + "FROM stock_basic b "
                        + "LEFT JOIN daily_quote q ON q.ts_code = b.ts_code "
                        + "  AND q.trade_date = (SELECT MAX(trade_date) FROM daily_quote WHERE ts_code = b.ts_code) "
                        + "WHERE b.status = 1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            sql.append(" AND (b.ts_code LIKE ? OR b.name LIKE ?)");
            String like = "%" + keyword + "%";
            args.add(like);
            args.add(like);
        }
        if (industry != null && !industry.trim().isEmpty()) {
            sql.append(" AND b.industry = ?");
            args.add(industry);
        }
        sql.append(" ORDER BY ").append(orderBy(sort, order)).append(" LIMIT ? OFFSET ?");
        args.add(size);
        args.add((page - 1) * size);

        return jdbc.query(sql.toString(), (rs, i) -> new StockBrief(
                rs.getString("ts_code"),
                rs.getString("name"),
                rs.getString("industry"),
                rs.getString("list_date"),
                dbl(rs, "latest_close"),
                dbl(rs, "latest_pct")),
                args.toArray());
    }

    /** 排序白名单：仅允许最新价 / 涨跌幅，空值统一排在最后，避免 SQL 注入 */
    private static String orderBy(String sort, String order) {
        if ("close".equals(sort) || "pct".equals(sort)) {
            String col = "close".equals(sort) ? "q.close" : "q.pct_chg";
            String dir = "desc".equalsIgnoreCase(order) ? "DESC" : "ASC";
            return col + " IS NULL, " + col + " " + dir;
        }
        return "b.ts_code ASC";
    }

    /** 列表总行数（用于分页） */
    public int countList(String keyword, String industry) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM stock_basic b WHERE b.status = 1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            sql.append(" AND (b.ts_code LIKE ? OR b.name LIKE ?)");
            String like = "%" + keyword + "%";
            args.add(like);
            args.add(like);
        }
        if (industry != null && !industry.trim().isEmpty()) {
            sql.append(" AND b.industry = ?");
            args.add(industry);
        }
        Integer c = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return c == null ? 0 : c;
    }

    /** 按代码查个股基础信息（不存在返回 null） */
    public StockDetail findDetail(String tsCode) {
        List<StockDetail> rows = jdbc.query(
                "SELECT b.ts_code, b.name, b.industry, b.list_date, "
                        + "MIN(q.trade_date) AS start_date, MAX(q.trade_date) AS end_date, COUNT(q.trade_date) AS rows_count, "
                        + "q2.close AS latest_close, q2.pct_chg AS latest_pct "
                        + "FROM stock_basic b "
                        + "LEFT JOIN daily_quote q ON q.ts_code = b.ts_code "
                        + "LEFT JOIN daily_quote q2 ON q2.ts_code = b.ts_code "
                        + "  AND q2.trade_date = (SELECT MAX(trade_date) FROM daily_quote WHERE ts_code = b.ts_code) "
                        + "WHERE b.ts_code = ? "
                        + "GROUP BY b.ts_code, b.name, b.industry, b.list_date, q2.close, q2.pct_chg",
                (rs, i) -> new StockDetail(
                        rs.getString("ts_code"),
                        rs.getString("name"),
                        rs.getString("industry"),
                        rs.getString("list_date"),
                        rs.getString("start_date"),
                        rs.getString("end_date"),
                        rs.getInt("rows_count"),
                        dbl(rs, "latest_close"),
                        dbl(rs, "latest_pct")),
                tsCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 最近 N 个交易日日线 */
    public List<IndexDaily> findRecent(String tsCode, int limit) {
        return jdbc.query(
                "SELECT " + DAILY_COLS + " FROM daily_quote WHERE ts_code = ? ORDER BY trade_date DESC LIMIT ?",
                (rs, i) -> mapRow(rs), tsCode, limit);
    }

    /** 区间日线（升序） */
    public List<IndexDaily> findRange(String tsCode, String start, String end) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + DAILY_COLS + " FROM daily_quote WHERE ts_code = ?");
        List<Object> args = new ArrayList<>();
        args.add(tsCode);
        if (start != null && !start.trim().isEmpty()) {
            sql.append(" AND trade_date >= ?");
            args.add(start);
        }
        if (end != null && !end.trim().isEmpty()) {
            sql.append(" AND trade_date <= ?");
            args.add(end);
        }
        sql.append(" ORDER BY trade_date ASC");
        return jdbc.query(sql.toString(), (rs, i) -> mapRow(rs), args.toArray());
    }

    private static IndexDaily mapRow(ResultSet rs) throws SQLException {
        return new IndexDaily(
                rs.getString("ts_code"),
                rs.getString("trade_date"),
                dbl(rs, "open"), dbl(rs, "high"), dbl(rs, "low"), dbl(rs, "close"),
                dbl(rs, "pre_close"), dbl(rs, "pct_chg"), dbl(rs, "vol"), dbl(rs, "amount"));
    }

    private static Double dbl(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    /** 列表条目 */
    public static class StockBrief {
        public final String tsCode, name, industry, listDate;
        public final Double latestClose, latestPct;
        public StockBrief(String tsCode, String name, String industry, String listDate,
                          Double latestClose, Double latestPct) {
            this.tsCode = tsCode; this.name = name; this.industry = industry;
            this.listDate = listDate; this.latestClose = latestClose; this.latestPct = latestPct;
        }
    }

    /** 个股详情 */
    public static class StockDetail {
        public final String tsCode, name, industry, listDate, startDate, endDate;
        public final int rowsCount;
        public final Double latestClose, latestPct;
        public StockDetail(String tsCode, String name, String industry, String listDate,
                           String startDate, String endDate, int rowsCount,
                           Double latestClose, Double latestPct) {
            this.tsCode = tsCode; this.name = name; this.industry = industry;
            this.listDate = listDate; this.startDate = startDate; this.endDate = endDate;
            this.rowsCount = rowsCount; this.latestClose = latestClose; this.latestPct = latestPct;
        }
    }
}
