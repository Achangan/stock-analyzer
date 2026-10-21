package com.stockanalyzer.portfolio.controller;

import com.stockanalyzer.portfolio.dao.PortfolioDao;
import com.stockanalyzer.portfolio.feign.AnalysisFeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 持仓管理：盈亏计算 + 仓位公式风险提示 */
@RestController
@RequestMapping("/position")
public class PositionController {

    /** 仓位上限：单笔风险 1% ÷ 止损 5% = 20% */
    private static final double POSITION_LIMIT_PCT = 20.0;

    private final PortfolioDao dao;
    private final AnalysisFeignClient analysisClient;

    public PositionController(PortfolioDao dao, AnalysisFeignClient analysisClient) {
        this.dao = dao;
        this.analysisClient = analysisClient;
    }

    /** 持仓列表（含实时盈亏与仓位占比） */
    @GetMapping("/pnl")
    public Map<String, Object> pnl() {
        List<Map<String, Object>> positions = dao.findPositions();

        // 先算总市值
        double totalMarketValue = 0;
        Map<Long, Double> priceCache = new HashMap<>();
        for (Map<String, Object> p : positions) {
            Double price = latestPrice((String) p.get("tsCode"));
            priceCache.put((Long) p.get("id"), price);
            if (price != null) {
                totalMarketValue += price * ((BigDecimal) p.get("shares")).doubleValue();
            }
        }

        for (Map<String, Object> p : positions) {
            long id = (Long) p.get("id");
            Double price = priceCache.get(id);
            double cost = ((BigDecimal) p.get("costPrice")).doubleValue();
            double shares = ((BigDecimal) p.get("shares")).doubleValue();
            double costValue = cost * shares;
            p.put("costValue", round2(costValue));
            if (price != null) {
                double marketValue = price * shares;
                double pnlValue = marketValue - costValue;
                double pnlPct = cost > 0 ? (price / cost - 1) * 100 : 0;
                double weightPct = totalMarketValue > 0 ? marketValue / totalMarketValue * 100 : 0;
                p.put("latestPrice", price);
                p.put("marketValue", round2(marketValue));
                p.put("pnl", round2(pnlValue));
                p.put("pnlPct", round2(pnlPct));
                p.put("weightPct", round2(weightPct));
                p.put("overWeight", weightPct > POSITION_LIMIT_PCT);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("totalMarketValue", round2(totalMarketValue));
        result.put("positionLimitPct", POSITION_LIMIT_PCT);
        result.put("list", positions);
        result.put("disclaimer", "盈亏按最新收盘估算，仅供参考，不构成投资建议，不承诺任何收益。");
        return result;
    }

    @PostMapping("")
    public Map<String, Object> add(@RequestBody Map<String, Object> body) {
        String tsCode = String.valueOf(body.get("tsCode"));
        String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        double costPrice = Double.parseDouble(String.valueOf(body.get("costPrice")));
        double shares = Double.parseDouble(String.valueOf(body.get("shares")));
        String buyDate = body.get("buyDate") == null ? null : String.valueOf(body.get("buyDate"));
        String note = body.get("note") == null ? null : String.valueOf(body.get("note"));
        Map<String, Object> result = new HashMap<>();
        result.put("id", dao.addPosition(tsCode, name, costPrice, shares, buyDate, note));
        result.put("ok", true);
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable long id) {
        dao.removePosition(id);
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        return result;
    }

    private Double latestPrice(String tsCode) {
        try {
            Map<String, Object> s = analysisClient.getIndexSummary(tsCode);
            if (s != null && s.get("latestClose") != null) {
                return Double.parseDouble(String.valueOf(s.get("latestClose")));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
