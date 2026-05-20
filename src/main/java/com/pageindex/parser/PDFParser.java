package com.pageindex.parser;

import com.pageindex.model.PageContent;
import com.pageindex.utils.TokenCounter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析器，用于提取 PDF 页面的文本内容和 token 数量
 */
public class PDFParser {
    private static final Logger logger = LoggerFactory.getLogger(PDFParser.class);
    private final String model;
    
    public PDFParser(String model) {
        this.model = model != null ? model : "gpt-4o-2024-11-20";
    }
    
    public PDFParser() {
        this("gpt-4o-2024-11-20");
    }
    
    /**
     * 从文件路径解析 PDF
     */
    public List<PageContent> parsePDF(String pdfPath) throws IOException {
        File file = new File(pdfPath);
        if (!file.exists()) {
            throw new IOException("PDF file not found: " + pdfPath);
        }
        
        try (PDDocument document = Loader.loadPDF(file)) {
            return parseDocument(document);
        }
    }
    
    /**
     * 从 InputStream 解析 PDF
     */
    public List<PageContent> parsePDF(InputStream inputStream) throws IOException {
        byte[] pdfBytes = inputStream.readAllBytes();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return parseDocument(document);
        }
    }
    
    /**
     * 解析 PDDocument
     */
    private List<PageContent> parseDocument(PDDocument document) throws IOException {
        List<PageContent> pages = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        
        int totalPages = document.getNumberOfPages();
        logger.info("Parsing PDF with {} pages", totalPages);
        
        for (int i = 1; i <= totalPages; i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String pageText = stripper.getText(document);
            int tokenCount = TokenCounter.countTokens(pageText, model);
            pages.add(new PageContent(pageText, tokenCount));
        }
        
        logger.info("Successfully parsed {} pages", pages.size());
        return pages;
    }
    
    /**
     * 获取 PDF 总页数
     */
    public int getPageCount(String pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            return document.getNumberOfPages();
        }
    }
}
