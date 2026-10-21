package com.stockanalyzer.analysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockanalyzer.analysis.dao.BacktestResultDao;
import com.stockanalyzer.analysis.feign.QuoteFeignClient;
import com.stockanalyzer.analysis.model.IndexDaily;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 策略回测：双均线金叉买/死叉卖，全仓进出，暂不计手续费 */
@Service
public class BacktestService {

    private final QuoteFeignClient quoteClient;
    private final BacktestResultDao resultDao;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BacktestService(QuoteFeignClient quoteClient, BacktestResultDao resultDao) {
        this.quoteClient = quoteClient;
        this.resultDao = resultDao;
    }

    public Map<String, Object> maCross(String tsCode, int fast, int slow,
                                       String start, String end, double initial) throws Exception {
        if (fast >= slow) {
            throw new IllegalArgumentException("快线周期必须小于慢线周期");
        }
        List<IndexDaily> rows = quoteClient.getRangeQuotes(tsCode, start, end);
        if (rows == null || rows.size() < slow + 2) {
            throw new IllegalArgumentException("区间数据不足，无法回测");
        }

        int n = rows.size();
        double[] close = new double[n];
        for (int i = 0; i < n; i++) {
            close[i] = rows.get(i).getClose();
        }
        double[] fastMa = ma(close, fast);
        double[] slowMa = ma(close, slow);

        double cash = initial;
        double shares = 0;
        int trades = 0;          // 完整买卖回合数
        int wins = 0;            // 盈利回合数
        double buyPrice = 0;
        double peak = initial;
        double maxDd = 0;

        List<Map<String, Object>> navPoints = new ArrayList<>();
        double benchFirst = close[0];

        for (int i = 0; i < n; i++) {
            // 信号用「当日收盘后比较、当日收盘价成交」的简化模型
            if (i >= slow && !Double.isNaN(fastMa[i]) && !Double.isNaN(slowMa[i])
                    && !Double.isNaN(fastMa[i - 1]) && !Double.isNaN(slowMa[i - 1])) {
                boolean crossUp = fastMa[i] > slowMa[i] && fastMa[i - 1] <= slowMa[i - 1];
                boolean crossDown = fastMa[i] < slowMa[i] && fastMa[i - 1] >= slowMa[i - 1];
                if (crossUp && shares == 0) {
                    shares = cash / close[i];
                    cash = 0;
                    buyPrice = close[i];
                } else if (crossDown && shares > 0) {
                    cash = shares * close[i];
                    shares = 0;
                    trades++;
                    if (close[i] > buyPrice) {
                        wins++;
                    }
                }
            }
            double nav = cash + shares * close[i];
            peak = Math.max(peak, nav);
            double dd = peak > 0 ? (nav / peak - 1) * 100 : 0;
            maxDd = Math.min(maxDd, dd);

            Map<String, Object> point = new HashMap<>();
            point.put("date", rows.get(i).getTradeDate());
            point.put("nav", round4(nav / initial));
            point.put("bench", round4(close[i] / benchFirst));
            point.put("dd", round4(dd));
            navPoints.add(point);
        }
        // 期末仍持仓则按最后收盘平仓计入统计
        if (shares > 0) {
            trades++;
            if (close[n - 1] > buyPrice) {
                wins++;
            }
        }

        double totalReturn = (cash + shares * close[n - 1]) / initial * 100 - 100;
        double winRate = trades > 0 ? wins * 100.0 / trades : 0;

        String params = objectMapper.writeValueAsString(new HashMap<String, Object>() {
            {
                put("fast", fast);
                put("slow", slow);
                put("initial", initial);
            }
        });
        long id = resultDao.insert(tsCode, "ma_cross", params,
                rows.get(0).getTradeDate(), rows.get(n - 1).getTradeDate(),
                round4(totalReturn), round4(maxDd), round4(winRate),
                objectMapper.writeValueAsString(navPoints));

        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("tsCode", tsCode);
        result.put("strategy", "ma_cross");
        result.put("fast", fast);
        result.put("slow", slow);
        result.put("startDate", rows.get(0).getTradeDate());
        result.put("endDate", rows.get(n - 1).getTradeDate());
        result.put("totalReturn", round4(totalReturn));
        result.put("benchmarkReturn", round4((close[n - 1] / benchFirst - 1) * 100));
        result.put("maxDrawdown", round4(maxDd));
        result.put("winRate", round4(winRate));
        result.put("trades", trades);
        result.put("nav", navPoints);
        result.put("disclaimer", "回测基于历史行情与简化成交假设（收盘价成交、未计手续费与滑点），仅供参考，不构成投资建议，不承诺任何收益。");
        return result;
    }

    /** 简单均线序列，前 n-1 位为 NaN */
    private double[] ma(double[] close, int window) {
        double[] out = new double[close.length];
        double sum = 0;
        for (int i = 0; i < close.length; i++) {
            sum += close[i];
            if (i >= window) {
                sum -= close[i - window];
            }
            out[i] = i >= window - 1 ? sum / window : Double.NaN;
        }
        return out;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
