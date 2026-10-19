package com.stockanalyzer.controller;

import com.stockanalyzer.dao.QuoteDao;
import com.stockanalyzer.model.IndexSummary;
import com.stockanalyzer.service.IndexAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 指数分析 REST 接口（P2） */
@RestController
@RequestMapping("/api/index")
public class IndexAnalysisController {

    private final IndexAnalysisService service;
    private final QuoteDao dao;

    public IndexAnalysisController(IndexAnalysisService service, QuoteDao dao) {
        this.service = service;
        this.dao = dao;
    }

    /** 全部已入库指数的分析摘要 */
    @GetMapping("/list")
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String code : dao.findIndexCodes()) {
            IndexSummary s = service.summary(code);
            if (s != null) {
                items.add(toMap(s));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", items.size());
        result.put("items", items);
        return result;
    }

    /** 单个指数分析摘要，支持 000300 / 000300.SH 混输 */
    @GetMapping("/{code}/summary")
    public ResponseEntity<Map<String, Object>> summary(@PathVariable String code) {
        IndexSummary s = service.summary(service.normalizeCode(code));
        if (s == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toMap(s));
    }

    /** 数据覆盖情况：每个指数的起止日期与行数 */
    @GetMapping("/coverage")
    public List<QuoteDao.IndexCoverage> coverage() {
        return dao.findCoverage();
    }

    private Map<String, Object> toMap(IndexSummary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tsCode", s.getTsCode());
        m.put("latestDate", s.getLatestDate());
        m.put("latestClose", s.getLatestClose());
        m.put("latestPctChg", s.getLatestPctChg());
        m.put("ma5", s.getMa5());
        m.put("ma20", s.getMa20());
        m.put("ma60", s.getMa60());
        m.put("change20d", s.getChange20d());
        m.put("change60d", s.getChange60d());
        m.put("volatility20d", s.getVolatility20d());
        return m;
    }
}
