package com.stockanalyzer.analysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockanalyzer.analysis.dao.FactorConfigDao;
import com.stockanalyzer.analysis.feign.QuoteFeignClient;
import com.stockanalyzer.analysis.model.IndexSummary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 因子权重打分：动量/趋势/波动率/乖离率 四因子 min-max 归一化加权 */
@Service
public class FactorScoreService {

    public static final String[] FACTORS = {"mom20", "trend", "vol", "bias"};
    public static final Map<String, Double> DEFAULT_WEIGHTS = new LinkedHashMap<>();
    static {
        DEFAULT_WEIGHTS.put("mom20", 0.4);
        DEFAULT_WEIGHTS.put("trend", 0.3);
        DEFAULT_WEIGHTS.put("vol", 0.2);
        DEFAULT_WEIGHTS.put("bias", 0.1);
    }

    private final IndexAnalysisService analysisService;
    private final FactorConfigDao configDao;
    private final QuoteFeignClient quoteClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FactorScoreService(IndexAnalysisService analysisService, FactorConfigDao configDao,
                              QuoteFeignClient quoteClient) {
        this.analysisService = analysisService;
        this.configDao = configDao;
        this.quoteClient = quoteClient;
    }

    /** 解析有效权重：显式 JSON > 指定配置 > 默认配置 > 内置默认 */
    public Map<String, Double> resolveWeights(String weightsJson, Long configId) {
        if (weightsJson != null && !weightsJson.isEmpty()) {
            try {
                Map<String, Double> w = objectMapper.readValue(weightsJson,
                        new TypeReference<Map<String, Double>>() {});
                if (!w.isEmpty()) {
                    return w;
                }
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> cfg = configId != null ? configDao.findById(configId) : configDao.findDefault();
        if (cfg != null) {
            try {
                Map<String, Double> w = objectMapper.readValue((String) cfg.get("weights"),
                        new TypeReference<Map<String, Double>>() {});
                if (!w.isEmpty()) {
                    return w;
                }
            } catch (Exception ignored) {
            }
        }
        return new LinkedHashMap<>(DEFAULT_WEIGHTS);
    }

    /** 对全部指数打分排名 */
    public Map<String, Object> score(Map<String, Double> weights) {
        List<String> codes = quoteClient.getIndexCodes();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String code : codes) {
            IndexSummary s;
            try {
                s = analysisService.summary(code);
            } catch (Exception e) {
                continue;
            }
            if (s == null) {
                continue;
            }
            Map<String, Double> raw = new HashMap<>();
            raw.put("mom20", s.getChange20d());
            raw.put("trend", trendScore(s));
            raw.put("vol", s.getVolatility20d());
            raw.put("bias", s.getBias20());
            Map<String, Object> row = new HashMap<>();
            row.put("tsCode", code);
            row.put("latestClose", s.getLatestClose());
            row.put("trend", s.getTrend());
            row.put("raw", raw);
            rows.add(row);
        }

        // min-max 归一化：mom20/trend 越高越好，vol/bias 越低越好
        for (String f : FACTORS) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (Map<String, Object> row : rows) {
                @SuppressWarnings("unchecked")
                Double v = ((Map<String, Double>) row.get("raw")).get(f);
                if (v == null) {
                    continue;
                }
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            boolean higherBetter = f.equals("mom20") || f.equals("trend");
            for (Map<String, Object> row : rows) {
                @SuppressWarnings("unchecked")
                Map<String, Double> raw = (Map<String, Double>) row.get("raw");
                Double v = raw.get(f);
                double norm;
                if (v == null || max == min) {
                    norm = 50.0;
                } else {
                    norm = (v - min) / (max - min) * 100.0;
                    if (!higherBetter) {
                        norm = 100.0 - norm;
                    }
                }
                raw.put(f + "Norm", Math.round(norm * 100.0) / 100.0);
            }
        }

        // 权重归一化后加权求和
        double wSum = 0;
        Map<String, Double> w = new LinkedHashMap<>();
        for (String f : FACTORS) {
            double weight = weights.getOrDefault(f, 0.0);
            w.put(f, weight);
            wSum += weight;
        }
        if (wSum <= 0) {
            for (String f : FACTORS) {
                w.put(f, 1.0);
            }
            wSum = FACTORS.length;
        }
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Double> raw = (Map<String, Double>) row.get("raw");
            double score = 0;
            for (String f : FACTORS) {
                score += w.get(f) / wSum * raw.get(f + "Norm");
            }
            row.put("score", Math.round(score * 100.0) / 100.0);
        }
        rows.sort(Comparator.comparingDouble((Map<String, Object> r) -> (Double) r.get("score")).reversed());

        Map<String, Object> result = new HashMap<>();
        result.put("weights", w);
        result.put("list", rows);
        result.put("disclaimer", "因子评分基于历史行情，仅供参考，不构成投资建议，不承诺任何收益。");
        return result;
    }

    /** 趋势因子：MA5>MA20 得 0.5，MA20>MA60 再得 0.5 */
    private Double trendScore(IndexSummary s) {
        if (s.getMa5() == null || s.getMa20() == null || s.getMa60() == null) {
            return null;
        }
        double score = 0;
        if (s.getMa5() > s.getMa20()) {
            score += 0.5;
        }
        if (s.getMa20() > s.getMa60()) {
            score += 0.5;
        }
        return score;
    }
}
