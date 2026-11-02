package com.stockanalyzer.analysis.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 每日策略榜单读取（数据由采集端 screen_stocks.py 写入） */
@Repository
public class ScreenDao {

    private final JdbcTemplate jdbc;

    public ScreenDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 最新榜单交易日，无数据返回 null */
    public String latestTradeDate() {
        List<String> list = jdbc.query("SELECT MAX(trade_date) FROM screen_result",
                (rs, i) -> rs.getDate(1) == null ? null : rs.getDate(1).toString());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 可选榜单日期（倒序） */
    public List<String> dates(int limit) {
        return jdbc.query(
                "SELECT DISTINCT trade_date FROM screen_result ORDER BY trade_date DESC LIMIT ?",
                (rs, i) -> rs.getDate(1).toString(), limit);
    }

    /** 指定交易日的榜单，按排名升序 */
    public List<Map<String, Object>> list(String tradeDate, int limit) {
        return jdbc.query(
                "SELECT trade_date, ts_code, name, industry, rank_no, close, pct_chg, pe, pb, mcap, "
                        + "mom5, mom20, trend, volratio, vol20, rsi14, drawdown60, score, created_at "
                        + "FROM screen_result WHERE trade_date=? ORDER BY rank_no LIMIT ?",
                ScreenDao::mapRow, tradeDate, limit);
    }

    private static Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradeDate", rs.getDate("trade_date").toString());
        m.put("tsCode", rs.getString("ts_code"));
        m.put("name", rs.getString("name"));
        m.put("industry", rs.getString("industry"));
        m.put("rankNo", rs.getInt("rank_no"));
        m.put("close", rs.getBigDecimal("close"));
        m.put("pctChg", rs.getBigDecimal("pct_chg"));
        m.put("pe", rs.getBigDecimal("pe"));
        m.put("pb", rs.getBigDecimal("pb"));
        m.put("mcap", rs.getBigDecimal("mcap"));
        m.put("mom5", rs.getBigDecimal("mom5"));
        m.put("mom20", rs.getBigDecimal("mom20"));
        m.put("trend", rs.getBigDecimal("trend"));
        m.put("volratio", rs.getBigDecimal("volratio"));
        m.put("vol20", rs.getBigDecimal("vol20"));
        m.put("rsi14", rs.getBigDecimal("rsi14"));
        m.put("drawdown60", rs.getBigDecimal("drawdown60"));
        m.put("score", rs.getBigDecimal("score"));
        m.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString());
        return m;
    }
}
