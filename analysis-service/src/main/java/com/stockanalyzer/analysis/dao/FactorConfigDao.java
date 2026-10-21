package com.stockanalyzer.analysis.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** 因子权重配置存取 */
@Repository
public class FactorConfigDao {

    private final JdbcTemplate jdbc;

    public FactorConfigDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findAll() {
        return jdbc.query("SELECT id, name, weights, is_default, updated_at FROM factor_config ORDER BY is_default DESC, id",
                FactorConfigDao::mapRow);
    }

    public Map<String, Object> findDefault() {
        List<Map<String, Object>> list = jdbc.query(
                "SELECT id, name, weights, is_default, updated_at FROM factor_config WHERE is_default = 1 LIMIT 1",
                FactorConfigDao::mapRow);
        return list.isEmpty() ? null : list.get(0);
    }

    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> list = jdbc.query(
                "SELECT id, name, weights, is_default, updated_at FROM factor_config WHERE id = ?",
                FactorConfigDao::mapRow, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public long insert(String name, String weightsJson, boolean isDefault) {
        jdbc.update("INSERT INTO factor_config(name, weights, is_default, updated_at) VALUES(?,?,?,?)",
                name, weightsJson, isDefault ? 1 : 0, LocalDateTime.now());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    public void update(long id, String name, String weightsJson) {
        jdbc.update("UPDATE factor_config SET name = ?, weights = ?, updated_at = ? WHERE id = ?",
                name, weightsJson, LocalDateTime.now(), id);
    }

    public void clearDefault() {
        jdbc.update("UPDATE factor_config SET is_default = 0");
    }

    public void setDefault(long id) {
        jdbc.update("UPDATE factor_config SET is_default = 1 WHERE id = ?", id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM factor_config WHERE id = ?", id);
    }

    private static Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> m = new HashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("name", rs.getString("name"));
        m.put("weights", rs.getString("weights"));
        m.put("isDefault", rs.getInt("is_default") == 1);
        m.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime().toString());
        return m;
    }
}
