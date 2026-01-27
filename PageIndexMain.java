package com.pageindex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pageindex.model.Config;
import com.pageindex.utils.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
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
        
        String pdfPath = null;
        String configPath = null;
        String outputPath = "./results";
        
        // 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--pdf_path":
                    if (i + 1 < args.length) {
                        pdfPath = args[++i];
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
                case "--help":
                case "-h":
                    printUsage();
                    return;
            }
        }
        
        if (pdfPath == null) {
            System.err.println("Error: --pdf_path is required");
            printUsage();
            return;
        }
        
        try {
            // 加载配置
            Config config = configPath != null 
                    ? ConfigLoader.loadConfig(configPath)
                    : ConfigLoader.loadDefaultConfig();
            
            // 创建 PageIndex 实例
            PageIndex pageIndex = new PageIndex(config);
            
            // 构建索引
            System.out.println("Processing PDF: " + pdfPath);
            CompletableFuture<PageIndex.IndexResult> future = pageIndex.buildIndex(pdfPath);
            
            PageIndex.IndexResult result = future.join();
            
            // 保存结果
            File outputDir = new File(outputPath);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            String pdfName = new File(pdfPath).getName();
            String outputFileName = pdfName.replaceAll("\\.pdf$", "") + "_structure.json";
            File outputFile = new File(outputDir, outputFileName);
            
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, result);
            
            System.out.println("Tree structure saved to: " + outputFile.getAbsolutePath());
            
        } catch (Exception e) {
            logger.error("Failed to process PDF", e);
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
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --pdf_path <path>    Path to the PDF file (required)");
        System.out.println("  --config <path>     Path to config.yaml (optional, defaults to classpath)");
        System.out.println("  --output <path>     Output directory (default: ./results)");
        System.out.println("  --help, -h          Show this help message");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -jar pageindex-java.jar --pdf_path document.pdf");
        System.out.println("  java -jar pageindex-java.jar --pdf_path document.pdf --config custom-config.yaml --output ./output");
    }
}
