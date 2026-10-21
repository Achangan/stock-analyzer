package com.stockanalyzer.portfolio.controller;

import com.stockanalyzer.portfolio.dao.PortfolioDao;
import com.stockanalyzer.portfolio.feign.AnalysisFeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 自选跟踪 + 预警 接口 */
@RestController
@RequestMapping("/")
public class WatchlistController {

    private final PortfolioDao dao;
    private final AnalysisFeignClient analysisClient;

    public WatchlistController(PortfolioDao dao, AnalysisFeignClient analysisClient) {
        this.dao = dao;
        this.analysisClient = analysisClient;
    }

    // ===== 自选 =====
    @GetMapping("/watchlist")
    public Map<String, Object> watchlist() {
        Map<String, Object> result = new HashMap<>();
        result.put("list", dao.findWatchlist());
        result.put("unreadCodes", dao.unreadCodes());
        return result;
    }

    @PostMapping("/watchlist")
    public Map<String, Object> addWatch(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", dao.addWatch(body.get("tsCode"), body.getOrDefault("note", "")));
        result.put("ok", true);
        return result;
    }

    @DeleteMapping("/watchlist/{id}")
    public Map<String, Object> removeWatch(@PathVariable long id) {
        dao.removeWatch(id);
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        return result;
    }

    // ===== 预警规则 =====
    @GetMapping("/alert/rules")
    public List<Map<String, Object>> rules() {
        return dao.findRules();
    }

    @PostMapping("/alert/rule")
    public Map<String, Object> addRule(@RequestBody Map<String, Object> body) {
        String tsCode = String.valueOf(body.getOrDefault("tsCode", "*"));
        String ruleType = String.valueOf(body.get("ruleType"));
        Double threshold = body.get("threshold") == null
                ? null : Double.parseDouble(String.valueOf(body.get("threshold")));
        Map<String, Object> result = new HashMap<>();
        result.put("id", dao.addRule(tsCode, ruleType, threshold));
        result.put("ok", true);
        return result;
    }

    @PostMapping("/alert/rule/{id}/toggle")
    public Map<String, Object> toggleRule(@PathVariable long id) {
        dao.toggleRule(id);
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        return result;
    }

    @DeleteMapping("/alert/rule/{id}")
    public Map<String, Object> removeRule(@PathVariable long id) {
        dao.removeRule(id);
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        return result;
    }

    // ===== 预警事件 =====
    @GetMapping("/alert/events")
    public List<Map<String, Object>> events(@RequestParam(value = "unread", defaultValue = "false") boolean unread,
                                            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return dao.findEvents(unread, limit);
    }

    @PostMapping("/alert/events/{id}/read")
    public Map<String, Object> markRead(@PathVariable long id) {
        dao.markRead(id);
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        return result;
    }

    @PostMapping("/alert/events/read-all")
    public Map<String, Object> markAllRead() {
        dao.markAllRead();
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        return result;
    }

    /** 用最新分析摘要评估全部启用规则；ts_code='*' 的规则对自选列表内全部指数生效 */
    @PostMapping("/alert/evaluate")
    public Map<String, Object> evaluate() {
        List<Map<String, Object>> rules = dao.findRules();
        List<Map<String, Object>> watch = dao.findWatchlist();
        List<String> watchCodes = new ArrayList<>();
        for (Map<String, Object> w : watch) {
            watchCodes.add((String) w.get("tsCode"));
        }

        List<String> triggered = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            if (!(Boolean) rule.get("enabled")) {
                continue;
            }
            String ruleCode = (String) rule.get("tsCode");
            List<String> targets = "*".equals(ruleCode)
                    ? watchCodes
                    : java.util.Collections.singletonList(ruleCode);
            for (String code : targets) {
                Map<String, Object> s;
                try {
                    s = analysisClient.getIndexSummary(code);
                } catch (Exception e) {
                    continue;
                }
                if (s == null || s.get("latestDate") == null) {
                    continue;
                }
                String message = checkRule(rule, s);
                if (message != null) {
                    long ruleId = ((Number) rule.get("id")).longValue();
                    String tradeDate = String.valueOf(s.get("latestDate"));
                    if (!dao.eventExists(ruleId, code, tradeDate)) {
                        dao.addEvent(ruleId, code, tradeDate, message);
                        triggered.add(code + ": " + message);
                    }
                }
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("evaluated", rules.size());
        result.put("triggered", triggered);
        result.put("disclaimer", "预警基于历史行情规则触发，仅供参考，不构成投资建议。");
        return result;
    }

    /** 单条规则对单个指数摘要的判断，命中返回消息文本，否则 null */
    private String checkRule(Map<String, Object> rule, Map<String, Object> s) {
        String type = (String) rule.get("ruleType");
        BigDecimal threshold = (BigDecimal) rule.get("threshold");
        switch (type) {
            case "ma20_break": {
                Double close = toDouble(s.get("latestClose"));
                Double ma20 = toDouble(s.get("ma20"));
                if (close != null && ma20 != null && close < ma20) {
                    return String.format("收盘价 %.2f 跌破 MA20 (%.2f)", close, ma20);
                }
                return null;
            }
            case "bias20": {
                Double bias = toDouble(s.get("bias20"));
                if (bias != null && threshold != null && Math.abs(bias) >= threshold.doubleValue()) {
                    return String.format("MA20 乖离率 %.2f%% 超阈值 %.2f%%", bias, threshold.doubleValue());
                }
                return null;
            }
            case "pct_chg": {
                Double pct = toDouble(s.get("latestPctChg"));
                if (pct != null && threshold != null && Math.abs(pct) >= threshold.doubleValue()) {
                    return String.format("当日涨跌幅 %.2f%% 超阈值 %.2f%%", pct, threshold.doubleValue());
                }
                return null;
            }
            default:
                return null;
        }
    }

    private Double toDouble(Object v) {
        if (v == null) {
            return null;
        }
        return Double.parseDouble(String.valueOf(v));
    }
}
