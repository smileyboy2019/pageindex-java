package com.pageindex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pageindex.llm.LLMClient;
import com.pageindex.llm.LLMClientFactory;
import com.pageindex.model.Config;
import com.pageindex.model.PageContent;
import com.pageindex.model.TreeNode;
import com.pageindex.parser.DocumentParser;
import com.pageindex.tree.TreeBuilder;
import com.pageindex.tree.TreeParser;
import com.pageindex.llm.ImageRecognizer;
import com.pageindex.llm.MultimodalClient;
import com.pageindex.llm.NativeImageRecognizer;
import com.pageindex.llm.PaddleOCRRecognizer;
import com.pageindex.llm.TesseractOCRRecognizer;
import com.pageindex.utils.ConfigLoader;
import com.pageindex.utils.HanLPSummarizer;
import com.pageindex.utils.JsonUtils;
import com.pageindex.utils.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * PageIndex 主类，提供文档索引构建功能
 */
public class PageIndex {
    private static final Logger logger = LoggerFactory.getLogger(PageIndex.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final Config config;
    private final LLMClient llmClient;
    private final DocumentParser documentParser;
    private final TreeParser treeParser;
    private final ImageRecognizer imageRecognizer;
    private Boolean documentIsChinese = null; // 缓存文档语言检测结果
    
    public PageIndex(Config config) {
        this(config, LLMClientFactory.createClient(config));
    }
    
    public PageIndex(Config config, LLMClient llmClient) {
        this.config = config;
        this.llmClient = llmClient;
        this.documentParser = new DocumentParser(
            config.getModel(),
            config.getMaxPageNumEachNode(),
            config.getMaxTokenNumEachNode()
        );
        this.treeParser = new TreeParser(config, llmClient);
        
        // 初始化图片识别器（Tesseract、多模态、PaddleOCR或Java原生）
        Config.MultimodalConfig multimodalConfig = config.getMultimodal();
        if (multimodalConfig != null && multimodalConfig.getEnabled()) {
            String method = multimodalConfig.getRecognitionMethod();
            if ("tesseract".equalsIgnoreCase(method)) {
                // 使用Tesseract OCR（本地识别，速度快）
                this.imageRecognizer = new TesseractOCRRecognizer(
                        multimodalConfig.getTesseractDataPath(),
                        multimodalConfig.getTesseractLanguage()
                );
                logger.info("Using Tesseract OCR image recognition (language: {})", 
                        multimodalConfig.getTesseractLanguage());
            } else if ("paddleocr".equalsIgnoreCase(method)) {
                // 优先使用布局解析API（如果配置了API Key），否则使用本地SDK
                if (multimodalConfig.getPaddleocrApiKey() != null) {
                    // 如果配置了Secret Key，使用标准OCR API；否则使用布局解析API
                    if (multimodalConfig.getPaddleocrSecretKey() != null 
                            && !multimodalConfig.getPaddleocrSecretKey().isEmpty()) {
                        this.imageRecognizer = new PaddleOCRRecognizer(
                                multimodalConfig.getPaddleocrApiKey(),
                                multimodalConfig.getPaddleocrSecretKey(),
                                true
                        );
                        logger.info("Using PaddleOCR standard OCR API");
                    } else {
                        this.imageRecognizer = new PaddleOCRRecognizer(
                                multimodalConfig.getPaddleocrApiKey(),
                                multimodalConfig.getPaddleocrLayoutApiUrl()
                        );
                        logger.info("Using PaddleOCR layout parsing API");
                    }
                } else {
                    this.imageRecognizer = new PaddleOCRRecognizer();
                    logger.info("Using PaddleOCR local SDK image recognition");
                }
            } else if ("native".equalsIgnoreCase(method)) {
                this.imageRecognizer = new NativeImageRecognizer();
                logger.info("Using native Java image recognition");
            } else {
                // 默认使用多模态模型
                this.imageRecognizer = new MultimodalClient(
                        multimodalConfig.getBaseUrl(),
                        multimodalConfig.getModel(),
                        multimodalConfig.getTimeout()
                );
                logger.info("Using multimodal image recognition with model: {}", multimodalConfig.getModel());
            }
        } else {
            this.imageRecognizer = null;
            logger.info("Image recognition is disabled.");
        }
    }
    
    public PageIndex() {
        this(ConfigLoader.loadDefaultConfig());
    }
    
    /**
     * 从文档文件构建索引（支持 PDF 和 Word）
     */
    public CompletableFuture<IndexResult> buildIndex(String filePath) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                logger.info("Building index for document: {}", filePath);
                
                // 解析文档（自动检测类型）
                long parseStart = System.currentTimeMillis();
                List<PageContent> pages = documentParser.parseDocument(filePath);
                long parseTime = System.currentTimeMillis() - parseStart;
                logger.info("Parsed {} pages in {}ms, total tokens: {}",
                        pages.size(), parseTime,
                        pages.stream().mapToInt(PageContent::getTokenCount).sum());
                
                // 检测文档语言（基于前几页的内容）
                long langStart = System.currentTimeMillis();
                documentIsChinese = detectDocumentLanguage(pages);
                long langTime = System.currentTimeMillis() - langStart;
                logger.info("Document language detected as: {} (took {}ms)", 
                        documentIsChinese ? "Chinese" : "English", langTime);
                
                // 构建树结构（传递文件路径以便提取 PDF 书签）
                long treeStart = System.currentTimeMillis();
                List<TreeNode> tree = treeParser.parseTreeFromPages(pages, filePath).join();
                long treeTime = System.currentTimeMillis() - treeStart;
                logger.info("Tree structure built in {}ms, {} nodes", treeTime, countNodes(tree));
                
                // 添加节点文本（如果需要）
                if ("yes".equals(config.getIfAddNodeText())) {
                    addNodeText(tree, pages);
                }
                
                // 构建结果
                IndexResult result = new IndexResult();
                result.setDocName(new File(filePath).getName());
                result.setStructure(tree);
                
                // 添加节点文本（用于摘要生成和图片识别）
                boolean needTextForSummary = "yes".equals(config.getIfAddNodeSummary());
                boolean needTextForImages = imageRecognizer != null && config.getMultimodal() != null 
                        && config.getMultimodal().getEnabled();
                if (needTextForSummary || needTextForImages) {
                    if (!"yes".equals(config.getIfAddNodeText())) {
                        addNodeText(tree, pages);
                    }
                }
                
                // 第一步：先识别图片（如果有），并将图片作为子节点添加到树结构中
                if (needTextForImages) {
                    long imageStart = System.currentTimeMillis();
                    try {
                        detectAndRecognizeImages(tree, pages).join();
                        long imageTime = System.currentTimeMillis() - imageStart;
                        logger.info("Image recognition completed in {}ms", imageTime);
                        
                        // 将图片转换为子节点，保持文档顺序和层次结构
                        convertImagesToChildNodes(tree, pages);
                        
                        // 将图片描述合并到节点文本中（用于摘要生成）
                        mergeImageDescriptionsToText(tree);
                    } catch (Exception e) {
                        logger.warn("Image recognition failed, continuing without image descriptions: {}", e.getMessage());
                        // 图片识别失败，继续处理，不使用图片描述
                    }
                }
                
                // 第二步：生成摘要（基于文字+图片描述）
                if (needTextForSummary) {
                    long summaryStart = System.currentTimeMillis();
                    String summaryMethod = config.getSummaryMethod();
                    if ("hanlp".equals(summaryMethod)) {
                        logger.info("Using HanLP for summary generation");
                        generateSummariesWithHanLP(tree);
                    } else {
                        logger.info("Using LLM for summary generation");
                        generateSummaries(tree).join();
                    }
                    long summaryTime = System.currentTimeMillis() - summaryStart;
                    logger.info("Summary generation completed in {}ms", summaryTime);
                }
                
                // 第三步：重新分配节点ID（因为添加了图片子节点）
                if ("yes".equals(config.getIfAddNodeId())) {
                    TreeBuilder.assignNodeIds(tree);
                }
                
                // 第四步：生成文档描述（如果需要）
                if ("yes".equals(config.getIfAddDocDescription())) {
                    long descStart = System.currentTimeMillis();
                    String description = generateDocDescription(tree);
                    result.setDocDescription(description);
                    long descTime = System.currentTimeMillis() - descStart;
                    logger.info("Document description generation completed in {}ms", descTime);
                }
                
                // 如果不需要保留文本，移除文本（但保留图片描述）
                if (!"yes".equals(config.getIfAddNodeText())) {
                    removeNodeText(tree);
                }
                
                long totalTime = System.currentTimeMillis() - startTime;
                logger.info("Successfully built index for: {} (total time: {}ms)", filePath, totalTime);
                return result;
                
            } catch (Exception e) {
                logger.error("Failed to build index for: " + filePath, e);
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * 构建索引后直接检索回答。便捷方法会解析文档两次，批量查询建议复用 IndexResult。
     */
    public CompletableFuture<RetrievalResult> retrieveAndAnswer(String filePath, String query) {
        return search(filePath, query);
    }
    
    public CompletableFuture<RetrievalResult> search(String filePath, String query) {
        return new PageSearch(config, llmClient).search(filePath, query);
    }
    
    /**
     * 基于已有索引检索回答。
     */
    public CompletableFuture<RetrievalResult> retrieveAndAnswer(
            String filePath, String query, IndexResult indexResult) {
        return search(filePath, query, indexResult);
    }
    
    public CompletableFuture<RetrievalResult> search(
            String filePath, String query, IndexResult indexResult) {
        if (indexResult == null) {
            throw new IllegalArgumentException("indexResult must not be null");
        }
        return new PageSearch(config, llmClient).search(filePath, query, indexResult);
    }
    
    /**
     * 基于已有树结构检索回答。
     */
    public CompletableFuture<RetrievalResult> retrieveAndAnswer(
            String filePath, String query, List<TreeNode> tree) {
        return search(filePath, query, tree);
    }
    
    public CompletableFuture<RetrievalResult> search(
            String filePath, String query, List<TreeNode> tree) {
        return new PageSearch(config, llmClient).search(filePath, query, tree);
    }
    
    private CompletableFuture<RetrievalResult> retrieveAndAnswer(
            String filePath, String query, List<TreeNode> tree, String docName) {
        return new PageSearch(config, llmClient).search(filePath, query, tree, docName);
    }
    
    /**
     * 基于已解析页面检索回答，便于复用解析结果和单元测试。
     */
    public CompletableFuture<RetrievalResult> retrieveAndAnswer(
            String query, List<PageContent> pages, List<TreeNode> tree) {
        return search(query, pages, tree);
    }
    
    public CompletableFuture<RetrievalResult> search(
            String query, List<PageContent> pages, List<TreeNode> tree) {
        return new PageSearch(config, llmClient).search(query, pages, tree);
    }
    
    /**
     * PageIndex 检索阶段：
     * 2A. LLM 只看树结构和摘要，选择相关 nodeId；
     * 2B. Java 根据页码范围抽取原文；
     * 2C. LLM 只看抽取片段生成答案。
     */
    public CompletableFuture<RetrievalResult> retrieveAndAnswer(
            String query, List<PageContent> pages, List<TreeNode> tree, String docName) {
        return search(query, pages, tree, docName);
    }
    
    public CompletableFuture<RetrievalResult> search(
            String query, List<PageContent> pages, List<TreeNode> tree, String docName) {
        return new PageSearch(config, llmClient).search(query, pages, tree, docName);
    }
    
    private String buildNavigationIndexJson(List<TreeNode> tree) {
        try {
            return mapper.writeValueAsString(toNavigationNodes(tree));
        } catch (Exception e) {
            logger.warn("Failed to serialize navigation index, using regular JSON: {}", e.getMessage());
            return JsonUtils.toJsonString(tree);
        }
    }
    
    private List<Map<String, Object>> toNavigationNodes(List<TreeNode> nodes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TreeNode node : nodes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", node.getTitle());
            item.put("nodeId", node.getNodeId());
            item.put("startIndex", node.getStartIndex());
            item.put("endIndex", node.getEndIndex());
            item.put("summary", node.getSummary());
            if (node.hasChildren()) {
                item.put("nodes", toNavigationNodes(node.getNodes()));
            }
            result.add(item);
        }
        return result;
    }
    
