package com.pageindex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pageindex.model.Config;
import com.pageindex.model.PageContent;
import com.pageindex.model.TreeNode;
import com.pageindex.parser.DocumentParser;
import com.pageindex.utils.ConfigLoader;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * PageIndex 准确率测试工具（命令行版本）
 */
public class PageIndexAccuracyTester {
    private static final ObjectMapper mapper = new ObjectMapper();
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("用法: java PageIndexAccuracyTester <文件路径> [输出目录]");
            System.out.println("示例: java PageIndexAccuracyTester D:/bbb.pdf ./test-results");
            return;
        }
        
        String filePath = args[0];
        String outputPath = args.length > 1 ? args[1] : "./test-results";
        
        try {
            System.out.println("开始测试准确率...");
            System.out.println("文档路径: " + filePath);
            
            // 加载配置
            Config config = ConfigLoader.loadDefaultConfig();
            PageIndex pageIndex = new PageIndex(config);
            
            // 解析文档
            DocumentParser parser = new DocumentParser(config.getModel());
            List<PageContent> pages = parser.parseDocument(filePath);
            System.out.println("已解析 " + pages.size() + " 页");
            
            // 构建索引
            System.out.println("正在构建索引...");
            CompletableFuture<PageIndex.IndexResult> future = pageIndex.buildIndex(filePath);
            PageIndex.IndexResult result = future.join();
            System.out.println("索引构建完成");
            
            // 测试准确率
            System.out.println("正在测试准确率...");
            AccuracyReport report = testAccuracy(result, pages);
            
            // 保存测试报告
            File outputDir = new File(outputPath);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            File reportFile = new File(outputDir, "accuracy_report.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(reportFile, report);
            
            // 打印报告
            printReport(result, report);
            
            System.out.println("\n详细报告已保存到: " + reportFile.getAbsolutePath());
            
        } catch (Exception e) {
            System.err.println("测试失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 测试准确率
     */
    private static AccuracyReport testAccuracy(PageIndex.IndexResult result, List<PageContent> pages) {
        AccuracyReport report = new AccuracyReport();
        List<AccuracyItem> items = new ArrayList<>();
        
        int[] counts = countNodes(result.getStructure(), pages, items);
        
        report.setTotalNodes(counts[0]);
        report.setNodesWithSummary(counts[1]);
        report.setSummaryAccurateCount(counts[2]);
        report.setPageRangeAccurateCount(counts[3]);
        report.setSummaryAccuracy(counts[1] > 0 ? (double) counts[2] / counts[1] : 0.0);
        report.setPageRangeAccuracy(counts[0] > 0 ? (double) counts[3] / counts[0] : 0.0);
        report.setOverallAccuracy((report.getSummaryAccuracy() + report.getPageRangeAccuracy()) / 2.0);
        report.setItems(items);
        
        return report;
    }
    
    /**
     * 统计节点信息
     * 返回 [totalNodes, nodesWithSummary, accurateSummaries, accuratePageRanges]
     */
    private static int[] countNodes(List<TreeNode> nodes, List<PageContent> pages, List<AccuracyItem> items) {
        int[] counts = new int[4];
        
        for (TreeNode node : nodes) {
            counts[0]++; // totalNodes
            
            // 检查页码范围
            boolean pageRangeAccurate = true;
            if (node.getStartIndex() != null && node.getEndIndex() != null) {
                int start = node.getStartIndex();
                int end = node.getEndIndex();
                if (start < 1 || end > pages.size() || start > end) {
                    pageRangeAccurate = false;
                }
            }
            if (pageRangeAccurate) {
                counts[3]++; // accuratePageRanges
            }
            
            // 检查摘要准确性
            if (node.getSummary() != null && !node.getSummary().isEmpty()) {
                counts[1]++; // nodesWithSummary
                
                String originalText = getNodeText(node, pages);
                if (originalText != null && !originalText.isEmpty()) {
                    boolean isAccurate = checkSummaryAccuracy(node.getSummary(), originalText);
                    if (isAccurate) {
                        counts[2]++; // accurateSummaries
                    }
                    
                    AccuracyItem item = new AccuracyItem();
                    item.setNodeId(node.getNodeId());
                    item.setTitle(node.getTitle());
                    item.setStartIndex(node.getStartIndex());
                    item.setEndIndex(node.getEndIndex());
                    item.setSummary(node.getSummary());
                    item.setOriginalTextLength(originalText.length());
                    item.setSummaryLength(node.getSummary().length());
                    item.setPageRangeAccurate(pageRangeAccurate);
                    item.setSummaryAccurate(isAccurate);
                    items.add(item);
                }
            }
            
            // 递归检查子节点
            if (node.hasChildren()) {
                int[] childCounts = countNodes(node.getNodes(), pages, items);
                counts[0] += childCounts[0];
                counts[1] += childCounts[1];
                counts[2] += childCounts[2];
                counts[3] += childCounts[3];
            }
        }
        
        return counts;
    }
    
    /**
     * 获取节点的原文内容
     */
    private static String getNodeText(TreeNode node, List<PageContent> pages) {
        if (node.getStartIndex() == null || node.getEndIndex() == null) {
            return null;
        }
        
        StringBuilder text = new StringBuilder();
        for (int i = node.getStartIndex() - 1; i < node.getEndIndex() && i < pages.size(); i++) {
            text.append(pages.get(i).getText()).append("\n");
        }
        return text.toString();
    }
    
    /**
     * 检查摘要准确性（改进方法：检查关键信息是否匹配）
     */
    private static boolean checkSummaryAccuracy(String summary, String originalText) {
        if (summary == null || originalText == null || summary.isEmpty() || originalText.isEmpty()) {
            return false;
        }
        
        // 方法1：检查摘要中的关键词是否在原文中出现
        Set<String> summaryKeywords = extractKeywords(summary);
        if (summaryKeywords.isEmpty()) {
            // 如果没有关键词，检查摘要长度是否合理（至少是原文的1%）
            return summary.length() >= originalText.length() * 0.01;
        }
        
        // 检查摘要中的关键词是否在原文中出现
        int matchedKeywords = 0;
        for (String keyword : summaryKeywords) {
            if (originalText.contains(keyword)) {
                matchedKeywords++;
            }
        }
        
        // 如果至少30%的关键词在原文中出现，认为准确
        double keywordMatchRate = summaryKeywords.size() > 0 
                ? (double) matchedKeywords / summaryKeywords.size() 
                : 0.0;
        
        // 方法2：检查摘要长度是否合理（摘要应该在原文长度的5%-50%之间）
        double lengthRatio = (double) summary.length() / originalText.length();
        boolean lengthReasonable = lengthRatio >= 0.05 && lengthRatio <= 0.5;
        
        // 方法3：检查摘要中是否包含原文的重要句子片段（至少3个字符的连续片段）
        int commonFragments = countCommonFragments(summary, originalText);
        boolean hasCommonFragments = commonFragments >= 3;
        
        // 综合判断：满足任一条件即认为准确
        return keywordMatchRate >= 0.3 || (lengthReasonable && hasCommonFragments);
    }
    
    /**
     * 统计摘要和原文中的共同片段数量
     */
    private static int countCommonFragments(String summary, String originalText) {
        int count = 0;
        // 提取摘要中的3-10字符片段
        for (int len = 3; len <= Math.min(10, summary.length()); len++) {
            for (int i = 0; i <= summary.length() - len; i++) {
                String fragment = summary.substring(i, i + len);
                if (originalText.contains(fragment)) {
                    count++;
                    break; // 每个长度只统计一次
                }
            }
        }
        return count;
    }
    
    /**
     * 提取关键词（数字、日期、专有名词等）
     */
    private static Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();
        
        // 提取数字
        Pattern numberPattern = Pattern.compile("\\d+");
        java.util.regex.Matcher matcher = numberPattern.matcher(text);
        while (matcher.find()) {
            keywords.add(matcher.group());
        }
        
        // 提取中文词汇（2-4个字符）
        Pattern chinesePattern = Pattern.compile("[\\u4e00-\\u9fa5]{2,4}");
        matcher = chinesePattern.matcher(text);
        while (matcher.find()) {
            keywords.add(matcher.group());
        }
        
        return keywords;
    }
    
    /**
     * 打印报告
     */
    private static void printReport(PageIndex.IndexResult result, AccuracyReport report) {
        System.out.println("\n========== 准确率测试报告 ==========");
        System.out.println("文档: " + result.getDocName());
        System.out.println("总节点数: " + report.getTotalNodes());
        System.out.println("有摘要的节点数: " + report.getNodesWithSummary());
        System.out.println("摘要准确节点数: " + report.getSummaryAccurateCount());
        System.out.println("页码准确节点数: " + report.getPageRangeAccurateCount());
        System.out.println("摘要准确率: " + String.format("%.2f%%", report.getSummaryAccuracy() * 100));
        System.out.println("页码准确率: " + String.format("%.2f%%", report.getPageRangeAccuracy() * 100));
        System.out.println("总体准确率: " + String.format("%.2f%%", report.getOverallAccuracy() * 100));
        
        // 显示不准确的节点
        if (!report.getItems().isEmpty()) {
            System.out.println("\n不准确的节点:");
            int count = 0;
            for (AccuracyItem item : report.getItems()) {
                if (!item.isSummaryAccurate() || !item.isPageRangeAccurate()) {
                    System.out.println("  - " + item.getTitle() + 
                            " (摘要准确: " + item.isSummaryAccurate() + 
                            ", 页码准确: " + item.isPageRangeAccurate() + ")");
                    count++;
                    if (count >= 10) {
                        System.out.println("  ... (还有更多)");
                        break;
                    }
                }
            }
        }
    }
    
    /**
     * 准确率报告
     */
    public static class AccuracyReport {
        private int totalNodes;
        private int nodesWithSummary;
        private int summaryAccurateCount;
        private int pageRangeAccurateCount;
        private double summaryAccuracy;
        private double pageRangeAccuracy;
        private double overallAccuracy;
        private List<AccuracyItem> items;
        
        // Getters and setters
        public int getTotalNodes() { return totalNodes; }
        public void setTotalNodes(int totalNodes) { this.totalNodes = totalNodes; }
        
        public int getNodesWithSummary() { return nodesWithSummary; }
        public void setNodesWithSummary(int nodesWithSummary) { this.nodesWithSummary = nodesWithSummary; }
        
        public int getSummaryAccurateCount() { return summaryAccurateCount; }
        public void setSummaryAccurateCount(int summaryAccurateCount) { this.summaryAccurateCount = summaryAccurateCount; }
        
        public int getPageRangeAccurateCount() { return pageRangeAccurateCount; }
        public void setPageRangeAccurateCount(int pageRangeAccurateCount) { this.pageRangeAccurateCount = pageRangeAccurateCount; }
        
        public double getSummaryAccuracy() { return summaryAccuracy; }
        public void setSummaryAccuracy(double summaryAccuracy) { this.summaryAccuracy = summaryAccuracy; }
        
        public double getPageRangeAccuracy() { return pageRangeAccuracy; }
        public void setPageRangeAccuracy(double pageRangeAccuracy) { this.pageRangeAccuracy = pageRangeAccuracy; }
        
        public double getOverallAccuracy() { return overallAccuracy; }
        public void setOverallAccuracy(double overallAccuracy) { this.overallAccuracy = overallAccuracy; }
        
        public List<AccuracyItem> getItems() { return items; }
        public void setItems(List<AccuracyItem> items) { this.items = items; }
    }
    
    /**
     * 准确率项
     */
    public static class AccuracyItem {
        private String nodeId;
        private String title;
        private Integer startIndex;
        private Integer endIndex;
        private String summary;
        private int originalTextLength;
        private int summaryLength;
        private boolean pageRangeAccurate;
        private boolean summaryAccurate;
        
        // Getters and setters
        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        
        public Integer getStartIndex() { return startIndex; }
        public void setStartIndex(Integer startIndex) { this.startIndex = startIndex; }
        
        public Integer getEndIndex() { return endIndex; }
        public void setEndIndex(Integer endIndex) { this.endIndex = endIndex; }
        
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        
        public int getOriginalTextLength() { return originalTextLength; }
        public void setOriginalTextLength(int originalTextLength) { this.originalTextLength = originalTextLength; }
        
        public int getSummaryLength() { return summaryLength; }
        public void setSummaryLength(int summaryLength) { this.summaryLength = summaryLength; }
        
        public boolean isPageRangeAccurate() { return pageRangeAccurate; }
        public void setPageRangeAccurate(boolean pageRangeAccurate) { this.pageRangeAccurate = pageRangeAccurate; }
        
        public boolean isSummaryAccurate() { return summaryAccurate; }
        public void setSummaryAccurate(boolean summaryAccurate) { this.summaryAccurate = summaryAccurate; }
    }
}
