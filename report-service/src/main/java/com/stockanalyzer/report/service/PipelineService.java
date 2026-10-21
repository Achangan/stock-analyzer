package com.stockanalyzer.report.service;

import com.stockanalyzer.report.service.ReportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 每日流水线：采集 → 报告 → 推送知识库 */
@Service
public class PipelineService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PipelineService.class);
    private static final List<String> DEFAULT_INDEXES = Arrays.asList(
            "000300.SH", "000905.SH", "000001.SH", "399006.SZ", "000688.SH");

    private final JdbcTemplate jdbc;
    private final ReportService reportService;

    @Value("${knowledge.hub-dir}")
    private String hubDir;
    @Value("${pipeline.python}")
    private String python;
    @Value("${pipeline.collector-dir}")
    private String collectorDir;

    public PipelineService(JdbcTemplate jdbc, ReportService reportService) {
        this.jdbc = jdbc;
        this.reportService = reportService;
    }

    /** 交易日 16:00 自动执行 */
    @Scheduled(cron = "${pipeline.cron}")
    public void scheduled() {
        log.info("pipeline scheduled run start");
        try {
            run(true);
        } catch (Exception e) {
            log.error("pipeline scheduled run failed", e);
        }
    }

    /**
     * 执行完整流水线。
     *
     * @param tradingDayOnly 为 true 时，trade_cal 明确标记休市则跳过；无记录视为交易日继续执行
     */
    public Map<String, Object> run(boolean tradingDayOnly) {
        List<String> steps = new ArrayList<>();
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        if (tradingDayOnly && !isTradingDay(today)) {
            steps.add(today + " 非交易日，跳过");
            return result(false, steps);
        }

        // 1. Python 指数增量采集
        try {
            steps.add("指数采集: " + runCollector("run_daily.py", 10));
        } catch (Exception e) {
            steps.add("指数采集失败: " + e.getMessage());
            return result(false, steps);
        }

        // 1.2 自选股增量采集（Baostock，只跑 watchlist）
        try {
            steps.add("个股采集: " + runCollector("run_stock.py --watchlist", 15));
        } catch (Exception e) {
            // 个股采集失败不阻断报告流程，记一条继续
            steps.add("个股采集失败（跳过）: " + e.getMessage());
        }

        // 2. 生成报告
        String report;
        try {
            report = reportService.generateDailyReport(DEFAULT_INDEXES);
            steps.add("报告生成: " + report.length() + " 字符");
        } catch (Exception e) {
            steps.add("报告生成失败: " + e.getMessage());
            return result(false, steps);
        }

        // 3. 写入知识库 daily/
        try {
            Path dailyDir = Paths.get(hubDir, "daily");
            Files.createDirectories(dailyDir);
            Path target = dailyDir.resolve(today + ".md");
            Files.write(target, report.getBytes(StandardCharsets.UTF_8));
            steps.add("已推送: " + target);
        } catch (Exception e) {
            steps.add("推送失败: " + e.getMessage());
            return result(false, steps);
        }

        return result(true, steps);
    }

    /** trade_cal 有记录且 is_open=0 才判定休市；无记录视为交易日 */
    private boolean isTradingDay(String date) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT is_open FROM trade_cal WHERE cal_date = ?", Integer.class, date);
        return rows.isEmpty() || rows.get(0) == 1;
    }

    /** 调用 Python 采集脚本，超时分钟数可配；返回末行输出摘要 */
    private String runCollector(String script, int timeoutMin) throws Exception {
        String[] fullArgs;
        String[] parts = script.split(" ");
        fullArgs = new String[parts.length + 1];
        fullArgs[0] = python;
        System.arraycopy(parts, 0, fullArgs, 1, parts.length);
        ProcessBuilder pb = new ProcessBuilder(fullArgs);
        pb.directory(Paths.get(collectorDir).toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[collector] {}", line);
                out.append(line).append('\n');
            }
        }
        boolean done = proc.waitFor(timeoutMin, TimeUnit.MINUTES);
        if (!done) {
            proc.destroyForcibly();
            throw new RuntimeException("采集超时（" + timeoutMin + " 分钟）");
        }
        if (proc.exitValue() != 0) {
            throw new RuntimeException("采集进程退出码 " + proc.exitValue());
        }
        String text = out.toString().trim();
        return text.length() > 200 ? "..." + text.substring(text.length() - 200) : text;
    }

    private Map<String, Object> result(boolean ok, List<String> steps) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", ok);
        m.put("steps", steps);
        m.put("ranAt", java.time.LocalDateTime.now().toString());
        return m;
    }
}