    private NodeSelection selectRelevantNodes(String query, String navigationIndexJson, List<TreeNode> tree) {
        String prompt = buildNodeSelectionPrompt(query, navigationIndexJson);
        String response = llmClient.call(config.getModel(), prompt, null);
        
        NodeSelection selection = parseNodeSelectionResponse(response);
        selection.setRawResponse(response);
        
        Set<String> validIds = flattenNodes(tree).stream()
                .map(TreeNode::getNodeId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        
        List<String> validSelectedIds = selection.getNodeIds().stream()
                .filter(validIds::contains)
                .distinct()
                .limit(3)
                .collect(Collectors.toList());
        selection.setNodeIds(validSelectedIds);
        return selection;
    }
    
    private String buildNodeSelectionPrompt(String query, String navigationIndexJson) {
        boolean isChinese = (documentIsChinese != null && documentIsChinese)
                || query.matches(".*[\\u4e00-\\u9fa5].*");
        
        if (isChinese) {
            return String.format("""
                你是一个文档导航代理。
                你将获得文档的树结构索引。每个节点包含 title、nodeId、页码范围和 summary。
                请只根据树结构和摘要判断用户问题最可能落在哪些节点中，不要猜测文档正文。
                
                规则：
                1. 最多返回 1 到 3 个 nodeId，优先选择最具体的节点。
                2. 只能返回索引中真实存在的 nodeId。
                3. 必须返回 JSON，不要输出 Markdown 或额外文字。
                
                返回格式：
                {"node_ids":["0001"],"reason":"为什么这些节点最相关"}
                
                文档树结构：
                %s
                
                用户问题：
                %s
                
                请直接返回 JSON：
                """, navigationIndexJson, query);
        }
        
        return String.format("""
            You are a document navigation agent.
            You have a tree index of a document. Each node includes title, nodeId, page range, and summary.
            Decide which nodes are most likely to contain the answer using only the index and summaries.
            
            Rules:
            1. Return 1 to 3 node IDs maximum. Prefer the most specific nodes.
            2. Only return nodeId values that exist in the index.
            3. Return JSON only. No markdown and no extra prose.
            
            Return format:
            {"node_ids":["0001"],"reason":"why these nodes are most relevant"}
            
            DOCUMENT TREE:
            %s
            
            USER QUERY:
            %s
            
            JSON:
            """, navigationIndexJson, query);
    }
    
    private NodeSelection parseNodeSelectionResponse(String response) {
        NodeSelection selection = new NodeSelection();
        if (response == null || response.trim().isEmpty() || response.equals("Error") || response.startsWith("Error:")) {
            return selection;
        }
        
        try {
            com.fasterxml.jackson.databind.JsonNode json = JsonUtils.extractJson(response);
            if (json != null) {
                com.fasterxml.jackson.databind.JsonNode idsNode = null;
                if (json.has("node_ids")) {
                    idsNode = json.get("node_ids");
                } else if (json.has("nodeIds")) {
                    idsNode = json.get("nodeIds");
                } else if (json.isArray()) {
                    idsNode = json;
                }
                
                if (idsNode != null) {
                    if (idsNode.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode idNode : idsNode) {
                            if (!idNode.isNull()) {
                                selection.getNodeIds().add(idNode.asText().trim());
                            }
                        }
                    } else if (idsNode.isTextual()) {
                        addDelimitedNodeIds(selection.getNodeIds(), idsNode.asText());
                    }
                }
                
                if (json.has("reason") && !json.get("reason").isNull()) {
                    selection.setReasoning(json.get("reason").asText());
                } else if (json.has("reasoning") && !json.get("reasoning").isNull()) {
                    selection.setReasoning(json.get("reasoning").asText());
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to parse node selection as JSON: {}", e.getMessage());
        }
        
        if (selection.getNodeIds().isEmpty()) {
            addDelimitedNodeIds(selection.getNodeIds(), response);
        }
        return selection;
    }
    
    private void addDelimitedNodeIds(List<String> nodeIds, String text) {
        if (text == null) {
            return;
        }
        String cleaned = text.replaceAll("[\\[\\]\"'`]", "");
        for (String part : cleaned.split("[,，\\s]+")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                nodeIds.add(id);
            }
        }
    }
    
    private List<TreeNode> resolveSelectedNodes(List<TreeNode> tree, List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, TreeNode> byId = new LinkedHashMap<>();
        for (TreeNode node : flattenNodes(tree)) {
            if (node.getNodeId() != null) {
                byId.put(node.getNodeId(), node);
            }
        }
        
        List<TreeNode> selected = new ArrayList<>();
        for (String nodeId : nodeIds) {
            TreeNode node = byId.get(nodeId);
            if (node != null && !selected.contains(node)) {
                selected.add(node);
            }
        }
        return selected;
    }
    
    private List<TreeNode> flattenNodes(List<TreeNode> nodes) {
        List<TreeNode> flat = new ArrayList<>();
        for (TreeNode node : nodes) {
            flat.add(node);
            if (node.hasChildren()) {
                flat.addAll(flattenNodes(node.getNodes()));
            }
        }
        return flat;
    }
    
    private List<TreeNode> fallbackSelectNodes(String query, List<TreeNode> tree) {
        List<TreeNode> allNodes = flattenNodes(tree);
        List<ScoredNode> scoredNodes = new ArrayList<>();
        for (TreeNode node : allNodes) {
            int score = scoreNodeForQuery(query, node);
            if (score > 0) {
                scoredNodes.add(new ScoredNode(node, score));
            }
        }
        
        scoredNodes.sort((a, b) -> Integer.compare(b.score, a.score));
        if (!scoredNodes.isEmpty()) {
            return scoredNodes.stream()
                    .limit(3)
                    .map(scored -> scored.node)
                    .collect(Collectors.toList());
        }
        
        return allNodes.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(List.of(allNodes.get(0)));
    }
    
    private int scoreNodeForQuery(String query, TreeNode node) {
        String title = node.getTitle() != null ? node.getTitle() : "";
        String summary = node.getSummary() != null ? node.getSummary() : "";
        String haystack = normalizeForScore(title + " " + summary);
        String normalizedQuery = normalizeForScore(query);
        
        int score = 0;
        if (!normalizedQuery.isEmpty() && haystack.contains(normalizedQuery)) {
            score += 10;
        }
        
        for (String token : extractQueryTokens(query)) {
            if (haystack.contains(token)) {
                score += title.toLowerCase(Locale.ROOT).contains(token) ? 4 : 2;
            }
        }
        return score;
    }
    
    private List<String> extractQueryTokens(String query) {
        if (query == null) {
            return new ArrayList<>();
        }
        List<String> tokens = new ArrayList<>();
        for (String part : query.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+")) {
            String token = normalizeForScore(part);
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }
    
    private String normalizeForScore(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+", "");
    }
    
    private String extractTextForNodes(List<TreeNode> selectedNodes, List<PageContent> pages) {
        StringBuilder text = new StringBuilder();
        Set<Integer> includedPages = new LinkedHashSet<>();
        
        List<TreeNode> orderedNodes = selectedNodes.stream()
                .sorted((a, b) -> Integer.compare(
                        a.getStartIndex() != null ? a.getStartIndex() : Integer.MAX_VALUE,
                        b.getStartIndex() != null ? b.getStartIndex() : Integer.MAX_VALUE))
                .collect(Collectors.toList());
        
        for (TreeNode node : orderedNodes) {
            Integer start = node.getStartIndex();
            Integer end = node.getEndIndex();
            if (start == null || end == null) {
                continue;
            }
            
            int boundedStart = Math.max(1, start);
            int boundedEnd = Math.min(pages.size(), end);
            if (boundedStart > boundedEnd) {
                continue;
            }
            
            text.append("\n--- SOURCE: ")
                    .append(formatSourceSection(node))
                    .append(" ---\n");
            
            for (int pageIndex = boundedStart - 1; pageIndex < boundedEnd; pageIndex++) {
                PageContent page = pages.get(pageIndex);
                int pageNumber = page.getPageNumber() > 0 ? page.getPageNumber() : pageIndex + 1;
                if (!includedPages.add(pageNumber)) {
                    continue;
                }
                text.append("\n[Page ").append(pageNumber).append("]\n");
                if (page.getText() != null) {
                    text.append(page.getText()).append("\n");
                }
            }
        }
        return text.toString().trim();
    }
    
    private String synthesizeAnswer(String query, String extractedText, List<TreeNode> selectedNodes) {
        String sourceList = selectedNodes.stream()
                .map(this::formatSourceSection)
                .collect(Collectors.joining("\n"));
        
        boolean isChinese = (documentIsChinese != null && documentIsChinese)
                || query.matches(".*[\\u4e00-\\u9fa5].*");
        String prompt;
        if (isChinese) {
            prompt = String.format("""
                你是一个严谨的文档问答助手。
                你只能使用下面给出的相关原文片段回答问题，不要使用片段之外的信息。
                如果片段中没有答案，请明确说明无法从提供片段中确定。
                回答末尾请引用来源节点。
                
                来源节点：
                %s
                
                相关原文片段：
                %s
                
                用户问题：
                %s
                
                请直接回答：
                """, sourceList, extractedText, query);
        } else {
            prompt = String.format("""
                You are a precise question-answering assistant.
                Use only the relevant excerpt below. Do not use information outside the excerpt.
                If the answer is not present, say that it cannot be determined from the provided excerpt.
                Cite the source node(s) at the end.
                
                SOURCE NODES:
                %s
                
                RELEVANT EXCERPT:
                %s
                
                USER QUESTION:
                %s
                
                ANSWER:
                """, sourceList, extractedText, query);
        }
        
        try {
            return llmClient.call(config.getModel(), prompt, null);
        } catch (Exception e) {
            logger.warn("Answer synthesis failed: {}", e.getMessage());
            return "Error";
        }
    }
    
    private String buildFallbackAnswer(List<TreeNode> selectedNodes) {
        String sources = selectedNodes.stream()
                .map(this::formatSourceSection)
                .collect(Collectors.joining("; "));
        if (documentIsChinese != null && documentIsChinese) {
            return "已找到相关片段，但模型未能生成答案。来源节点：" + sources;
        }
        return "Relevant excerpts were found, but the model did not produce an answer. Sources: " + sources;
    }
    
    private String joinAllPages(List<PageContent> pages) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            PageContent page = pages.get(i);
            int pageNumber = page.getPageNumber() > 0 ? page.getPageNumber() : i + 1;
            text.append("\n[Page ").append(pageNumber).append("]\n");
            if (page.getText() != null) {
                text.append(page.getText()).append("\n");
            }
        }
        return text.toString().trim();
    }
    
