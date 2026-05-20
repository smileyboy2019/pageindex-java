package com.pageindex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pageindex.llm.LLMClient;
import com.pageindex.llm.LLMClientFactory;
import com.pageindex.model.Config;
import com.pageindex.model.PageContent;
import com.pageindex.model.TreeNode;
import com.pageindex.parser.DocumentParser;
import com.pageindex.utils.ConfigLoader;
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
 * PageSearch 专门负责 PageIndex 的检索阶段。
 *
 * <p>流程：
 * <ol>
 *   <li>LLM 只看树结构和摘要，选择相关 nodeId。</li>
 *   <li>Java 根据节点页码范围抽取原文。</li>
 *   <li>LLM 只看抽取片段生成答案。</li>
 * </ol>
 */
public class PageSearch {
    private static final Logger logger = LoggerFactory.getLogger(PageSearch.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final Config config;
    private final LLMClient llmClient;
    private final DocumentParser documentParser;
    private Boolean documentIsChinese = null;
    
    public PageSearch() {
        this(ConfigLoader.loadDefaultConfig());
    }
    
    public PageSearch(Config config) {
        this(config, LLMClientFactory.createClient(config));
    }
    
    public PageSearch(Config config, LLMClient llmClient) {
        this.config = config;
        this.llmClient = llmClient;
        this.documentParser = new DocumentParser(
                config.getModel(),
                config.getMaxPageNumEachNode(),
                config.getMaxTokenNumEachNode());
    }
    
    /**
     * 构建索引后检索。批量查询建议先用 PageIndex.buildIndex，再复用 IndexResult。
     */
    public CompletableFuture<PageIndex.RetrievalResult> search(String filePath, String query) {
        PageIndex pageIndex = new PageIndex(config, llmClient);
        return pageIndex.buildIndex(filePath)
                .thenCompose(indexResult -> search(filePath, query, indexResult));
    }
    
    /**
     * 基于已有索引检索。
     */
    public CompletableFuture<PageIndex.RetrievalResult> search(
            String filePath, String query, PageIndex.IndexResult indexResult) {
        if (indexResult == null) {
            throw new IllegalArgumentException("indexResult must not be null");
        }
        return search(filePath, query, indexResult.getStructure(), indexResult.getDocName());
    }
    
    /**
     * 基于已有树结构检索。
     */
    public CompletableFuture<PageIndex.RetrievalResult> search(
            String filePath, String query, List<TreeNode> tree) {
        String docName = filePath != null ? new File(filePath).getName() : null;
        return search(filePath, query, tree, docName);
    }
    
    public CompletableFuture<PageIndex.RetrievalResult> search(
            String filePath, String query, List<TreeNode> tree, String docName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<PageContent> pages = documentParser.parseDocument(filePath);
                return search(query, pages, tree, docName).join();
            } catch (Exception e) {
                logger.error("Failed to search document: " + filePath, e);
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * 基于已解析页面和已有树检索，便于复用解析结果和单元测试。
     */
    public CompletableFuture<PageIndex.RetrievalResult> search(
            String query, List<PageContent> pages, List<TreeNode> tree) {
        return search(query, pages, tree, null);
    }
    
    public CompletableFuture<PageIndex.RetrievalResult> search(
            String query, List<PageContent> pages, List<TreeNode> tree, String docName) {
        return CompletableFuture.supplyAsync(() -> {
            if (query == null || query.trim().isEmpty()) {
                throw new IllegalArgumentException("query must not be empty");
            }
            if (pages == null || pages.isEmpty()) {
                throw new IllegalArgumentException("pages must not be empty");
            }
            if (tree == null || tree.isEmpty()) {
                throw new IllegalArgumentException("tree must not be empty");
            }
            
            documentIsChinese = detectDocumentLanguage(pages);
            
            long startTime = System.currentTimeMillis();
            String navigationIndexJson = buildNavigationIndexJson(tree);
            NodeSelection selection = selectRelevantNodes(query, navigationIndexJson, tree);
            List<TreeNode> selectedNodes = resolveSelectedNodes(tree, selection.getNodeIds());
            
            if (selectedNodes.isEmpty()) {
                selectedNodes = fallbackSelectNodes(query, tree);
                selection.setNodeIds(selectedNodes.stream()
                        .map(TreeNode::getNodeId)
                        .collect(Collectors.toList()));
                selection.setReasoning("Fallback local keyword selection was used because no valid node IDs were returned.");
            }
            
            String extractedText = extractTextForNodes(selectedNodes, pages);
            if (extractedText == null || extractedText.trim().isEmpty()) {
                logger.warn("Selected nodes produced empty text, falling back to full document text");
                extractedText = joinAllPages(pages);
            }
            
            String answer = synthesizeAnswer(query, extractedText, selectedNodes);
            if (answer == null || answer.trim().isEmpty() || answer.equals("Error") || answer.startsWith("Error:")) {
                answer = buildFallbackAnswer(selectedNodes);
            }
            
            int fullTextCharCount = pages.stream()
                    .map(PageContent::getText)
                    .filter(text -> text != null)
                    .mapToInt(String::length)
                    .sum();
            int extractedTextCharCount = extractedText.length();
            int fullTextTokenCount = pages.stream().mapToInt(PageContent::getTokenCount).sum();
            int extractedTextTokenCount = TokenCounter.countTokens(extractedText, config.getModel());
            
            PageIndex.RetrievalResult result = new PageIndex.RetrievalResult();
            result.setQuery(query);
            result.setDocName(docName);
            result.setSelectedNodeIds(selectedNodes.stream()
                    .map(TreeNode::getNodeId)
                    .collect(Collectors.toList()));
            result.setSourceSections(selectedNodes.stream()
                    .map(this::formatSourceSection)
                    .collect(Collectors.toList()));
            result.setSelectionReasoning(selection.getReasoning());
            result.setRawNodeSelectionResponse(selection.getRawResponse());
            result.setExtractedText(extractedText);
            result.setAnswer(answer);
            result.setFullTextCharCount(fullTextCharCount);
            result.setExtractedTextCharCount(extractedTextCharCount);
            result.setFullTextTokenCount(fullTextTokenCount);
            result.setExtractedTextTokenCount(extractedTextTokenCount);
            result.setCharReductionRatio(calculateReduction(fullTextCharCount, extractedTextCharCount));
            result.setTokenReductionRatio(calculateReduction(fullTextTokenCount, extractedTextTokenCount));
            result.setElapsedMs(System.currentTimeMillis() - startTime);
            
            logger.info("PageSearch query '{}': nodes={}, chars {}/{} (reduction {}%)",
                    query,
                    result.getSelectedNodeIds(),
                    extractedTextCharCount,
                    fullTextCharCount,
                    String.format("%.1f", result.getCharReductionRatio() * 100));
            
            return result;
        });
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
    
    private boolean detectDocumentLanguage(List<PageContent> pages) {
        int checkPages = Math.min(5, pages.size());
        int chineseCharCount = 0;
        int totalCharCount = 0;
        
        for (int i = 0; i < checkPages; i++) {
            String text = pages.get(i).getText();
            if (text != null && !text.isEmpty()) {
                totalCharCount += text.length();
                for (char c : text.toCharArray()) {
                    if (c >= 0x4e00 && c <= 0x9fa5) {
                        chineseCharCount++;
                    }
                }
            }
        }
        
        return totalCharCount > 0 && (chineseCharCount * 100.0 / totalCharCount) > 5.0;
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
}
