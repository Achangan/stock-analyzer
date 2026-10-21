package com.stockanalyzer.report.controller;

import com.stockanalyzer.report.service.PipelineService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 每日流水线手动触发入口 */
@RestController
@RequestMapping("/pipeline")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    /** 手动触发：采集 → 报告 → 推送知识库（手动触发不做交易日校验） */
    @PostMapping("/run")
    public Map<String, Object> run() {
        return pipelineService.run(false);
    }
}
