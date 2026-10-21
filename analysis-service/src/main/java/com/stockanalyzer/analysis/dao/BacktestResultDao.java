package com.stockanalyzer.analysis.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 回测结果存取 */
@Repository
public class BacktestResultDao {

    private final JdbcTemplate jdbc;

    public BacktestResultDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String tsCode, String strategy, String params, String start, String end,
                       double totalReturn, double maxDrawdown, double winRate, String navJson) {
        jdbc.update("INSERT INTO backtest_result(ts_code, strategy, params, start_date, end_date,"
                        + " total_return, max_drawdown, win_rate, nav_json, created_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                tsCode, strategy, params, start, end, totalReturn, maxDrawdown, winRate, navJson,
                LocalDateTime.now());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    public List<Map<String, Object>> findRecent(String tsCode, int limit) {
        if (tsCode != null && !tsCode.isEmpty()) {
            return jdbc.query("SELECT id, ts_code, strategy, params, start_date, end_date,"
                            + " total_return, max_drawdown, win_rate, created_at FROM backtest_result"
                            + " WHERE ts_code = ? ORDER BY id DESC LIMIT ?",
                    BacktestResultDao::mapRow, tsCode, limit);
        }
        return jdbc.query("SELECT id, ts_code, strategy, params, start_date, end_date,"
                        + " total_return, max_drawdown, win_rate, created_at FROM backtest_result"
                        + " ORDER BY id DESC LIMIT ?",
                BacktestResultDao::mapRow, limit);
    }

    private static Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("tsCode", rs.getString("ts_code"));
        m.put("strategy", rs.getString("strategy"));
        m.put("params", rs.getString("params"));
        m.put("startDate", rs.getDate("start_date").toString());
        m.put("endDate", rs.getDate("end_date").toString());
        m.put("totalReturn", rs.getDouble("total_return"));
        m.put("maxDrawdown", rs.getDouble("max_drawdown"));
        m.put("winRate", rs.getDouble("win_rate"));
        m.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString());
        return m;
    }
}
