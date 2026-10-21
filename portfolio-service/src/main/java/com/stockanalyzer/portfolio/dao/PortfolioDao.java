package com.stockanalyzer.portfolio.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 自选/预警规则/预警事件/持仓 数据访问 */
@Repository
public class PortfolioDao {

    private final JdbcTemplate jdbc;

    public PortfolioDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ===== watchlist =====
    public List<Map<String, Object>> findWatchlist() {
        return jdbc.query("SELECT id, ts_code, note, sort, created_at FROM watchlist ORDER BY sort, id",
                PortfolioDao::mapWatch);
    }

    public long addWatch(String tsCode, String note) {
        jdbc.update("INSERT INTO watchlist(ts_code, note, sort, created_at) VALUES(?,?,0,?)"
                        + " ON DUPLICATE KEY UPDATE note = VALUES(note)",
                tsCode, note, LocalDateTime.now());
        Long id = jdbc.queryForObject("SELECT id FROM watchlist WHERE ts_code = ?", Long.class, tsCode);
        return id == null ? -1 : id;
    }

    public void removeWatch(long id) {
        jdbc.update("DELETE FROM watchlist WHERE id = ?", id);
    }

    // ===== alert_rule =====
    public List<Map<String, Object>> findRules() {
        return jdbc.query("SELECT id, ts_code, rule_type, threshold, enabled, created_at FROM alert_rule ORDER BY id",
                PortfolioDao::mapRule);
    }

    public long addRule(String tsCode, String ruleType, Double threshold) {
        jdbc.update("INSERT INTO alert_rule(ts_code, rule_type, threshold, enabled, created_at) VALUES(?,?,?,1,?)",
                tsCode, ruleType, threshold, LocalDateTime.now());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    public void toggleRule(long id) {
        jdbc.update("UPDATE alert_rule SET enabled = 1 - enabled WHERE id = ?", id);
    }

    public void removeRule(long id) {
        jdbc.update("DELETE FROM alert_rule WHERE id = ?", id);
    }

    // ===== alert_event =====
    public List<Map<String, Object>> findEvents(boolean unreadOnly, int limit) {
        String sql = "SELECT id, rule_id, ts_code, trade_date, message, read_flag, created_at FROM alert_event"
                + (unreadOnly ? " WHERE read_flag = 0" : "") + " ORDER BY id DESC LIMIT ?";
        return jdbc.query(sql, PortfolioDao::mapEvent, limit);
    }

    /** 同一规则同一指数同一交易日只记一次 */
    public boolean eventExists(long ruleId, String tsCode, String tradeDate) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM alert_event WHERE rule_id = ? AND ts_code = ? AND trade_date = ?",
                Integer.class, ruleId, tsCode, tradeDate);
        return cnt != null && cnt > 0;
    }

    public void addEvent(long ruleId, String tsCode, String tradeDate, String message) {
        jdbc.update("INSERT INTO alert_event(rule_id, ts_code, trade_date, message, read_flag, created_at)"
                        + " VALUES(?,?,?,?,0,?)",
                ruleId, tsCode, tradeDate, message, LocalDateTime.now());
    }

    public void markRead(long id) {
        jdbc.update("UPDATE alert_event SET read_flag = 1 WHERE id = ?", id);
    }

    public void markAllRead() {
        jdbc.update("UPDATE alert_event SET read_flag = 1 WHERE read_flag = 0");
    }

    public List<String> unreadCodes() {
        return jdbc.queryForList("SELECT DISTINCT ts_code FROM alert_event WHERE read_flag = 0", String.class);
    }

    // ===== position =====
    public List<Map<String, Object>> findPositions() {
        return jdbc.query("SELECT id, ts_code, name, cost_price, shares, buy_date, note FROM portfolio_position ORDER BY id",
                PortfolioDao::mapPosition);
    }

    public long addPosition(String tsCode, String name, double costPrice, double shares, String buyDate, String note) {
        jdbc.update("INSERT INTO portfolio_position(ts_code, name, cost_price, shares, buy_date, note) VALUES(?,?,?,?,?,?)",
                tsCode, name, costPrice, shares, buyDate, note);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    public void removePosition(long id) {
        jdbc.update("DELETE FROM portfolio_position WHERE id = ?", id);
    }

    // ===== row mappers =====
    private static Map<String, Object> mapWatch(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("tsCode", rs.getString("ts_code"));
        m.put("note", rs.getString("note"));
        m.put("sort", rs.getInt("sort"));
        m.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString());
        return m;
    }

    private static Map<String, Object> mapRule(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("tsCode", rs.getString("ts_code"));
        m.put("ruleType", rs.getString("rule_type"));
        m.put("threshold", rs.getBigDecimal("threshold"));
        m.put("enabled", rs.getInt("enabled") == 1);
        m.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString());
        return m;
    }

    private static Map<String, Object> mapEvent(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("ruleId", rs.getLong("rule_id"));
        m.put("tsCode", rs.getString("ts_code"));
        m.put("tradeDate", rs.getDate("trade_date") == null ? null : rs.getDate("trade_date").toString());
        m.put("message", rs.getString("message"));
        m.put("readFlag", rs.getInt("read_flag") == 1);
        m.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString());
        return m;
    }

    private static Map<String, Object> mapPosition(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("tsCode", rs.getString("ts_code"));
        m.put("name", rs.getString("name"));
        m.put("costPrice", rs.getBigDecimal("cost_price"));
        m.put("shares", rs.getBigDecimal("shares"));
        m.put("buyDate", rs.getDate("buy_date") == null ? null : rs.getDate("buy_date").toString());
        m.put("note", rs.getString("note"));
        return m;
    }
}

