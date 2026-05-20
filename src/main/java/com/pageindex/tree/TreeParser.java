package com.pageindex.tree;

import com.fasterxml.jackson.databind.JsonNode;
import com.pageindex.llm.LLMClient;
import com.pageindex.model.*;
import com.pageindex.parser.PDFParser;
import com.pageindex.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 树解析器，从 PDF 构建树结构索引
 */
public class TreeParser {
    private static final Logger logger = LoggerFactory.getLogger(TreeParser.class);
    
    private final Config config;
    private final LLMClient llmClient;
    private final PDFParser pdfParser;
    
    public TreeParser(Config config, LLMClient llmClient) {
        this.config = config;
        this.llmClient = llmClient;
        this.pdfParser = new PDFParser(config.getModel());
    }
    
    /**
     * 从 PDF 文件构建树结构
     */
    public CompletableFuture<List<TreeNode>> parseTree(String pdfPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<PageContent> pages = pdfParser.parsePDF(pdfPath);
                return parseTreeFromPages(pages).join();
            } catch (Exception e) {
                logger.error("Failed to parse tree from PDF: " + pdfPath, e);
                return new ArrayList<>();
            }
        });
    }
    
    /**
     * 从页面列表构建树结构
     */
    public CompletableFuture<List<TreeNode>> parseTreeFromPages(List<PageContent> pages) {
        return parseTreeFromPages(pages, null);
    }
    
    /**
     * 从页面列表构建树结构（支持 PDF 书签）
     */
    public CompletableFuture<List<TreeNode>> parseTreeFromPages(List<PageContent> pages, String pdfPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 优先尝试从 PDF 书签提取目录结构
                List<TOCItem> pdfBookmarkItems = null;
                if (pdfPath != null && pdfPath.toLowerCase().endsWith(".pdf")) {
                    try {
                        com.pageindex.parser.DocumentParser docParser = new com.pageindex.parser.DocumentParser(
                            config.getModel(), config.getMaxPageNumEachNode(), config.getMaxTokenNumEachNode());
                        pdfBookmarkItems = docParser.extractPDFBookmarks(pdfPath);
                        if (pdfBookmarkItems != null && !pdfBookmarkItems.isEmpty()) {
                            logger.info("Extracted {} items from PDF bookmarks/outlines", pdfBookmarkItems.size());
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to extract PDF bookmarks: {}", e.getMessage());
                    }
                }
                
                // 如果从 PDF 书签提取到了目录，优先使用
                if (pdfBookmarkItems != null && !pdfBookmarkItems.isEmpty()) {
                    // 转换为树结构（嵌套结构）
                    List<TreeNode> tree = TreeBuilder.listToTree(pdfBookmarkItems);
                    
                    // 更新页码范围：根据子节点更新父节点的页码范围
                    TreeBuilder.updatePageRanges(tree, pages.size());
                    
                    // 添加节点ID
                    if ("yes".equals(config.getIfAddNodeId())) {
                        TreeBuilder.assignNodeIds(tree);
                    }
                    
                    return tree;
                }
                
                // 如果没有 PDF 书签，使用原有的目录检测逻辑
                // 检查是否有目录
                TOCResult tocResult = checkTOC(pages);
                
                List<TOCItem> tocItems;
                if (tocResult.hasTOC() && tocResult.hasPageNumbers()) {
                    // 有目录且包含页码
                    tocItems = processTOCWithPageNumbers(tocResult, pages);
                    // 检查是否覆盖了所有页面，如果没有，继续处理剩余部分
                    if (!coversAllPages(tocItems, pages.size())) {
                        logger.info("TOC does not cover all pages, processing remaining content");
                        List<TOCItem> remainingItems = processRemainingPages(tocItems, pages);
                        tocItems.addAll(remainingItems);
                    }
                } else if (tocResult.hasTOC()) {
                    // 有目录但没有页码
                    List<TOCItem> extractedItems = extractTOCItems(tocResult.getTocContent());
                    if (extractedItems.isEmpty() || extractedItems.size() < pages.size() / 20) {
                        // 如果目录项太少，回退到处理整个文档
                        logger.info("TOC items too few ({}), processing entire document", extractedItems.size());
                        tocItems = processNoTOC(pages);
                    } else {
                        // 使用目录结构，但需要处理整个文档来找到章节位置
                        tocItems = processTOCWithoutPageNumbers(tocResult, pages);
                        // 检查是否覆盖了所有页面
                        if (!coversAllPages(tocItems, pages.size())) {
                            logger.info("TOC does not cover all pages, processing remaining content");
                            List<TOCItem> remainingItems = processRemainingPages(tocItems, pages);
                            tocItems.addAll(remainingItems);
                        }
                    }
                } else {
                    // 没有目录，需要生成
                    tocItems = processNoTOC(pages);
                }
                
                // 转换为树结构（嵌套结构）
                List<TreeNode> tree = TreeBuilder.listToTree(tocItems);
                
                // 更新页码范围：根据子节点更新父节点的页码范围
                TreeBuilder.updatePageRanges(tree, pages.size());
                
                // 添加节点ID
                if ("yes".equals(config.getIfAddNodeId())) {
                    TreeBuilder.assignNodeIds(tree);
                }
                
                return tree;
            } catch (Exception e) {
                logger.error("Failed to parse tree from pages", e);
                return new ArrayList<>();
            }
        });
    }
    
    /**
     * 检查是否有目录
     */
    private TOCResult checkTOC(List<PageContent> pages) {
        int checkPages = Math.min(config.getTocCheckPageNum(), pages.size());
        
        // 优先使用基于规则的方法
        if ("yes".equals(config.getUseRuleBasedTree())) {
            for (int i = 0; i < checkPages; i++) {
                String pageText = pages.get(i).getText();
                if (RuleBasedTreeBuilder.detectTOC(pageText)) {
                    boolean hasPageNumbers = RuleBasedTreeBuilder.hasPageNumbers(pageText);
                    logger.info("Detected TOC at page {} using rule-based method (hasPageNumbers: {})", i + 1, hasPageNumbers);
                    return new TOCResult(true, hasPageNumbers, i, pageText);
                }
            }
            return new TOCResult(false, false, -1, null);
        }
        
        // 使用LLM方法（原有逻辑）
        for (int i = 0; i < checkPages; i++) {
            String pageText = pages.get(i).getText();
            String prompt = buildTOCDetectionPrompt(pageText);
            String response = llmClient.call(config.getModel(), prompt, null);
            
            JsonNode json = JsonUtils.extractJson(response);
            if (json != null && "yes".equals(json.get("toc_detected").asText())) {
                // 找到目录，检查是否包含页码
                boolean hasPageNumbers = checkPageNumbersInTOC(pageText);
                return new TOCResult(true, hasPageNumbers, i, pageText);
            }
        }
        
        return new TOCResult(false, false, -1, null);
    }
    
    private String buildTOCDetectionPrompt(String content) {
        return String.format("""
            Your job is to detect if there is a table of content provided in the given text.
            
            Given text: %s
            
            return the following JSON format:
            {
                "thinking": "why do you think there is a table of content in the given text",
                "toc_detected": "yes or no"
            }
            
            Directly return the final JSON structure. Do not output anything else.
            Please note: abstract, summary, notation list, figure list, table list, etc. are not table of contents.
            """, content);
    }
    
    private boolean checkPageNumbersInTOC(String tocContent) {
        String prompt = String.format("""
            You will be given a table of contents.
            Your job is to detect if there are page numbers/indices given within the table of contents.
            
            Given text: %s
            
            Reply format:
            {
                "thinking": "why do you think there are page numbers/indices given within the table of contents",
                "page_index_given_in_toc": "yes or no"
            }
            
            Directly return the final JSON structure. Do not output anything else.
            """, tocContent);
        
        String response = llmClient.call(config.getModel(), prompt, null);
        JsonNode json = JsonUtils.extractJson(response);
        return json != null && "yes".equals(json.get("page_index_given_in_toc").asText());
    }
    
    /**
     * 处理有目录且包含页码的情况
     */
    private List<TOCItem> processTOCWithPageNumbers(TOCResult tocResult, List<PageContent> pages) {
        // 提取目录项
        List<TOCItem> tocItems;
        if ("yes".equals(config.getUseRuleBasedTree())) {
            // 使用基于规则的方法
            tocItems = RuleBasedTreeBuilder.extractTOCItemsByRule(tocResult.getTocContent());
        } else {
            // 使用LLM方法
            tocItems = extractTOCItems(tocResult.getTocContent());
        }
        
        if (tocItems.isEmpty()) {
            logger.warn("No TOC items extracted, processing entire document");
            return processNoTOC(pages);
        }
        
        // 检查是否有有效的页码
        boolean hasValidPageNumbers = false;
        for (TOCItem item : tocItems) {
            if (item.getPhysicalIndex() != null && item.getPhysicalIndex() > 0 && item.getPhysicalIndex() <= pages.size()) {
                hasValidPageNumbers = true;
                break;
            }
        }
        
        // 如果没有有效页码，无法确定目录是否完整，应该处理整个文档以确保生成完整的章节结构
        if (!hasValidPageNumbers) {
            logger.info("TOC items have no valid page numbers, cannot determine if TOC is complete. Processing entire document to ensure all chapters are included.");
            return processNoTOC(pages);
        }
        
        // 为没有页码的目录项设置页码范围
        for (int i = 0; i < tocItems.size(); i++) {
            TOCItem item = tocItems.get(i);
            if (item.getPhysicalIndex() == null || item.getPhysicalIndex() <= 0) {
                // 如果没有页码，使用下一个目录项的页码-1，或文档末尾
                int nextPage = (i + 1 < tocItems.size() && tocItems.get(i + 1).getPhysicalIndex() != null 
                        && tocItems.get(i + 1).getPhysicalIndex() > 0)
                        ? tocItems.get(i + 1).getPhysicalIndex() - 1
                        : pages.size();
                item.setPhysicalIndex(Math.max(1, Math.min(nextPage, pages.size())));
            }
        }
        
        // 检查目录项是否覆盖了所有页面
        // 如果目录项太少（少于总页数的1/10），或者最后一个目录项的页码远小于文档总页数，则继续处理剩余部分
        int lastPage = 0;
        for (TOCItem item : tocItems) {
            if (item.getPhysicalIndex() != null && item.getPhysicalIndex() > lastPage) {
                lastPage = item.getPhysicalIndex();
            }
        }
        
        // 如果目录项数量太少（少于总页数的1/10），或者最后一个目录项的起始页码小于文档的70%，继续处理剩余部分
        // 注意：即使最后一个目录项的页码接近文档末尾，如果目录项数量太少，也可能只覆盖了部分章节
        boolean shouldProcessRemaining = tocItems.size() < pages.size() / 10;
        
        if (!shouldProcessRemaining && lastPage > 0) {
            // 计算平均每个目录项覆盖的页数
            double avgPagesPerItem = (double) lastPage / tocItems.size();
            // 如果平均每个目录项覆盖的页数太多（>15页），可能目录项不完整，需要继续处理
            shouldProcessRemaining = avgPagesPerItem > 15;
        }
        
        if (!shouldProcessRemaining && lastPage < pages.size() * 0.7) {
            shouldProcessRemaining = true;
        }
        
        if (shouldProcessRemaining) {
            logger.info("TOC items ({}) may not cover all chapters (last page: {}, total: {}, avg pages/item: {}), processing remaining content", 
                    tocItems.size(), lastPage, pages.size(), 
                    lastPage > 0 ? (double) lastPage / tocItems.size() : 0);
            List<TOCItem> remainingItems = processRemainingPages(tocItems, pages);
            if (!remainingItems.isEmpty()) {
                tocItems.addAll(remainingItems);
                logger.info("Added {} additional items from remaining content", remainingItems.size());
            }
        }
        
        return tocItems;
    }
    
    /**
     * 处理有目录但没有页码的情况
     * 需要扫描整个文档来找到每个章节的实际位置
     */
    private List<TOCItem> processTOCWithoutPageNumbers(TOCResult tocResult, List<PageContent> pages) {
        // 提取目录项
        List<TOCItem> items;
        if ("yes".equals(config.getUseRuleBasedTree())) {
            // 使用基于规则的方法
            items = RuleBasedTreeBuilder.extractTOCItemsByRule(tocResult.getTocContent());
        } else {
            // 使用LLM方法
            items = extractTOCItems(tocResult.getTocContent());
        }
        
        if (items.isEmpty()) {
            logger.warn("No TOC items extracted, processing entire document");
            return processNoTOC(pages);
        }
        
        logger.info("TOC without page numbers, scanning document to find chapter positions");
        
        // 扫描文档，为每个目录项找到对应的页码
        // 从目录页之后开始扫描
        int startScanPage = tocResult.getTocPageIndex() + 1;
        
        for (TOCItem item : items) {
            if (item.getPhysicalIndex() == null || item.getPhysicalIndex() <= 0) {
                // 在文档中搜索章节标题
                int foundPage = findChapterInDocument(item.getTitle(), pages, startScanPage);
                if (foundPage > 0) {
                    item.setPhysicalIndex(foundPage);
                    startScanPage = foundPage; // 从找到的位置继续搜索下一个章节
                } else {
                    // 如果找不到，使用估算位置
                    int estimatedPage = Math.min(startScanPage, pages.size());
                    item.setPhysicalIndex(estimatedPage);
                }
            }
        }
        
        return items;
    }
    
    /**
     * 在文档中查找章节标题对应的页码
     */
    private int findChapterInDocument(String title, List<PageContent> pages, int startPage) {
        if (title == null || title.isEmpty()) {
            return -1;
        }
        
        // 提取标题的关键词（去除标点符号和空格）
        String normalizedTitle = title.replaceAll("[\\s\\p{Punct}]+", "");
        if (normalizedTitle.length() < 2) {
            return -1;
        }
        
        // 从起始页开始搜索
        for (int i = Math.max(0, startPage - 1); i < pages.size(); i++) {
            String pageText = pages.get(i).getText();
            if (pageText == null || pageText.isEmpty()) {
                continue;
            }
            
            // 检查页面中是否包含标题的关键部分
            // 尝试匹配标题的前几个字符（至少3个字符）
            int matchLength = Math.min(5, normalizedTitle.length());
            String titlePrefix = normalizedTitle.substring(0, matchLength);
            String normalizedPageText = pageText.replaceAll("[\\s\\p{Punct}]+", "");
            
            if (normalizedPageText.contains(titlePrefix)) {
                // 找到匹配，返回页码（1-based）
                return i + 1;
            }
        }
        
        return -1;
    }
    
    /**
     * 检查TOC项是否覆盖了所有页面
     */
    private boolean coversAllPages(List<TOCItem> tocItems, int totalPages) {
        if (tocItems.isEmpty()) {
            return false;
        }
        
        // 找到最后一个有页码的项
        int lastPage = 0;
        for (TOCItem item : tocItems) {
            if (item.getPhysicalIndex() != null && item.getPhysicalIndex() > lastPage) {
                lastPage = item.getPhysicalIndex();
            }
        }
        
        // 如果最后一个章节的起始页码接近文档末尾（至少覆盖80%），认为覆盖完整
        return lastPage >= totalPages * 0.8;
    }
    
    /**
     * 处理剩余未覆盖的页面
     */
    private List<TOCItem> processRemainingPages(List<TOCItem> existingItems, List<PageContent> pages) {
        // 找到最后一个章节的结束页码
        int lastCoveredPage = 0;
        for (TOCItem item : existingItems) {
            if (item.getPhysicalIndex() != null && item.getPhysicalIndex() > lastCoveredPage) {
                lastCoveredPage = item.getPhysicalIndex();
            }
        }
        
        // 如果还有未覆盖的页面，处理剩余部分
        if (lastCoveredPage < pages.size()) {
            List<PageContent> remainingPages = pages.subList(lastCoveredPage, pages.size());
            List<TOCItem> remainingItems = processNoTOC(remainingPages);
            
            // 调整剩余项的页码（加上已覆盖的页码偏移）
            for (TOCItem item : remainingItems) {
                if (item.getPhysicalIndex() != null) {
                    item.setPhysicalIndex(item.getPhysicalIndex() + lastCoveredPage);
                }
            }
            
            return remainingItems;
        }
        
        return new ArrayList<>();
    }
    
    /**
     * 处理没有目录的情况，生成树结构
     */
    private List<TOCItem> processNoTOC(List<PageContent> pages) {
        // 如果使用基于规则的方法，直接从文档提取章节
        if ("yes".equals(config.getUseRuleBasedTree())) {
            List<TOCItem> items = RuleBasedTreeBuilder.extractChaptersFromDocument(pages);
            if (!items.isEmpty()) {
                logger.info("Generated {} TOC items using rule-based method", items.size());
                return items;
            }
            // 如果规则方法失败，继续使用原有逻辑
        }
        
        List<TOCItem> tocItems = new ArrayList<>();
        
        // 将页面分组（每组的 token 数不超过 max_token_num_each_node）
        List<List<PageContent>> pageGroups = groupPages(pages);
        
        // 计算每个组在整个文档中的起始页码
        int currentPage = 1;
        List<Integer> groupStartPages = new ArrayList<>();
        for (List<PageContent> group : pageGroups) {
            groupStartPages.add(currentPage);
            currentPage += group.size();
        }
        
        // 为每组生成树结构
        int successCount = 0;
        for (int i = 0; i < pageGroups.size(); i++) {
            List<PageContent> group = pageGroups.get(i);
            int groupStartPage = groupStartPages.get(i);
            String groupText = group.stream()
                    .map(PageContent::getText)
                    .collect(Collectors.joining("\n"));
            
            List<TOCItem> generatedItems;
            if (i == 0) {
                // 第一组：生成初始树结构
                generatedItems = generateTOCInit(groupText);
            } else {
                // 后续组：继续生成树结构
                generatedItems = generateTOCContinue(tocItems, groupText);
            }
            
            if (generatedItems != null && !generatedItems.isEmpty()) {
                // 为生成的项设置正确的页码
                // 如果LLM没有提供页码，根据项在组中的位置分配页码
                int groupEndPage = groupStartPage + group.size() - 1;
                int itemsWithPage = 0;
                int itemsWithoutPage = 0;
                
                // 先统计有多少项有页码，多少项没有
                for (TOCItem item : generatedItems) {
                    if (item.getPhysicalIndex() != null && item.getPhysicalIndex() > 0 
                            && item.getPhysicalIndex() >= groupStartPage && item.getPhysicalIndex() <= groupEndPage) {
                        itemsWithPage++;
                    } else {
                        itemsWithoutPage++;
                    }
                }
                
                // 为没有页码的项分配页码
                if (itemsWithoutPage > 0) {
                    // 将组内的页面均匀分配给没有页码的项
                    int pagesPerItem = Math.max(1, group.size() / Math.max(1, generatedItems.size()));
                    int itemIndex = 0;
                    for (TOCItem item : generatedItems) {
                        if (item.getPhysicalIndex() == null || item.getPhysicalIndex() <= 0 
                                || item.getPhysicalIndex() < groupStartPage || item.getPhysicalIndex() > groupEndPage) {
                            // 计算该项应该的页码：基于组内位置
                            int assignedPage = groupStartPage + (itemIndex * pagesPerItem);
                            item.setPhysicalIndex(Math.min(assignedPage, groupEndPage));
                        }
                        itemIndex++;
                    }
                }
                
                tocItems.addAll(generatedItems);
                successCount++;
            } else {
                logger.warn("Failed to generate TOC items for group {}, creating fallback structure", i);
                // 如果LLM失败，创建基本的回退结构
                TOCItem fallbackItem = createFallbackItem(group, i + 1, groupStartPage);
                if (fallbackItem != null) {
                    tocItems.add(fallbackItem);
                    successCount++;
                }
            }
        }
        
        // 确保所有页面组都有对应的TOC项
        // 如果某些组失败了，至少为它们创建基本的回退项
        if (tocItems.size() < pageGroups.size()) {
            logger.warn("Some groups failed to generate TOC items. Expected {} items, got {}. Creating fallback items for missing groups.", 
                    pageGroups.size(), tocItems.size());
            
            // 为缺失的组创建回退项
            for (int i = 0; i < pageGroups.size(); i++) {
                // 检查是否已经有这个组的项（通过页码判断）
                int groupStartPage = groupStartPages.get(i);
                boolean hasItemForGroup = false;
                for (TOCItem item : tocItems) {
                    if (item.getPhysicalIndex() != null && item.getPhysicalIndex() == groupStartPage) {
                        hasItemForGroup = true;
                        break;
                    }
                }
                
                if (!hasItemForGroup) {
                    List<PageContent> group = pageGroups.get(i);
                    TOCItem fallbackItem = createFallbackItem(group, i + 1, groupStartPage);
                    if (fallbackItem != null) {
                        tocItems.add(fallbackItem);
                    }
                }
            }
        }
        
        // 如果所有组都失败了，至少创建一个覆盖整个文档的基本结构
        if (tocItems.isEmpty() && !pages.isEmpty()) {
            logger.warn("All TOC generation failed, creating basic structure covering entire document");
            TOCItem basicItem = new TOCItem();
            basicItem.setStructure("1");
            basicItem.setTitle("文档内容");
            basicItem.setPhysicalIndex(1);
            tocItems.add(basicItem);
        }
        
        logger.info("Generated {} TOC items from {} groups ({} successful)", tocItems.size(), pageGroups.size(), successCount);
        return tocItems;
    }
    
    /**
     * 创建回退的TOC项（当LLM失败时使用）
     */
    private TOCItem createFallbackItem(List<PageContent> group, int index, int startPage) {
        if (group.isEmpty()) {
            return null;
        }
        
        TOCItem item = new TOCItem();
        item.setStructure(String.valueOf(index));
        
        // 尝试从第一页提取标题（查找可能的章节标题）
        String firstPageText = group.get(0).getText();
        String title = extractPossibleTitle(firstPageText);
        if (title == null || title.isEmpty()) {
            title = "章节 " + index;
        }
        item.setTitle(title);
        
        // 设置起始页码
        item.setPhysicalIndex(startPage);
        
        return item;
    }
    
    /**
     * 从页面文本中提取可能的标题
     */
    private String extractPossibleTitle(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        // 查找可能的章节标题模式（如 "第X章"、"Chapter X"、"X." 等）
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "(?:第[一二三四五六七八九十\\d]+章|Chapter\\s+\\d+|\\d+\\.\\s+[^\\n]{2,50})",
            java.util.regex.Pattern.MULTILINE
        );
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String match = matcher.group();
            // 提取标题部分（去除编号）
            String title = match.replaceAll("^(?:第[一二三四五六七八九十\\d]+章|Chapter\\s+\\d+|\\d+\\.)\\s*", "");
            if (title.length() > 1 && title.length() < 100) {
                return title.trim();
            }
        }
        
        // 如果没有找到标准格式，尝试提取第一行的非空内容
        String[] lines = text.split("\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.length() > 2 && line.length() < 100 && !line.matches("^\\s*\\d+\\s*$")) {
                return line;
            }
        }
        
        return null;
    }
    
    private List<List<PageContent>> groupPages(List<PageContent> pages) {
        List<List<PageContent>> groups = new ArrayList<>();
        List<PageContent> currentGroup = new ArrayList<>();
        int currentTokens = 0;
        int currentPages = 0;
        
        for (PageContent page : pages) {
            // 检查是否超过token限制或页面限制
            boolean exceedsTokenLimit = currentTokens + page.getTokenCount() > config.getMaxTokenNumEachNode();
            boolean exceedsPageLimit = currentPages >= config.getMaxPageNumEachNode();
            
            if ((exceedsTokenLimit || exceedsPageLimit) && !currentGroup.isEmpty()) {
                groups.add(new ArrayList<>(currentGroup));
                currentGroup.clear();
                currentTokens = 0;
                currentPages = 0;
            }
            currentGroup.add(page);
            currentTokens += page.getTokenCount();
            currentPages++;
        }
        
        if (!currentGroup.isEmpty()) {
            groups.add(currentGroup);
        }
        
        logger.debug("Grouped {} pages into {} groups", pages.size(), groups.size());
        return groups;
    }
    
    private List<TOCItem> generateTOCInit(String text) {
        String prompt = buildGenerateTOCPrompt(text, null);
        String response = llmClient.call(config.getModel(), prompt, null);
        return parseTOCItems(response);
    }
    
    private List<TOCItem> generateTOCContinue(List<TOCItem> existingItems, String text) {
        String prompt = buildGenerateTOCPrompt(text, existingItems);
        String response = llmClient.call(config.getModel(), prompt, null);
        return parseTOCItems(response);
    }
    
    private String buildGenerateTOCPrompt(String text, List<TOCItem> previousItems) {
        StringBuilder prompt = new StringBuilder("""
            You are an expert in extracting hierarchical tree structure, your task is to generate the tree structure of the document.
            
            The structure variable is the numeric system which represents the index of the hierarchy section in the table of contents. 
            For example, the first section has structure index 1, the first subsection has structure index 1.1, 
            the second subsection has structure index 1.2, etc.
            
            For the title, you need to extract the original title from the text, only fix the space inconsistency.
            
            The response should be in the following format:
            [
                {
                    "structure": "x.x.x" (string),
                    "title": "title of the section, keep the original title",
                    "physical_index": <page number>
                }
            ]
            
            Directly return the final JSON structure. Do not output anything else.
            """);
        
        if (previousItems != null && !previousItems.isEmpty()) {
            prompt.append("\nPrevious tree structure:\n");
            prompt.append(JsonUtils.toJsonString(previousItems));
        }
        
        prompt.append("\nGiven text:\n").append(text);
        
        return prompt.toString();
    }
    
    private List<TOCItem> extractTOCItems(String tocContent) {
        // 简化实现：调用 LLM 解析目录
        String prompt = buildExtractTOCPrompt(tocContent);
        String response = llmClient.call(config.getModel(), prompt, null);
        return parseTOCItems(response);
    }
    
    private String buildExtractTOCPrompt(String tocContent) {
        return String.format("""
            You are given a table of contents. Transform it into a JSON format.
            
            The structure variable is the numeric system which represents the index of the hierarchy section.
            For example, the first section has structure index 1, the first subsection has structure index 1.1, etc.
            
            Given table of contents:
            %s
            
            Return format:
            {
                "table_of_contents": [
                    {
                        "structure": "x.x.x" or null (string),
                        "title": "title of the section",
                        "page": <page number> or null
                    }
                ]
            }
            
            Directly return the final JSON structure. Do not output anything else.
            """, tocContent);
    }
    
    private List<TOCItem> parseTOCItems(String response) {
        List<TOCItem> items = new ArrayList<>();
        try {
            JsonNode json = JsonUtils.extractJson(response);
            if (json == null) {
                logger.warn("Failed to extract JSON from LLM response");
                logger.debug("Response content: {}", response != null ? response.substring(0, Math.min(500, response.length())) : "null");
                return items;
            }
            
            JsonNode tocArray = json.has("table_of_contents") 
                    ? json.get("table_of_contents")
                    : json.isArray() ? json : null;
            
            if (tocArray != null && tocArray.isArray()) {
                for (JsonNode itemNode : tocArray) {
                    TOCItem item = new TOCItem();
                    if (itemNode.has("structure") && !itemNode.get("structure").isNull()) {
                        item.setStructure(itemNode.get("structure").asText());
                    }
                    if (itemNode.has("title")) {
                        item.setTitle(itemNode.get("title").asText());
                    }
                    if (itemNode.has("physical_index") && !itemNode.get("physical_index").isNull()) {
                        item.setPhysicalIndex(itemNode.get("physical_index").asInt());
                    } else if (itemNode.has("page") && !itemNode.get("page").isNull()) {
                        item.setPhysicalIndex(itemNode.get("page").asInt());
                    }
                    items.add(item);
                }
                logger.info("Successfully parsed {} TOC items", items.size());
            } else {
                logger.warn("No valid TOC array found in JSON response");
            }
        } catch (Exception e) {
            logger.error("Failed to parse TOC items from response", e);
            logger.debug("Response content: {}", response != null ? response.substring(0, Math.min(500, response.length())) : "null");
        }
        return items;
    }
    
    /**
     * TOC 检查结果
     */
    private static class TOCResult {
        private final boolean hasTOC;
        private final boolean hasPageNumbers;
        private final int tocPageIndex;
        private final String tocContent;
        
        public TOCResult(boolean hasTOC, boolean hasPageNumbers, int tocPageIndex, String tocContent) {
            this.hasTOC = hasTOC;
            this.hasPageNumbers = hasPageNumbers;
            this.tocPageIndex = tocPageIndex;
            this.tocContent = tocContent;
        }
        
        public boolean hasTOC() {
            return hasTOC;
        }
        
        public boolean hasPageNumbers() {
            return hasPageNumbers;
        }
        
        public int getTocPageIndex() {
            return tocPageIndex;
        }
        
        public String getTocContent() {
            return tocContent;
        }
    }
}
