package com.pageindex.tree;

import com.pageindex.model.PageContent;
import com.pageindex.model.TOCItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于规则的树结构构建器
 * 使用正则表达式和规则匹配来构建树结构，不依赖LLM
 */
public class RuleBasedTreeBuilder {
    private static final Logger logger = LoggerFactory.getLogger(RuleBasedTreeBuilder.class);
    
    // 目录检测关键词
    private static final String[] TOC_KEYWORDS = {
        "目录", "目  录", "目   录", "Table of Contents", "Contents", 
        "TABLE OF CONTENTS", "CONTENTS", "目次"
    };
    
    // 章节标题模式（按优先级排序，更严格的模式在前）
    private static final Pattern[] CHAPTER_PATTERNS = {
        // 一级标题：第X章（中文）
        Pattern.compile("^第[一二三四五六七八九十\\d]+章[\\s]*([^\\n]{1,100})$", Pattern.MULTILINE),
        // 一级标题：Chapter X（英文）
        Pattern.compile("^(?i)Chapter\\s+(\\d+)[\\s]*([^\\n]{1,100})$", Pattern.MULTILINE),
        // 二级标题：第X节（中文）
        Pattern.compile("^第[一二三四五六七八九十\\d]+节[\\s]*([^\\n]{1,100})$", Pattern.MULTILINE),
        // 二级标题：Section X（英文）
        Pattern.compile("^(?i)Section\\s+(\\d+)[\\s]*([^\\n]{1,100})$", Pattern.MULTILINE),
        // 数字编号：1.、1.1、1.1.1（必须行首，且不是年份：4位数字或大于100的数字不是章节编号）
        Pattern.compile("^(\\d{1,2}(?:\\.\\d+)*)[\\s]+([^\\n]{1,100})$", Pattern.MULTILINE),
        // 中文编号：一、二、三（必须行首）
        Pattern.compile("^([一二三四五六七八九十]+)[、.][\\s]*([^\\n]{1,100})$", Pattern.MULTILINE),
        // 字母编号：A.、B.、a.、b.（必须行首）
        Pattern.compile("^([A-Za-z])[.、][\\s]*([^\\n]{1,100})$", Pattern.MULTILINE)
    };
    
