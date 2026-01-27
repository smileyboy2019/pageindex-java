package com.pageindex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pageindex.llm.LLMClient;
import com.pageindex.llm.LLMClientFactory;
import com.pageindex.model.Config;
import com.pageindex.model.PageContent;
import com.pageindex.model.TreeNode;
import com.pageindex.parser.PDFParser;
import com.pageindex.tree.TreeBuilder;
import com.pageindex.tree.TreeParser;
import com.pageindex.utils.ConfigLoader;
import com.pageindex.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * PageIndex 主类，提供文档索引构建功能
 */
public class PageIndex {
    private static final Logger logger = LoggerFactory.getLogger(PageIndex.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final Config config;
    private final LLMClient llmClient;
    private final PDFParser pdfParser;
    private final TreeParser treeParser;
    
    public PageIndex(Config config) {
        this.config = config;
        this.llmClient = LLMClientFactory.createClient(config);
        this.pdfParser = new PDFParser(config.getModel());
        this.treeParser = new TreeParser(config, llmClient);
    }
    
    public PageIndex() {
        this(ConfigLoader.loadDefaultConfig());
    }
    
    /**
     * 从 PDF 文件构建索引
     */
    public CompletableFuture<IndexResult> buildIndex(String pdfPath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Building index for PDF: {}", pdfPath);
                
                // 解析 PDF
                List<PageContent> pages = pdfParser.parsePDF(pdfPath);
                logger.info("Parsed {} pages, total tokens: {}", 
                        pages.size(), 
                        pages.stream().mapToInt(PageContent::getTokenCount).sum());
                
                // 构建树结构
                List<TreeNode> tree = treeParser.parseTreeFromPages(pages).join();
                
                // 添加节点文本（如果需要）
                if ("yes".equals(config.getIfAddNodeText())) {
                    addNodeText(tree, pages);
                }
                
                // 生成摘要（如果需要）
                if ("yes".equals(config.getIfAddNodeSummary())) {
                    if (!"yes".equals(config.getIfAddNodeText())) {
                        addNodeText(tree, pages);
                    }
                    generateSummaries(tree).join();
                    if (!"yes".equals(config.getIfAddNodeText())) {
                        removeNodeText(tree);
                    }
                }
                
                // 构建结果
                IndexResult result = new IndexResult();
                result.setDocName(new File(pdfPath).getName());
                result.setStructure(tree);
                
                // 生成文档描述（如果需要）
                if ("yes".equals(config.getIfAddDocDescription())) {
                    String description = generateDocDescription(tree);
                    result.setDocDescription(description);
                }
                
                logger.info("Successfully built index for: {}", pdfPath);
                return result;
                
            } catch (Exception e) {
                logger.error("Failed to build index for: " + pdfPath, e);
                throw new RuntimeException(e);
            }
        });
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
     * 移除树节点的文本内容
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
     * 为树节点生成摘要
     */
    private CompletableFuture<Void> generateSummaries(List<TreeNode> nodes) {
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        
        for (TreeNode node : nodes) {
            if (node.getText() != null && !node.getText().isEmpty()) {
                CompletableFuture<Void> future = llmClient.callAsync(config.getModel(), 
                        buildSummaryPrompt(node.getText()))
                        .thenAccept(summary -> node.setSummary(summary));
                futures.add(future);
            }
            
            if (node.hasChildren()) {
                futures.add(generateSummaries(node.getNodes()));
            }
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
    
    private String buildSummaryPrompt(String text) {
        return String.format("""
            You are given a part of a document, your task is to generate a description of the partial document 
            about what are main points covered in the partial document.
            
            Partial Document Text: %s
            
            Directly return the description, do not include any other text.
            """, text);
    }
    
    /**
     * 生成文档描述
     */
    private String generateDocDescription(List<TreeNode> tree) {
        String structureJson = JsonUtils.toJsonString(tree);
        String prompt = String.format("""
            You are an expert in generating descriptions for a document.
            You are given a structure of a document. Your task is to generate a one-sentence description for the document, 
            which makes it easy to distinguish the document from other documents.
            
            Document Structure: %s
            
            Directly return the description, do not include any other text.
            """, structureJson);
        
        return llmClient.call(config.getModel(), prompt, null);
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
