package com.stockanalyzer.analysis.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockanalyzer.analysis.dao.FactorConfigDao;
import com.stockanalyzer.analysis.service.FactorScoreService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 因子权重打分接口 */
@RestController
@RequestMapping("/factor")
public class FactorController {

    private final FactorScoreService scoreService;
    private final FactorConfigDao configDao;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FactorController(FactorScoreService scoreService, FactorConfigDao configDao) {
        this.scoreService = scoreService;
        this.configDao = configDao;
    }

    /** 打分排名：可用 weights(JSON) 或 configId 指定权重，否则用默认配置 */
    @GetMapping("/score")
    public Map<String, Object> score(@RequestParam(value = "weights", required = false) String weights,
                                     @RequestParam(value = "configId", required = false) Long configId) {
        return scoreService.score(scoreService.resolveWeights(weights, configId));
    }

    /** 权重配置列表 */
    @GetMapping("/configs")
    public List<Map<String, Object>> configs() {
        return configDao.findAll();
    }

    /** 新增/更新配置；传 id 为更新 */
    @PostMapping("/config")
    public Map<String, Object> save(@RequestBody Map<String, Object> body) throws Exception {
        String name = String.valueOf(body.getOrDefault("name", "未命名"));
        @SuppressWarnings("unchecked")
        Map<String, Double> weights = (Map<String, Double>) body.get("weights");
        String weightsJson = objectMapper.writeValueAsString(weights);
        Map<String, Object> result = new HashMap<>();
        if (body.get("id") != null) {
            long id = Long.parseLong(String.valueOf(body.get("id")));
            configDao.update(id, name, weightsJson);
            result.put("id", id);
        } else {
            boolean first = configDao.findAll().isEmpty();
            result.put("id", configDao.insert(name, weightsJson, first));
        }
        result.put("ok", true);
        return result;
    }

    /** 设为默认配置 */
    @PostMapping("/config/{id}/default")
    public Map<String, Object> setDefault(@PathVariable long id) {
        configDao.clearDefault();
        configDao.setDefault(id);
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        return result;
    }

    @DeleteMapping("/config/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        configDao.delete(id);
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        return result;
    }
}