    /**
     * 检测页面是否包含目录
     */
    public static boolean detectTOC(String pageText) {
        if (pageText == null || pageText.isEmpty()) {
            return false;
        }
        
        String normalizedText = pageText.replaceAll("\\s+", " ");
        
        // 检查是否包含目录关键词
        for (String keyword : TOC_KEYWORDS) {
            if (normalizedText.contains(keyword)) {
                // 进一步检查：目录通常包含多个章节项
                int chapterCount = countChapterItems(pageText);
                if (chapterCount >= 3) {
                    logger.debug("Detected TOC with keyword: {} and {} items", keyword, chapterCount);
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 统计目录项数量
     */
    private static int countChapterItems(String text) {
        int count = 0;
        for (Pattern pattern : CHAPTER_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * 检查目录是否包含页码
     */
    public static boolean hasPageNumbers(String tocText) {
        if (tocText == null || tocText.isEmpty()) {
            return false;
        }
        
        // 检查是否包含页码模式：数字在行尾
        Pattern pagePattern = Pattern.compile("\\d+\\s*$", Pattern.MULTILINE);
        Matcher matcher = pagePattern.matcher(tocText);
        int pageNumberCount = 0;
        while (matcher.find()) {
            pageNumberCount++;
        }
        
        // 如果至少有3个页码，认为目录包含页码
        return pageNumberCount >= 3;
    }
    
    /**
     * 从目录文本中提取目录项（基于规则）
     */
    public static List<TOCItem> extractTOCItemsByRule(String tocText) {
        List<TOCItem> items = new ArrayList<>();
        
        if (tocText == null || tocText.isEmpty()) {
            return items;
        }
        
        String[] lines = tocText.split("\\n");
        int[] levelCounters = new int[10]; // 支持10级嵌套
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.length() < 2) {
                continue;
            }
            
            // 跳过目录标题行
            boolean isTOCHeader = false;
            for (String keyword : TOC_KEYWORDS) {
                if (line.contains(keyword)) {
                    isTOCHeader = true;
                    break;
                }
            }
            if (isTOCHeader) {
                continue;
            }
            
            TOCItem item = parseTOCLine(line, levelCounters);
            if (item != null && item.getTitle() != null && !item.getTitle().isEmpty()) {
                items.add(item);
            }
        }
        
        logger.info("Extracted {} TOC items using rule-based method", items.size());
        return items;
    }
    
    /**
     * 解析单行目录项
     */
    private static TOCItem parseTOCLine(String line, int[] levelCounters) {
        TOCItem item = new TOCItem();
        
        // 提取页码（行尾的数字）
        Pattern pagePattern = Pattern.compile("(\\d+)\\s*$");
        Matcher pageMatcher = pagePattern.matcher(line);
        if (pageMatcher.find()) {
            try {
                int pageNum = Integer.parseInt(pageMatcher.group(1));
                item.setPhysicalIndex(pageNum);
                // 移除页码部分
                line = line.substring(0, pageMatcher.start()).trim();
            } catch (NumberFormatException e) {
                // 忽略
            }
        }
        
        // 尝试匹配各种章节模式
        for (Pattern pattern : CHAPTER_PATTERNS) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                String structure = extractStructure(matcher, pattern);
                String title = extractTitle(line, matcher, pattern);
                
                if (title != null && !title.isEmpty()) {
                    item.setStructure(structure);
                    item.setTitle(title);
                    return item;
                }
            }
        }
        
        // 如果没有匹配到标准模式，尝试提取标题（去除前导数字和符号）
        String title = line.replaceAll("^[\\d\\s\\.、]+", "").trim();
        if (title.length() > 1 && title.length() < 200) {
            item.setTitle(title);
            // 使用简单的递增编号
            item.setStructure(String.valueOf(levelCounters[0] + 1));
            levelCounters[0]++;
            return item;
        }
        
        return null;
    }
    
    /**
     * 提取结构编号
     */
    private static String extractStructure(Matcher matcher, Pattern pattern) {
        String patternStr = pattern.pattern();
        
        if (patternStr.contains("第.*章")) {
            // 中文章节：第X章 -> X（一级标题）
            String chapterNum = matcher.group(1);
            return convertChineseNumber(chapterNum);
        } else if (patternStr.contains("第.*节")) {
            // 中文小节：第X节 -> 需要结合上下文确定层级
            String sectionNum = matcher.group(1);
            return convertChineseNumber(sectionNum);
        } else if (patternStr.contains("Chapter")) {
            // Chapter X -> X（一级标题）
            return matcher.group(1);
        } else if (patternStr.contains("Section")) {
            // Section X -> 需要结合上下文确定层级
            return matcher.group(1);
        } else if (patternStr.contains("\\d{1,2}(?:\\.\\d+)*")) {
            // 数字编号：1.1.1 -> 1.1.1（保持原有层级）
            // 注意：只匹配1-2位数字开头的编号，避免匹配年份（4位数字）
            String numStr = matcher.group(1);
            if (numStr != null && !numStr.isEmpty()) {
                // 检查是否是年份（4位数字或大于100的数字）
                String[] parts = numStr.split("\\.");
                if (parts.length > 0) {
                    try {
                        int firstNum = Integer.parseInt(parts[0]);
                        // 如果是4位数字（年份）或大于100的数字，不是章节编号
                        if (firstNum >= 1000 || firstNum > 100) {
                            return null;
                        }
                        // 如果是2位数字且大于50，可能是年份的后两位，也跳过
                        if (firstNum > 50 && parts.length == 1) {
                            return null;
                        }
                    } catch (NumberFormatException e) {
                        // 忽略
                    }
                }
                return numStr;
            }
        } else if (patternStr.contains("[一二三四五六七八九十]+")) {
            // 中文编号：一、二、三 -> 1, 2, 3（二级标题）
            String chineseNum = matcher.group(1);
            return convertChineseNumber(chineseNum);
        } else if (patternStr.contains("[A-Za-z]")) {
            // 字母编号：A, B, C -> 1, 2, 3（二级标题）
            String letter = matcher.group(1);
            return String.valueOf(letter.toUpperCase().charAt(0) - 'A' + 1);
        }
        
        return null;
    }
    
    /**
     * 提取标题
     */
    private static String extractTitle(String line, Matcher matcher, Pattern pattern) {
        String patternStr = pattern.pattern();
        
        if (patternStr.contains("第.*章")) {
            // 第X章 标题 -> 标题
            return matcher.group(1).trim();
        } else if (patternStr.contains("Chapter")) {
            // Chapter X 标题 -> 标题
            if (matcher.groupCount() >= 2) {
                return matcher.group(2).trim();
            }
            return line.substring(matcher.end()).trim();
        } else if (patternStr.contains("\\d+")) {
            // 1.1.1 标题 -> 标题
            if (matcher.groupCount() >= 2) {
                return matcher.group(2).trim();
            }
            return line.substring(matcher.end()).trim();
        } else if (patternStr.contains("[一二三四五六七八九十]+")) {
            // 一、标题 -> 标题
            if (matcher.groupCount() >= 2) {
                return matcher.group(2).trim();
            }
            return line.substring(matcher.end()).trim();
        } else if (patternStr.contains("[A-Za-z]")) {
            // A. 标题 -> 标题
            if (matcher.groupCount() >= 2) {
                return matcher.group(2).trim();
            }
            return line.substring(matcher.end()).trim();
        }
        
        return line.trim();
    }
    
    /**
     * 将中文数字转换为阿拉伯数字
     */
    private static String convertChineseNumber(String chineseNum) {
        if (chineseNum == null || chineseNum.isEmpty()) {
            return "1";
        }
        
        // 简单映射
        java.util.Map<String, String> numberMap = new java.util.HashMap<>();
        numberMap.put("一", "1");
        numberMap.put("二", "2");
        numberMap.put("三", "3");
        numberMap.put("四", "4");
        numberMap.put("五", "5");
        numberMap.put("六", "6");
        numberMap.put("七", "7");
        numberMap.put("八", "8");
        numberMap.put("九", "9");
        numberMap.put("十", "10");
        
        // 如果是纯数字，直接返回
        if (chineseNum.matches("\\d+")) {
            return chineseNum;
        }
        
        // 尝试转换
        String result = numberMap.get(chineseNum);
        return result != null ? result : "1";
    }
    
    /**
     * 从文档中提取章节结构（无目录时使用）
     */
    public static List<TOCItem> extractChaptersFromDocument(List<PageContent> pages) {
        List<TOCItem> items = new ArrayList<>();
        int[] levelCounters = new int[10]; // 支持10级嵌套
        
        // 优先使用Word文档的标题样式信息
        boolean hasWordHeadings = false;
        for (PageContent page : pages) {
            if (page.hasHeadings() && !page.getHeadings().isEmpty()) {
                hasWordHeadings = true;
                break;
            }
        }
        
        if (hasWordHeadings) {
            // 使用Word样式信息提取标题
            logger.info("Using Word heading styles to extract chapters");
            for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
                PageContent page = pages.get(pageIndex);
                if (page.hasHeadings()) {
                    for (PageContent.HeadingInfo heading : page.getHeadings()) {
                        TOCItem item = new TOCItem();
                        int level = heading.getLevel();
                        
                        // 根据层级更新计数器并构建结构编号
                        // level 是从1开始的（1=一级标题，2=二级标题），需要转换为从0开始的索引
                        int levelIndex = level - 1; // 转换为从0开始的索引
                        
                        // 更新对应层级的计数器
                        for (int i = levelIndex; i < levelCounters.length; i++) {
                            if (i == levelIndex) {
                                levelCounters[i]++;
                            } else {
                                levelCounters[i] = 0; // 重置下级计数器
                            }
                        }
                        
                        // 构建结构编号（如 1, 1.1, 1.1.1）
                        // 只包含到当前层级的所有计数器
                        StringBuilder structure = new StringBuilder();
                        for (int i = 0; i <= levelIndex && i < levelCounters.length; i++) {
                            if (i > 0) {
                                structure.append(".");
                            }
                            structure.append(levelCounters[i]);
                        }
                        
                        item.setStructure(structure.toString());
                        item.setTitle(heading.getText());
                        item.setPhysicalIndex(pageIndex + 1);
                        items.add(item);
                        logger.info("Found heading at page {}: '{}' (level: {}, structure: {})", 
                                pageIndex + 1, heading.getText(), level, structure.toString());
                    }
                }
            }
        }
        
        // 如果没有Word标题样式，使用规则匹配
        if (items.isEmpty()) {
            logger.info("No Word headings found, using rule-based pattern matching");
            for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
                PageContent page = pages.get(pageIndex);
                String pageText = page.getText();
                
                if (pageText == null || pageText.isEmpty()) {
                    continue;
                }
                
                // 检查页面中是否有章节标题
                // 优先检查页面开头的几行（标题通常在页面顶部）
                String[] lines = pageText.split("\\n");
                int linesToCheck = Math.min(10, lines.length); // 只检查前10行
                
                for (int lineIndex = 0; lineIndex < linesToCheck; lineIndex++) {
                    String line = lines[lineIndex].trim();
                    if (line.isEmpty() || line.length() < 2) {
                        continue;
                    }
                    
                    // 跳过明显不是标题的行（包含太多标点符号、数字等）
                    if (isNotTitle(line)) {
                        continue;
                    }
                    
                    // 标题通常较短，且不在页面中间
                    if (line.length() > 100) {
                        continue;
                    }
                    
                    // 尝试匹配章节标题
                    TOCItem matchedItem = null;
                    int matchedPatternIndex = -1;
                    
                    for (int patternIndex = 0; patternIndex < CHAPTER_PATTERNS.length; patternIndex++) {
                        Pattern pattern = CHAPTER_PATTERNS[patternIndex];
                        Matcher matcher = pattern.matcher(line);
                        if (matcher.find()) {
                            String title = extractTitle(line, matcher, pattern);
                            
                            if (title != null && !title.isEmpty() && title.length() < 200 && title.length() > 1) {
                                // 检查标题是否合理（不应该是纯数字、纯符号等）
                                if (isValidTitle(title)) {
                                    // 额外检查：标题不应该以标点符号开头（除非是"第X章"这样的格式）
                                    String trimmedTitle = title.trim();
                                    if (trimmedTitle.length() > 0 && 
                                        !Character.isLetterOrDigit(trimmedTitle.charAt(0)) && 
                                        !trimmedTitle.startsWith("第") && 
                                        !trimmedTitle.matches("^[一二三四五六七八九十]+")) {
                                        // 跳过以标点符号开头的标题（可能是文本片段）
                                        continue;
                                    }
                                    
                                    matchedItem = new TOCItem();
                                    String structure = extractStructureWithLevel(matcher, pattern, patternIndex, levelCounters);
                                    
                                    // 确保 structure 是有效的层级编号（如 "1", "1.1", "1.2" 等）
                                    if (structure == null || structure.isEmpty() || 
                                        structure.matches("^\\d{3,}$") || // 跳过3位以上的数字（可能是年份）
                                        Integer.parseInt(structure.split("\\.")[0]) > 100) { // 跳过大于100的数字
                                        // 如果 structure 无效，使用层级计数器生成
                                        int level = getLevelFromPatternIndex(patternIndex);
                                        structure = buildStructureFromLevel(level, levelCounters);
                                    }
                                    
                                    matchedItem.setStructure(structure);
                                    matchedItem.setTitle(title);
                                    matchedItem.setPhysicalIndex(pageIndex + 1);
                                    matchedPatternIndex = patternIndex;
                                    break; // 找到匹配就退出
                                }
                            }
                        }
                    }
                    
                    if (matchedItem != null) {
                        items.add(matchedItem);
                        logger.info("Found chapter at page {}: '{}' (structure: {}, pattern: {})", 
                                pageIndex + 1, matchedItem.getTitle(), matchedItem.getStructure(), matchedPatternIndex);
                    }
                }
            }
        }
        
        // 如果没有找到章节，按页面分组创建基本结构
        if (items.isEmpty()) {
            logger.info("No chapters found, creating page-based structure");
            int pagesPerSection = Math.max(1, pages.size() / 10);
            for (int i = 0; i < pages.size(); i += pagesPerSection) {
                TOCItem item = new TOCItem();
                item.setStructure(String.valueOf(items.size() + 1));
                item.setTitle("章节 " + (items.size() + 1));
                item.setPhysicalIndex(i + 1);
                items.add(item);
            }
        } else {
            // 构建层级结构：根据structure编号建立父子关系
            items = buildHierarchy(items);
        }
        
        logger.info("Extracted {} chapters from document using rule-based method", items.size());
        return items;
    }
    