    private String formatSourceSection(TreeNode node) {
        String nodeId = node.getNodeId() != null ? node.getNodeId() : "unknown";
        String title = node.getTitle() != null ? node.getTitle() : "Untitled";
        String range = "";
        if (node.getStartIndex() != null && node.getEndIndex() != null) {
            range = String.format(" (pages %d-%d)", node.getStartIndex(), node.getEndIndex());
        }
        return String.format("%s [%s]%s", title, nodeId, range);
    }
    
    private double calculateReduction(int fullCount, int extractedCount) {
        if (fullCount <= 0) {
            return 0.0;
        }
        double ratio = 1.0 - ((double) extractedCount / fullCount);
        return Math.max(0.0, Math.min(1.0, ratio));
    }
    
    /**
     * 为树节点添加文本内容
     */
    private void addNodeText(List<TreeNode> nodes, List<PageContent> pages) {
        for (TreeNode node : nodes) {
            if (node.getStartIndex() != null && node.getEndIndex() != null) {
                StringBuilder text = new StringBuilder();
                for (int i = node.getStartIndex() - 1; i < node.getEndIndex() && i < pages.size(); i++) {
                    text.append(pages.get(i).getText()).append("\n");
                }
                node.setText(text.toString());
            }
            
            if (node.hasChildren()) {
                addNodeText(node.getNodes(), pages);
            }
        }
    }
    
