package com.stockanalyzer.report.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 学习模块：读取理财知识库 markdown 源文件 */
@RestController
@RequestMapping("/learn")
public class LearnController {

    @Value("${knowledge.hub-dir}")
    private String hubDir;

    /** 目录树：guide/ 主题文章 + daily/ 日志 */
    @GetMapping("/tree")
    public List<Map<String, Object>> tree() throws IOException {
        Path root = hubRoot();
        List<Map<String, Object>> tree = new ArrayList<>();
        tree.add(group(root, "guide", "主题文章", true));
        tree.add(group(root, "daily", "每日日志", false));
        return tree;
    }

    /** 读取单篇 markdown，path 形如 guide/xxx.md，白名单限定在知识库根目录内 */
    @GetMapping("/file")
    public Map<String, String> file(@RequestParam String path) throws IOException {
        Path root = hubRoot();
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target) || !path.endsWith(".md")) {
            throw new IllegalArgumentException("非法的文件路径: " + path);
        }
        String content = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
        Map<String, String> result = new HashMap<>();
        result.put("path", path);
        result.put("title", extractTitle(content, path));
        result.put("content", content);
        return result;
    }

    private Path hubRoot() throws IOException {
        Path root = Paths.get(hubDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("知识库目录不存在: " + root);
        }
        return root;
    }

    private Map<String, Object> group(Path root, String dir, String label, boolean asc) throws IOException {
        Path sub = root.resolve(dir);
        List<Map<String, String>> children = new ArrayList<>();
        if (Files.isDirectory(sub)) {
            try (Stream<Path> s = Files.list(sub)) {
                children = s.filter(p -> p.getFileName().toString().endsWith(".md"))
                        .sorted(asc ? Comparator.comparing(p -> p.getFileName().toString())
                                : Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                        .map(p -> {
                            String rel = dir + "/" + p.getFileName().toString();
                            Map<String, String> node = new HashMap<>();
                            node.put("path", rel);
                            String title;
                            try {
                                title = extractTitle(
                                        new String(Files.readAllBytes(p), StandardCharsets.UTF_8), rel);
                            } catch (IOException e) {
                                title = p.getFileName().toString().replace(".md", "");
                            }
                            node.put("label", title);
                            return node;
                        })
                        .collect(Collectors.toList());
            }
        }
        Map<String, Object> group = new HashMap<>();
        group.put("label", label);
        group.put("children", children);
        return group;
    }

    /** 取首个一级标题作为标题，取不到用文件名 */
    private String extractTitle(String content, String path) {
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith("# ")) {
                return t.substring(2).trim();
            }
        }
        return Paths.get(path).getFileName().toString().replace(".md", "");
    }
}
