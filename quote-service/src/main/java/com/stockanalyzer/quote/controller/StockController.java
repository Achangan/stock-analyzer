package com.stockanalyzer.quote.controller;

import com.stockanalyzer.quote.dao.StockDao;
import com.stockanalyzer.quote.model.IndexDaily;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 个股行情接口 */
@RestController
@RequestMapping("/stock")
public class StockController {

    private final StockDao stockDao;

    public StockController(StockDao stockDao) {
        this.stockDao = stockDao;
    }

    /** 行业下拉选项 */
    @GetMapping("/industries")
    public List<String> industries() {
        return stockDao.findIndustries();
    }

    /** 个股列表（分页 + 关键词 + 行业；sort 支持 close/pct） */
    @GetMapping("/list")
    public Map<String, Object> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String industry,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {
        if (size > 100) size = 100;
        Map<String, Object> r = new HashMap<>();
        r.put("list", stockDao.findList(keyword, industry, page, size, sort, order));
        r.put("total", stockDao.countList(keyword, industry));
        r.put("page", page);
        r.put("size", size);
        return r;
    }

    /** 个股详情（基础信息 + 数据覆盖 + 最新行情） */
    @GetMapping("/{code}")
    public StockDao.StockDetail detail(@PathVariable String code) {
        StockDao.StockDetail d = stockDao.findDetail(normalizeCode(code));
        if (d == null) {
            throw new IllegalArgumentException("未找到个股: " + code);
        }
        return d;
    }

    /** 最近 N 个交易日日线 */
    @GetMapping("/{code}/recent")
    public List<IndexDaily> recent(
            @PathVariable String code,
            @RequestParam(defaultValue = "61") int limit) {
        return stockDao.findRecent(normalizeCode(code), limit);
    }

    /** 区间日线（升序） */
    @GetMapping("/{code}/range")
    public List<IndexDaily> range(
            @PathVariable String code,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        return stockDao.findRange(normalizeCode(code), start, end);
    }

    /** 代码归一化：允许 600519、600519.SH、600519SH 混输 */
    private String normalizeCode(String raw) {
        String code = raw.trim().toUpperCase();
        if (code.contains(".")) return code;
        if (code.matches("^\\d{6}$")) {
            return code.startsWith("39") ? code + ".SZ" : code + ".SH";
        }
        if (code.endsWith("SH")) return code.substring(0, code.length() - 2) + ".SH";
        if (code.endsWith("SZ")) return code.substring(0, code.length() - 2) + ".SZ";
        return code;
    }
}