    /**
     * 移除树节点的文本内容（但保留图片描述）
     */
    private void removeNodeText(List<TreeNode> nodes) {
        for (TreeNode node : nodes) {
            node.setText(null);
            if (node.hasChildren()) {
                removeNodeText(node.getNodes());
            }
        }
    }
    
    /**
     * 将图片转换为子节点，保持文档顺序和层次结构
     */
    private void convertImagesToChildNodes(List<TreeNode> nodes, List<PageContent> pages) {
        for (TreeNode node : nodes) {
            if (node.hasImages() && node.getImages() != null && !node.getImages().isEmpty()) {
                // 为每个图片创建子节点
                List<TreeNode> imageNodes = new ArrayList<>();
                int imageIndex = 1;
                
                for (TreeNode.ImageDescription imageDesc : node.getImages()) {
                    if (imageDesc.getDescription() != null && !imageDesc.getDescription().isEmpty()) {
                        TreeNode imageNode = new TreeNode();
                        // 图片节点标题：图1、图2等
                        imageNode.setTitle("图 " + imageIndex);
                        // 图片节点的页码范围：使用图片所在的页码
                        imageNode.setStartIndex(imageDesc.getPageNumber());
                        imageNode.setEndIndex(imageDesc.getPageNumber());
                        // 图片描述作为摘要
                        imageNode.setSummary(imageDesc.getDescription());
                        // 图片描述也作为文本内容
                        imageNode.setText(imageDesc.getDescription());
                        // 设置父节点ID（将在后续分配节点ID时更新）
                        imageNode.setParentNodeId(node.getNodeId());
                        
                        imageNodes.add(imageNode);
                        imageIndex++;
                    }
                }
                
                // 将图片节点插入到子节点列表中，按照页码顺序
                if (!imageNodes.isEmpty()) {
                    List<TreeNode> existingChildren = node.getNodes();
                    if (existingChildren == null) {
                        existingChildren = new ArrayList<>();
                        node.setNodes(existingChildren);
                    }
                    
                    // 合并现有子节点和图片节点，按照startIndex排序
                    List<TreeNode> allChildren = new ArrayList<>(existingChildren);
                    allChildren.addAll(imageNodes);
                    
                    // 按照startIndex排序，保持文档顺序
                    allChildren.sort((n1, n2) -> {
                        int start1 = n1.getStartIndex() != null ? n1.getStartIndex() : 0;
                        int start2 = n2.getStartIndex() != null ? n2.getStartIndex() : 0;
                        return Integer.compare(start1, start2);
                    });
                    
                    node.setNodes(allChildren);
                    
                    // 清除images字段，因为已经转换为子节点
                    node.setImages(null);
                    
                    logger.debug("Converted {} images to child nodes for node '{}'", imageNodes.size(), node.getTitle());
                }
            }
            
            // 递归处理子节点
            if (node.hasChildren()) {
                convertImagesToChildNodes(node.getNodes(), pages);
            }
        }
    }
    
