package com.stockanalyzer.analysis.controller;

import com.stockanalyzer.analysis.dao.ScreenDao;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 策略榜单接口：读取采集端写入的每日选股结果 */
@RestController
@RequestMapping("/screen")
public class ScreenController {

    private final ScreenDao screenDao;

    public ScreenController(ScreenDao screenDao) {
        this.screenDao = screenDao;
    }

    /** 榜单列表；date 省略时取最新交易日 */
    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) String date,
                                    @RequestParam(defaultValue = "20") int limit) {
        if (limit > 100) {
            limit = 100;
        }
        String tradeDate = (date == null || date.isEmpty()) ? screenDao.latestTradeDate() : date;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tradeDate", tradeDate);
        result.put("list", tradeDate == null ? Collections.emptyList() : screenDao.list(tradeDate, limit));
        result.put("disclaimer", "策略榜单基于历史行情量化筛选，仅供参考，不构成投资建议，不承诺任何收益。");
        return result;
    }

    /** 有榜单数据的交易日列表（倒序） */
    @GetMapping("/dates")
    public List<String> dates(@RequestParam(defaultValue = "30") int limit) {
        if (limit > 100) {
            limit = 100;
        }
        return screenDao.dates(limit);
    }
}
