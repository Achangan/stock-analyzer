package com.stockanalyzer.analysis.controller;

import com.stockanalyzer.analysis.dao.BacktestResultDao;
import com.stockanalyzer.analysis.service.BacktestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 策略回测接口 */
@RestController
@RequestMapping("/backtest")
public class BacktestController {

    private final BacktestService backtestService;
    private final BacktestResultDao resultDao;

    public BacktestController(BacktestService backtestService, BacktestResultDao resultDao) {
        this.backtestService = backtestService;
        this.resultDao = resultDao;
    }

    /** 双均线回测：{"tsCode":"000300.SH","fast":5,"slow":20,"start":"2023-01-01","end":"2026-10-20","initial":100000} */
    @PostMapping("/ma-cross")
    public Map<String, Object> maCross(@RequestBody Map<String, Object> body) throws Exception {
        String tsCode = String.valueOf(body.get("tsCode"));
        int fast = Integer.parseInt(String.valueOf(body.getOrDefault("fast", 5)));
        int slow = Integer.parseInt(String.valueOf(body.getOrDefault("slow", 20)));
        String start = body.get("start") == null ? null : String.valueOf(body.get("start"));
        String end = body.get("end") == null ? null : String.valueOf(body.get("end"));
        double initial = Double.parseDouble(String.valueOf(body.getOrDefault("initial", 100000)));
        return backtestService.maCross(tsCode, fast, slow, start, end, initial);
    }

    /** 历史回测记录（不含净值明细） */
    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(value = "tsCode", required = false) String tsCode,
                                             @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return resultDao.findRecent(tsCode, limit);
    }
}