    /**
     * 将图片描述合并到节点文本中（用于摘要生成）
     */
    private void mergeImageDescriptionsToText(List<TreeNode> nodes) {
        for (TreeNode node : nodes) {
            // 如果节点有图片子节点，将图片描述合并到文本中
            if (node.hasChildren()) {
                StringBuilder textWithImages = new StringBuilder();
                
                // 先添加原始文本
                if (node.getText() != null && !node.getText().isEmpty()) {
                    textWithImages.append(node.getText());
                }
                
                // 添加图片子节点的描述
                for (TreeNode child : node.getNodes()) {
                    if (child.getTitle() != null && child.getTitle().startsWith("图 ")) {
                        // 这是一个图片节点
                        if (child.getSummary() != null && !child.getSummary().isEmpty()) {
                            if (textWithImages.length() > 0) {
                                textWithImages.append("\n\n");
                            }
                            textWithImages.append("[").append(child.getTitle())
                                    .append("（第").append(child.getStartIndex()).append("页）]: ")
                                    .append(child.getSummary());
                        }
                    }
                }
                
                // 更新节点文本（包含图片描述）
                if (textWithImages.length() > 0) {
                    node.setText(textWithImages.toString());
                }
            }
            
            // 递归处理子节点
            if (node.hasChildren()) {
                mergeImageDescriptionsToText(node.getNodes());
            }
        }
    }
    