    /**
     * 判断一行是否明显不是标题
     */
    private static boolean isNotTitle(String line) {
        // 如果行太长，可能不是标题
        if (line.length() > 100) {
            return true;
        }
        
        // 如果包含太多数字和符号，可能不是标题
        int digitCount = 0;
        int punctCount = 0;
        for (char c : line.toCharArray()) {
            if (Character.isDigit(c)) {
                digitCount++;
            } else if (!Character.isLetterOrDigit(c) && !Character.isWhitespace(c) && c < 0x4e00) {
                punctCount++;
            }
        }
        
        // 如果数字和符号占比超过50%，可能不是标题
        if (line.length() > 0 && (digitCount + punctCount) * 100.0 / line.length() > 50) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 判断标题是否有效
     */
    private static boolean isValidTitle(String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        
        // 去除空格后检查
        String trimmed = title.trim();
        if (trimmed.length() < 2) {
            return false;
        }
        
        // 不能是纯数字
        if (trimmed.matches("^\\d+$")) {
            return false;
        }
        
        // 不能是纯符号
        if (trimmed.matches("^[\\s\\p{Punct}]+$")) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 提取结构编号并更新层级计数器
     */
    private static String extractStructureWithLevel(Matcher matcher, Pattern pattern, int patternIndex, int[] levelCounters) {
        String patternStr = pattern.pattern();
        String baseStructure = extractStructure(matcher, pattern);
        
        // 确定层级：根据模式索引
        int level = getLevelFromPatternIndex(patternIndex);
        
        // 如果提取到了结构（如"1.2.3"），使用其层级
        if (baseStructure != null && !baseStructure.isEmpty()) {
            level = getLevelFromStructure(baseStructure);
            // 解析baseStructure中的数字，更新计数器
            String[] parts = baseStructure.split("\\.");
            for (int i = 0; i < parts.length && i < levelCounters.length; i++) {
                try {
                    levelCounters[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    // 忽略
                }
            }
            return baseStructure;
        }
        
        // 如果没有提取到结构，根据模式索引确定层级并更新计数器
        // 更新对应层级的计数器
        for (int i = level; i < levelCounters.length; i++) {
            if (i == level) {
                levelCounters[i]++;
            } else {
                levelCounters[i] = 0; // 重置下级计数器
            }
        }
        
        return buildStructureFromLevel(level, levelCounters);
    }
    
    /**
     * 根据模式索引确定层级（0=一级，1=二级，以此类推）
     */
    private static int getLevelFromPatternIndex(int patternIndex) {
        // Pattern 0: 第X章 -> 一级标题 (level 0)
        // Pattern 1: Chapter X -> 一级标题 (level 0)
        // Pattern 2: 第X节 -> 二级标题 (level 1)
        // Pattern 3: Section X -> 二级标题 (level 1)
        // Pattern 4: 数字编号（1.1.1）-> 根据点的数量确定层级
        // Pattern 5: 中文编号（一、二、三）-> 二级标题 (level 1)
        // Pattern 6: 字母编号（A.、B.）-> 二级标题 (level 1)
        
        if (patternIndex == 0 || patternIndex == 1) {
            return 0; // 一级标题
        } else if (patternIndex == 2 || patternIndex == 3 || patternIndex == 5 || patternIndex == 6) {
            return 1; // 二级标题
        } else if (patternIndex == 4) {
            // 数字编号模式，需要根据实际匹配的内容确定层级
            // 这里返回1作为默认值，实际层级会在extractStructure中确定
            return 1;
        }
        return patternIndex - 1;
    }
    
    /**
     * 从结构编号获取层级（1=一级，1.1=二级，1.1.1=三级）
     */
    private static int getLevelFromStructure(String structure) {
        if (structure == null || structure.isEmpty()) {
            return 0;
        }
        return structure.split("\\.").length - 1;
    }
    
    /**
     * 根据层级和计数器构建结构编号
     */
    private static String buildStructureFromLevel(int level, int[] levelCounters) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= level; i++) {
            if (i > 0) {
                sb.append(".");
            }
            sb.append(levelCounters[i]);
        }
        return sb.toString();
    }
    
    /**
     * 构建层级结构：根据structure编号建立父子关系
     */
    private static List<TOCItem> buildHierarchy(List<TOCItem> items) {
        // 按页码和层级排序
        items.sort((a, b) -> {
            int pageCompare = Integer.compare(
                a.getPhysicalIndex() != null ? a.getPhysicalIndex() : 0,
                b.getPhysicalIndex() != null ? b.getPhysicalIndex() : 0
            );
            if (pageCompare != 0) {
                return pageCompare;
            }
            // 同一页内，按层级排序
            int levelA = getLevelFromStructure(a.getStructure());
            int levelB = getLevelFromStructure(b.getStructure());
            return Integer.compare(levelA, levelB);
        });
        
        return items;
    }
}
