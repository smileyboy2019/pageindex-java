package com.pageindex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pageindex.model.Config;
import com.pageindex.utils.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * PageIndex 命令行入口
 */
public class PageIndexMain {
    private static final Logger logger = LoggerFactory.getLogger(PageIndexMain.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        
        String filePath = null;
        String configPath = null;
        String outputPath = "./results";
        String query = null;
        Boolean includeNodeText = null; // null表示使用配置文件中的值
        
        // 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--pdf_path":
                case "--file_path":
                    if (i + 1 < args.length) {
                        filePath = args[++i];
                    }
                    break;
                case "--config":
                    if (i + 1 < args.length) {
                        configPath = args[++i];
                    }
                    break;
                case "--output":
                    if (i + 1 < args.length) {
                        outputPath = args[++i];
                    }
                    break;
                case "--query":
                case "--search":
                    if (i + 1 < args.length) {
                        query = args[++i];
                    }
                    break;
                case "--include_node_text":
                case "--include-detail":
                    if (i + 1 < args.length) {
                        String value = args[++i];
                        includeNodeText = "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value);
                    } else {
                        includeNodeText = true; // 如果只有参数没有值，默认为true
                    }
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return;
            }
        }
        
        if (filePath == null) {
            System.err.println("Error: --pdf_path or --file_path is required");
            printUsage();
            return;
        }
        
        try {
            // 确保输出目录存在（日志和JSON都保存在这里）
            File outputDir = new File(outputPath);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            // 设置日志目录为输出目录
            System.setProperty("pageindex.log.dir", outputDir.getAbsolutePath());
            
            // 重新初始化日志配置（使新的日志路径生效）
            ch.qos.logback.classic.LoggerContext loggerContext = 
                    (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
            loggerContext.reset();
            ch.qos.logback.classic.joran.JoranConfigurator configurator = 
                    new ch.qos.logback.classic.joran.JoranConfigurator();
            configurator.setContext(loggerContext);
            try {
                java.io.InputStream configStream = PageIndexMain.class.getClassLoader()
                        .getResourceAsStream("logback.xml");
                if (configStream != null) {
                    configurator.doConfigure(configStream);
                    configStream.close();
                }
            } catch (Exception e) {
                System.err.println("Failed to reload logback configuration: " + e.getMessage());
            }
            
            logger.info("==========================================");
            logger.info("PageIndex Java - Starting document processing");
            logger.info("Output directory: {}", outputDir.getAbsolutePath());
            logger.info("Log file: {}/pageindex.log", outputDir.getAbsolutePath());
            logger.info("Error log file: {}/pageindex-error.log", outputDir.getAbsolutePath());
            logger.info("==========================================");
            
            // 加载配置
            Config config = configPath != null 
                    ? ConfigLoader.loadConfig(configPath)
                    : ConfigLoader.loadDefaultConfig();
            
            logger.info("Configuration loaded: model={}, provider={}", 
                    config.getModel(), config.getModelProvider());
            
            // 如果命令行指定了include_node_text，覆盖配置
            if (includeNodeText != null) {
                config.setIfAddNodeText(includeNodeText ? "yes" : "no");
            }
            
            // 创建 PageIndex 实例
            PageIndex pageIndex = new PageIndex(config);
            
            // 构建索引
            System.out.println("Processing document: " + filePath);
            logger.info("Processing document: {}", filePath);
            CompletableFuture<PageIndex.IndexResult> future = pageIndex.buildIndex(filePath);
            
            PageIndex.IndexResult result = future.join();
            
            // 保存结果
            
            String fileName = new File(filePath).getName();
            String outputFileName = fileName.replaceAll("\\.(pdf|docx)$", "") + "_structure.json";
            File outputFile = new File(outputDir, outputFileName);
            
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, result);
            
            System.out.println("Tree structure saved to: " + outputFile.getAbsolutePath());
            logger.info("Tree structure saved to: {}", outputFile.getAbsolutePath());
            
            if (query != null && !query.trim().isEmpty()) {
                logger.info("Running PageSearch query: {}", query);
                PageSearch pageSearch = new PageSearch(config);
                PageIndex.RetrievalResult searchResult = pageSearch.search(filePath, query, result).join();
                
                String searchOutputFileName = fileName.replaceAll("\\.(pdf|docx)$", "") + "_search.json";
                File searchOutputFile = new File(outputDir, searchOutputFileName);
                mapper.writerWithDefaultPrettyPrinter().writeValue(searchOutputFile, searchResult);
                
                System.out.println();
                System.out.println("Search answer:");
                System.out.println(searchResult.getAnswer());
                System.out.println();
                System.out.println("Selected nodes: " + searchResult.getSelectedNodeIds());
                System.out.println("Source sections: " + searchResult.getSourceSections());
                System.out.println(String.format("Chars sent to LLM: %d / %d (reduction %.1f%%)",
                        searchResult.getExtractedTextCharCount(),
                        searchResult.getFullTextCharCount(),
                        searchResult.getCharReductionRatio() * 100));
                System.out.println("Search result saved to: " + searchOutputFile.getAbsolutePath());
                
                logger.info("Search result saved to: {}", searchOutputFile.getAbsolutePath());
            }
            
            logger.info("==========================================");
            logger.info("Document processing completed successfully");
            logger.info("==========================================");
            
        } catch (Exception e) {
            logger.error("Failed to process document", e);
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void printUsage() {
        System.out.println("PageIndex Java - A vectorless, reasoning-based RAG system");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar pageindex-java.jar --pdf_path <path> [options]");
        System.out.println("  java -jar pageindex-java.jar --file_path <path> [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --pdf_path <path>    Path to the PDF file (supported)");
        System.out.println("  --file_path <path>   Path to the document file (PDF or Word .docx)");
        System.out.println("  --config <path>     Path to config.yaml (optional, defaults to classpath)");
        System.out.println("  --output <path>     Output directory (default: ./results)");
        System.out.println("  --query <question>  Run PageSearch after indexing and save observable search result");
        System.out.println("  --search <question> Alias for --query");
        System.out.println("  --include_node_text <yes|no>  Include detailed text content for each node (default: use config)");
        System.out.println("  --include-detail <yes|no>     Alias for --include_node_text");
        System.out.println("  --help, -h          Show this help message");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -jar pageindex-java.jar --pdf_path document.pdf");
        System.out.println("  java -jar pageindex-java.jar --file_path document.docx");
        System.out.println("  java -jar pageindex-java.jar --file_path document.docx --query \"What is the main risk?\"");
        System.out.println("  java -jar pageindex-java.jar --file_path document.docx --include_node_text yes");
        System.out.println("  java -jar pageindex-java.jar --file_path document.docx --config custom-config.yaml --output ./output");
    }
}