    /**
     * 为树节点生成摘要
     */
    private CompletableFuture<Void> generateSummaries(List<TreeNode> nodes) {
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        
        for (TreeNode node : nodes) {
            if (node.getText() != null && !node.getText().isEmpty()) {
                CompletableFuture<Void> future = llmClient.callAsync(config.getModel(), 
                        buildSummaryPrompt(node.getText()))
                        .thenApply(summary -> {
                            // 检查LLM返回的摘要是否有效
                            if (summary == null || summary.trim().isEmpty() || 
                                summary.equals("Error") || summary.startsWith("Error:")) {
                                // LLM调用失败，使用HanLP生成摘要
                                logger.warn("LLM summary generation failed for node '{}', using HanLP fallback", 
                                        node.getTitle());
                                return generateSummaryWithHanLP(node.getText());
                            }
                            return summary;
                        })
                        .exceptionally(ex -> {
                            // LLM调用异常，使用HanLP生成摘要
                            logger.warn("LLM summary generation exception for node '{}', using HanLP fallback: {}", 
                                    node.getTitle(), ex.getMessage());
                            return generateSummaryWithHanLP(node.getText());
                        })
                        .thenAccept(node::setSummary);
                futures.add(future);
            }
            
            if (node.hasChildren()) {
                futures.add(generateSummaries(node.getNodes()));
            }
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * 使用HanLP为所有节点生成摘要（回退方案）
     */
    private void generateSummariesWithHanLP(List<TreeNode> nodes) {
        for (TreeNode node : nodes) {
            if (node.getText() != null && !node.getText().isEmpty()) {
                String summary = generateSummaryWithHanLP(node.getText());
                if (summary != null && !summary.isEmpty()) {
                    node.setSummary(summary);
                }
            }
            if (node.hasChildren()) {
                generateSummariesWithHanLP(node.getNodes());
            }
        }
    }
    
    /**
     * 使用HanLP生成摘要（回退方案）
     */
    private String generateSummaryWithHanLP(String text) {
        try {
            // 根据文档语言选择摘要长度
            boolean isChinese = (documentIsChinese != null && documentIsChinese) 
                    || text.matches(".*[\\u4e00-\\u9fa5].*");
            
            // 设置摘要长度：中文文档200-300字，英文文档100-200词
            int maxLength = isChinese ? 250 : 150;
            
            String summary = HanLPSummarizer.summarize(text, isChinese, maxLength);
            
            if (summary != null && !summary.trim().isEmpty()) {
                logger.debug("Successfully generated summary using HanLP (length: {})", summary.length());
                return summary;
            } else {
                logger.warn("HanLP summary is empty, using text truncation");
                return truncateTextForSummary(text, maxLength);
            }
        } catch (Exception e) {
            logger.error("Failed to generate summary using HanLP", e);
            // 最后的回退：截断文本
            return truncateTextForSummary(text, 200);
        }
    }
    
    /**
     * 截断文本作为摘要（最后的回退方案）
     */
    private String truncateTextForSummary(String text, int maxLength) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        
        if (text.length() <= maxLength) {
            return text;
        }
        
        // 尝试在句号处截断
        String truncated = text.substring(0, maxLength);
        int lastPeriod = Math.max(
            Math.max(truncated.lastIndexOf('。'), truncated.lastIndexOf('！')),
            Math.max(truncated.lastIndexOf('？'), truncated.lastIndexOf('.'))
        );
        
        if (lastPeriod > maxLength * 0.5) {
            return truncated.substring(0, lastPeriod + 1);
        }
        
        return truncated + "...";
    }
    
    /**
     * 检测文档语言
     */
    private boolean detectDocumentLanguage(List<PageContent> pages) {
        // 检查前几页的内容，如果包含中文字符，认为是中文文档
        int checkPages = Math.min(5, pages.size());
        int chineseCharCount = 0;
        int totalCharCount = 0;
        
        for (int i = 0; i < checkPages; i++) {
            String text = pages.get(i).getText();
            if (text != null && !text.isEmpty()) {
                totalCharCount += text.length();
                // 统计中文字符数量
                for (char c : text.toCharArray()) {
                    if (c >= 0x4e00 && c <= 0x9fa5) {
                        chineseCharCount++;
                    }
                }
            }
        }
        
        // 如果中文字符占比超过5%，认为是中文文档
        return totalCharCount > 0 && (chineseCharCount * 100.0 / totalCharCount) > 5.0;
    }
    
    private String buildSummaryPrompt(String text) {
        // 优先使用文档级别的语言检测，如果文本本身包含中文也使用中文
        boolean isChinese = (documentIsChinese != null && documentIsChinese) 
                || text.matches(".*[\\u4e00-\\u9fa5].*");
        
        if (isChinese) {
            // 中文文档，使用中文 prompt，强调准确性和完整性
            return String.format("""
                请仔细阅读以下文档片段，并用中文准确总结其主要内容。
                
                要求：
                1. 必须使用中文回答
                2. 准确反映文档的实际内容，不要遗漏重要信息
                3. 用简洁的中文总结文档的主要内容，突出关键信息和要点
                4. 确保摘要与文档内容一致，不要添加文档中没有的信息
                5. 只返回摘要内容，不要包含其他文字、标记或思考过程
                
                文档内容：
                %s
                
                请直接返回中文摘要：
                """, text);
        } else {
            // 英文文档，使用英文 prompt，强调准确性
            return String.format("""
                You are given a part of a document, your task is to generate an accurate description of the partial document 
                about what are main points covered in the partial document.
                
                Requirements:
                1. Accurately reflect the actual content of the the document, do not omit important information
                2. Ensure the summary is consistent with the document content, do not add information not in the document
                3. Highlight key information and main points
                4. Directly return the description, do not include any other text, markers, or thinking process
                
                Partial Document Text: %s
                
                Summary:
                """, text);
        }
    }
    
    /**
     * 生成文档描述
     */
    private String generateDocDescription(List<TreeNode> tree) {
        String structureJson = JsonUtils.toJsonString(tree);
        String prompt;
        if (documentIsChinese != null && documentIsChinese) {
            prompt = String.format("""
                你是一个文档描述专家。
                你将获得一个文档的结构。你的任务是为该文档生成一个一句话的描述，
                该描述应易于将该文档与其他文档区分开来。
                
                文档结构：
                %s
                
                请直接返回中文描述，不要包含任何其他文字。
                """, structureJson);
        } else {
            prompt = String.format("""
                You are an expert in generating descriptions for a document.
                You are given a structure of a document. Your task is to generate a one-sentence description for the document,
                which makes it easy to distinguish the document from other documents.

                Document Structure: %s

                Directly return the description, do not include any other text.
                """, structureJson);
        }
        
        try {
            String description = llmClient.call(config.getModel(), prompt, null);
            // 检查LLM返回的描述是否有效
            if (description == null || description.trim().isEmpty() || 
                description.equals("Error") || description.startsWith("Error:")) {
                // LLM调用失败，使用基于树结构的简单描述
                logger.warn("LLM doc description generation failed, using fallback");
                return generateDocDescriptionFallback(tree);
            }
            return description;
        } catch (Exception e) {
            logger.warn("LLM doc description generation exception, using fallback: {}", e.getMessage());
            return generateDocDescriptionFallback(tree);
        }
    }
    
    /**
     * 生成文档描述的回退方案
     */
    private String generateDocDescriptionFallback(List<TreeNode> tree) {
        if (tree == null || tree.isEmpty()) {
            return "文档内容";
        }
        
        // 提取前几个节点的标题作为描述
        StringBuilder desc = new StringBuilder();
        int count = 0;
        for (TreeNode node : tree) {
            if (node.getTitle() != null && !node.getTitle().isEmpty()) {
                if (desc.length() > 0) {
                    desc.append("、");
                }
                desc.append(node.getTitle());
                count++;
                if (count >= 3) {
                    break;
                }
            }
        }
        
        if (desc.length() > 0) {
            return desc.toString();
        }
        
        return "文档内容";
    }
    
    /**
     * 使用指定的识别器检测并识别节点中的图片
     */
    private CompletableFuture<Void> detectAndRecognizeImagesWithRecognizer(
            List<TreeNode> nodes, List<PageContent> pages, ImageRecognizer recognizer) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (TreeNode node : nodes) {
            if (node.getStartIndex() != null && node.getEndIndex() != null) {
                // 收集该节点范围内的所有图片
                List<PageContent.ImageInfo> nodeImages = new ArrayList<>();
                for (int i = node.getStartIndex() - 1; i < node.getEndIndex() && i < pages.size(); i++) {
                    PageContent page = pages.get(i);
                    if (page.hasImages()) {
                        nodeImages.addAll(page.getImages());
                    }
                }
                
                // 如果有图片，识别它们（并行识别）
                if (!nodeImages.isEmpty()) {
                    logger.debug("Found {} images in node '{}', starting recognition", nodeImages.size(), node.getTitle());
                    CompletableFuture<Void> future = recognizer.recognizeImagesAsync(nodeImages)
                            .thenRun(() -> {
                                // 将识别结果添加到节点
                                List<TreeNode.ImageDescription> imageDescriptions = new ArrayList<>();
                                for (PageContent.ImageInfo imageInfo : nodeImages) {
                                    if (imageInfo.getDescription() != null && !imageInfo.getDescription().isEmpty()) {
                                        // 找到图片所在的页码
                                        int imagePage = 0;
                                        for (int i = node.getStartIndex() - 1; i < node.getEndIndex() && i < pages.size(); i++) {
                                            if (pages.get(i).hasImages() && pages.get(i).getImages().contains(imageInfo)) {
                                                imagePage = pages.get(i).getPageNumber();
                                                break;
                                            }
                                        }
                                        
                                        TreeNode.ImageDescription desc = new TreeNode.ImageDescription(
                                                imagePage > 0 ? imagePage : node.getStartIndex(),
                                                imageInfo.getDescription(),
                                                imageInfo.getWidth(),
                                                imageInfo.getHeight(),
                                                imageInfo.getImageFormat()
                                        );
                                        imageDescriptions.add(desc);
                                    }
                                }
                                
                                if (!imageDescriptions.isEmpty()) {
                                    node.setImages(imageDescriptions);
                                    logger.info("Recognized {} images for node '{}'", imageDescriptions.size(), node.getTitle());
                                } else {
                                    logger.warn("No image descriptions generated for node '{}'", node.getTitle());
                                }
                            });
                    futures.add(future);
                } else {
                    // 没有图片的节点，不设置images字段（保持为null）
                    logger.debug("No images found in node '{}'", node.getTitle());
                }
            }
            
            // 递归处理子节点
            if (node.hasChildren()) {
                futures.add(detectAndRecognizeImagesWithRecognizer(node.getNodes(), pages, imageRecognizer));
            }
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * 检测并识别节点中的图片（使用默认识别器）
     */
    private CompletableFuture<Void> detectAndRecognizeImages(List<TreeNode> nodes, List<PageContent> pages) {
        return detectAndRecognizeImagesWithRecognizer(nodes, pages, imageRecognizer);
    }
    
    /**
     * 统计树节点数量
     */
    private int countNodes(List<TreeNode> nodes) {
        int count = 0;
        for (TreeNode node : nodes) {
            count++;
            if (node.hasChildren()) {
                count += countNodes(node.getNodes());
            }
        }
        return count;
    }
    
    private static class NodeSelection {
        private List<String> nodeIds = new ArrayList<>();
        private String reasoning;
        private String rawResponse;
        
        public List<String> getNodeIds() {
            return nodeIds;
        }
        
        public void setNodeIds(List<String> nodeIds) {
            this.nodeIds = nodeIds != null ? nodeIds : new ArrayList<>();
        }
        
        public String getReasoning() {
            return reasoning;
        }
        
        public void setReasoning(String reasoning) {
            this.reasoning = reasoning;
        }
        
        public String getRawResponse() {
            return rawResponse;
        }
        
        public void setRawResponse(String rawResponse) {
            this.rawResponse = rawResponse;
        }
    }
    
    private static class ScoredNode {
        private final TreeNode node;
        private final int score;
        
        private ScoredNode(TreeNode node, int score) {
            this.node = node;
            this.score = score;
        }
    }
    
    /**
     * 检索结果，包含可观测的节点选择、抽取片段和压缩指标
     */
    public static class RetrievalResult {
        private String query;
        private String docName;
        private List<String> selectedNodeIds;
        private List<String> sourceSections;
        private String selectionReasoning;
        private String rawNodeSelectionResponse;
        private String extractedText;
        private String answer;
        private int fullTextCharCount;
        private int extractedTextCharCount;
        private int fullTextTokenCount;
        private int extractedTextTokenCount;
        private double charReductionRatio;
        private double tokenReductionRatio;
        private long elapsedMs;
        
        public String getQuery() {
            return query;
        }
        
        public void setQuery(String query) {
            this.query = query;
        }
        
        public String getDocName() {
            return docName;
        }
        
        public void setDocName(String docName) {
            this.docName = docName;
        }
        
        public List<String> getSelectedNodeIds() {
            return selectedNodeIds;
        }
        
        public void setSelectedNodeIds(List<String> selectedNodeIds) {
            this.selectedNodeIds = selectedNodeIds;
        }
        
        public List<String> getSourceSections() {
            return sourceSections;
        }
        
        public void setSourceSections(List<String> sourceSections) {
            this.sourceSections = sourceSections;
        }
        
        public String getSelectionReasoning() {
            return selectionReasoning;
        }
        
        public void setSelectionReasoning(String selectionReasoning) {
            this.selectionReasoning = selectionReasoning;
        }
        
        public String getRawNodeSelectionResponse() {
            return rawNodeSelectionResponse;
        }
        
        public void setRawNodeSelectionResponse(String rawNodeSelectionResponse) {
            this.rawNodeSelectionResponse = rawNodeSelectionResponse;
        }
        
        public String getExtractedText() {
            return extractedText;
        }
        
        public void setExtractedText(String extractedText) {
            this.extractedText = extractedText;
        }
        
        public String getAnswer() {
            return answer;
        }
        
        public void setAnswer(String answer) {
            this.answer = answer;
        }
        
        public int getFullTextCharCount() {
            return fullTextCharCount;
        }
        
        public void setFullTextCharCount(int fullTextCharCount) {
            this.fullTextCharCount = fullTextCharCount;
        }
        
        public int getExtractedTextCharCount() {
            return extractedTextCharCount;
        }
        
        public void setExtractedTextCharCount(int extractedTextCharCount) {
            this.extractedTextCharCount = extractedTextCharCount;
        }
        
        public int getFullTextTokenCount() {
            return fullTextTokenCount;
        }
        
        public void setFullTextTokenCount(int fullTextTokenCount) {
            this.fullTextTokenCount = fullTextTokenCount;
        }
        
        public int getExtractedTextTokenCount() {
            return extractedTextTokenCount;
        }
        
        public void setExtractedTextTokenCount(int extractedTextTokenCount) {
            this.extractedTextTokenCount = extractedTextTokenCount;
        }
        
        public double getCharReductionRatio() {
            return charReductionRatio;
        }
        
        public void setCharReductionRatio(double charReductionRatio) {
            this.charReductionRatio = charReductionRatio;
        }
        
        public double getTokenReductionRatio() {
            return tokenReductionRatio;
        }
        
        public void setTokenReductionRatio(double tokenReductionRatio) {
            this.tokenReductionRatio = tokenReductionRatio;
        }
        
        public long getElapsedMs() {
            return elapsedMs;
        }
        
        public void setElapsedMs(long elapsedMs) {
            this.elapsedMs = elapsedMs;
        }
    }
    
    /**
     * 索引结果
     */
    public static class IndexResult {
        private String docName;
        private String docDescription;
        private List<TreeNode> structure;
        
        public String getDocName() {
            return docName;
        }
        
        public void setDocName(String docName) {
            this.docName = docName;
        }
        
        public String getDocDescription() {
            return docDescription;
        }
        
        public void setDocDescription(String docDescription) {
            this.docDescription = docDescription;
        }
        
        public List<TreeNode> getStructure() {
            return structure;
        }
        
        public void setStructure(List<TreeNode> structure) {
            this.structure = structure;
        }
    }
}
