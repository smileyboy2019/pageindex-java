package com.pageindex.parser;

import com.pageindex.model.PageContent;
import com.pageindex.utils.TokenCounter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDPageLabels;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/**
 * 文档解析器，支持 PDF 和 Word 文档
 */
public class DocumentParser {
    private static final Logger logger = LoggerFactory.getLogger(DocumentParser.class);
    private final String model;
    private final int maxPageNumEachNode;
    private final int maxTokenNumEachNode;
    
    public DocumentParser(String model, int maxPageNumEachNode, int maxTokenNumEachNode) {
        this.model = model != null ? model : "gpt-4o-2024-11-20";
        this.maxPageNumEachNode = maxPageNumEachNode > 0 ? maxPageNumEachNode : 10;
        this.maxTokenNumEachNode = maxTokenNumEachNode > 0 ? maxTokenNumEachNode : 20000;
    }
    
    public DocumentParser(String model) {
        this(model, 10, 20000);
    }
    
    public DocumentParser() {
        this("gpt-4o-2024-11-20", 10, 20000);
    }
    
    /**
     * 解析文档（自动检测文件类型）
     */
    public List<PageContent> parseDocument(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("File not found: " + filePath);
        }
        
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".pdf")) {
            return parsePDF(filePath);
        } else if (fileName.endsWith(".docx")) {
            return parseWord(filePath);
        } else {
            throw new IOException("Unsupported file type: " + fileName);
        }
    }
    
    /**
     * 提取 PDF 书签/目录结构
     * @param filePath PDF 文件路径
     * @return TOC 项列表，如果没有书签则返回空列表
     */
    public List<com.pageindex.model.TOCItem> extractPDFBookmarks(String filePath) {
        List<com.pageindex.model.TOCItem> items = new ArrayList<>();
        
        File file = new File(filePath);
        if (!file.exists() || !file.getName().toLowerCase().endsWith(".pdf")) {
            return items;
        }
        
        try (PDDocument document = Loader.loadPDF(file)) {
            return extractPDFBookmarks(document);
        } catch (Exception e) {
            logger.warn("Failed to extract PDF bookmarks from {}: {}", filePath, e.getMessage());
            return items;
        }
    }
    
    /**
     * 解析 PDF 文件
     */
    public List<PageContent> parsePDF(String pdfPath) throws IOException {
        File file = new File(pdfPath);
        if (!file.exists()) {
            throw new IOException("PDF file not found: " + pdfPath);
        }
        
        try (PDDocument document = Loader.loadPDF(file)) {
            return parsePDFDocument(document);
        }
    }
    
    /**
     * 解析 Word 文档
     */
    public List<PageContent> parseWord(String wordPath) throws IOException {
        File file = new File(wordPath);
        if (!file.exists()) {
            throw new IOException("Word file not found: " + wordPath);
        }
        
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {
            
            return parseWordDocument(document);
        }
    }
    
    /**
     * 解析 Word 文档内容
     */
    private List<PageContent> parseWordDocument(XWPFDocument document) throws IOException {
        List<PageContent> pages = new ArrayList<>();
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        logger.info("Parsing Word document with {} paragraphs", paragraphs.size());
        
        // 提取所有图片（文档级别的）
        List<XWPFPictureData> allPictures = document.getAllPictures();
        logger.info("Found {} images in Word document", allPictures.size());
        
        // 按照配置的max_page_num_each_node和max_token_num_each_node来分页
        // 这样可以与PDF的分页逻辑保持一致，后续的树结构构建逻辑也统一
        StringBuilder currentPageText = new StringBuilder();
        List<PageContent.ImageInfo> currentPageImages = new ArrayList<>();
        List<PageContent.HeadingInfo> currentPageHeadings = new ArrayList<>();
        int currentPageNumber = 1;
        int currentTokenCount = 0;
        int currentParagraphCount = 0;
        int paragraphIndex = 0;
        
        for (XWPFParagraph paragraph : paragraphs) {
            String text = paragraph.getText();
            List<PageContent.ImageInfo> paragraphImages = extractImagesFromParagraph(paragraph, document);
            
            // 检查段落样式，识别标题
            String style = paragraph.getStyle();
            String styleID = paragraph.getStyleID(); // 获取样式ID
            boolean isHeading = false;
            int headingLevel = 0;
            
            // 方法1：检查 styleID 是否为数字（Word中样式ID "1" = Heading 1, "2" = Heading 2）
            if (styleID != null && !styleID.isEmpty()) {
                try {
                    // 尝试将 styleID 解析为数字
                    int styleNum = Integer.parseInt(styleID);
                    if (styleNum >= 1 && styleNum <= 9) {
                        // Word中样式ID 1-9 通常对应 Heading 1-9
                        isHeading = true;
                        headingLevel = styleNum;
                        logger.info("Found heading by styleID number: styleID={}, level={}, text={}", 
                                styleID, headingLevel, text.trim().substring(0, Math.min(50, text.length())));
                    }
                } catch (NumberFormatException e) {
                    // styleID 不是数字，尝试字符串匹配
                    String styleIDLower = styleID.toLowerCase();
                    if (styleIDLower.startsWith("heading") || styleIDLower.startsWith("title")) {
                        isHeading = true;
                        // 提取标题级别（如 Heading1 -> level 1, Heading2 -> level 2）
                        Pattern levelPattern = Pattern.compile("heading(\\d+)", Pattern.CASE_INSENSITIVE);
                        Matcher levelMatcher = levelPattern.matcher(styleIDLower);
                        if (levelMatcher.find()) {
                            headingLevel = Integer.parseInt(levelMatcher.group(1));
                        } else {
                            headingLevel = 1; // 默认一级
                        }
                        logger.info("Found heading by styleID string: styleID={}, level={}, text={}", 
                                styleID, headingLevel, text.trim().substring(0, Math.min(50, text.length())));
                    }
                }
            }
            
            // 方法2：如果 styleID 没有找到标题，尝试使用 style 名称
            if (!isHeading && style != null && !style.isEmpty()) {
                try {
                    // 尝试将 style 解析为数字
                    int styleNum = Integer.parseInt(style);
                    if (styleNum >= 1 && styleNum <= 9) {
                        isHeading = true;
                        headingLevel = styleNum;
                        logger.info("Found heading by style number: style={}, level={}, text={}", 
                                style, headingLevel, text.trim().substring(0, Math.min(50, text.length())));
                    }
                } catch (NumberFormatException e) {
                    // style 不是数字，尝试字符串匹配
                    String styleLower = style.toLowerCase();
                    if (styleLower.contains("heading") || styleLower.contains("title")) {
                        isHeading = true;
                        // 提取标题级别（如 Heading 1 -> level 1）
                        Pattern levelPattern = Pattern.compile("heading\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
                        Matcher levelMatcher = levelPattern.matcher(styleLower);
                        if (levelMatcher.find()) {
                            headingLevel = Integer.parseInt(levelMatcher.group(1));
                        } else {
                            headingLevel = 1; // 默认一级
                        }
                        logger.info("Found heading by style string: style={}, level={}, text={}", 
                                style, headingLevel, text.trim().substring(0, Math.min(50, text.length())));
                    }
                }
            }
            
            // 调试：记录所有段落的样式信息（前30个段落）
            if (paragraphIndex < 30 && text != null && !text.trim().isEmpty()) {
                logger.info("Paragraph {}: styleID={}, style={}, text={}", 
                    paragraphIndex, styleID != null ? styleID : "null", 
                    style != null ? style : "null",
                    text.trim().substring(0, Math.min(50, text.length())));
            }
            
            if (text != null && !text.trim().isEmpty()) {
                int textTokenCount = TokenCounter.countTokens(text, model);
                
                // 检查是否需要创建新页：
                // 1. token数量超过限制
                // 2. 段落数量超过限制（模拟页面数量）
                boolean exceedsTokenLimit = currentTokenCount + textTokenCount > maxTokenNumEachNode;
                boolean exceedsPageLimit = currentParagraphCount >= maxPageNumEachNode;
                
                if ((exceedsTokenLimit || exceedsPageLimit) && currentPageText.length() > 0) {
                    // 保存当前页
                    pages.add(new PageContent(
                        currentPageText.toString(), 
                        currentTokenCount, 
                        new ArrayList<>(currentPageImages),
                        currentPageNumber,
                        new ArrayList<>(currentPageHeadings)
                    ));
                    
                    // 重置
                    currentPageText.setLength(0);
                    currentPageImages.clear();
                    currentPageHeadings.clear();
                    currentTokenCount = 0;
                    currentParagraphCount = 0;
                    currentPageNumber++;
                }
                
                currentPageText.append(text).append("\n");
                currentTokenCount += textTokenCount;
                currentParagraphCount++;
                
                // 如果是标题，记录标题信息
                if (isHeading && text.trim().length() > 0 && text.trim().length() < 200) {
                    currentPageHeadings.add(new PageContent.HeadingInfo(text.trim(), headingLevel, paragraphIndex));
                }
                
                // 添加段落中的图片
                if (!paragraphImages.isEmpty()) {
                    currentPageImages.addAll(paragraphImages);
                }
                
                paragraphIndex++;
            } else if (!paragraphImages.isEmpty()) {
                // 即使段落没有文本，如果有图片也要添加
                currentPageImages.addAll(paragraphImages);
                // 空段落也计入段落数，避免图片段落被忽略
                currentParagraphCount++;
            }
        }
        
        // 添加最后一页
        if (currentPageText.length() > 0 || !currentPageImages.isEmpty()) {
            pages.add(new PageContent(
                currentPageText.toString(), 
                currentTokenCount, 
                new ArrayList<>(currentPageImages),
                currentPageNumber,
                new ArrayList<>(currentPageHeadings)
            ));
        }
        
        logger.info("Successfully parsed Word document into {} pages", pages.size());
        return pages;
    }
    
    /**
     * 从Word段落中提取图片
     */
    private List<PageContent.ImageInfo> extractImagesFromParagraph(XWPFParagraph paragraph, XWPFDocument document) {
        List<PageContent.ImageInfo> images = new ArrayList<>();
        
        try {
            List<XWPFRun> runs = paragraph.getRuns();
            for (XWPFRun run : runs) {
                // 检查run中是否有图片
                if (run.getEmbeddedPictures() != null && !run.getEmbeddedPictures().isEmpty()) {
                    for (XWPFPicture picture : run.getEmbeddedPictures()) {
                        try {
                            XWPFPictureData pictureData = picture.getPictureData();
                            if (pictureData != null) {
                                byte[] imageData = pictureData.getData();
                                String imageFormat = pictureData.suggestFileExtension();
                                if (imageFormat == null || imageFormat.isEmpty()) {
                                    imageFormat = "png";
                                }
                                
                                // 获取图片尺寸（如果可用）
                                int width = 0;
                                int height = 0;
                                try {
                                    // 尝试从图片数据获取尺寸
                                    BufferedImage bufferedImage = ImageIO.read(new java.io.ByteArrayInputStream(imageData));
                                    if (bufferedImage != null) {
                                        width = bufferedImage.getWidth();
                                        height = bufferedImage.getHeight();
                                        
                                        // 过滤太小的图片
                                        if (width >= 50 && height >= 50) {
                                            PageContent.ImageInfo imageInfo = new PageContent.ImageInfo(
                                                    imageData, imageFormat, width, height);
                                            images.add(imageInfo);
                                            logger.debug("Extracted image from Word paragraph: {}x{} ({})", 
                                                    width, height, imageFormat);
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.debug("Could not determine image size, using default: {}", e.getMessage());
                                    // 即使无法获取尺寸，也添加图片（可能是格式不支持）
                                    if (imageData.length > 1000) { // 至少1KB
                                        PageContent.ImageInfo imageInfo = new PageContent.ImageInfo(
                                                imageData, imageFormat, 0, 0);
                                        images.add(imageInfo);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to extract image from Word paragraph", e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error extracting images from Word paragraph", e);
        }
        
        return images;
    }
    
    /**
     * 解析 PDF 文档
     */
    private List<PageContent> parsePDFDocument(PDDocument document) throws IOException {
        List<PageContent> pages = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        
        int totalPages = document.getNumberOfPages();
        logger.info("Parsing PDF with {} pages", totalPages);
        
            // PDF 书签提取将在 TreeParser 中处理
        
        for (int i = 1; i <= totalPages; i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String pageText = stripper.getText(document);
            int tokenCount = TokenCounter.countTokens(pageText, model);
            
            // 提取页面中的图片
            List<PageContent.ImageInfo> images = extractImagesFromPage(document, i);
            
            pages.add(new PageContent(pageText, tokenCount, images, i));
        }
        
        logger.info("Successfully parsed {} pages", pages.size());
        return pages;
    }
    
    /**
     * 提取 PDF 书签/目录结构并返回 TOC 项列表
     */
    public List<com.pageindex.model.TOCItem> extractPDFBookmarks(PDDocument document) {
        List<com.pageindex.model.TOCItem> items = new ArrayList<>();
        
        try {
            PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
            if (outline == null) {
                logger.info("PDF has no bookmarks/outlines");
                return items;
            }
            
            logger.info("PDF has bookmarks/outlines, extracting structure...");
            PDOutlineItem current = outline.getFirstChild();
            int[] levelCounters = new int[10]; // 支持10级嵌套
            
            extractBookmarkRecursive(current, document, items, levelCounters, 0);
            
            logger.info("Extracted {} bookmarks from PDF", items.size());
        } catch (Exception e) {
            logger.warn("Failed to extract PDF bookmarks: {}", e.getMessage());
        }
        
        return items;
    }
    
    /**
     * 递归提取书签
     */
    private void extractBookmarkRecursive(PDOutlineItem item, PDDocument document, 
                                         List<com.pageindex.model.TOCItem> items, 
                                         int[] levelCounters, int currentLevel) {
        if (item == null) {
            return;
        }
        
        try {
            String title = item.getTitle();
            
            // 获取书签指向的页码
            Integer pageNumber = getBookmarkPageNumber(item, document);
            
            // 根据层级更新计数器并构建结构编号
            for (int i = currentLevel; i < levelCounters.length; i++) {
                if (i == currentLevel) {
                    levelCounters[i]++;
                } else {
                    levelCounters[i] = 0; // 重置下级计数器
                }
            }
            
            // 构建结构编号（如 1, 1.1, 1.1.1）
            StringBuilder structure = new StringBuilder();
            for (int i = 0; i <= currentLevel && i < levelCounters.length; i++) {
                if (i > 0) {
                    structure.append(".");
                }
                structure.append(levelCounters[i]);
            }
            
            com.pageindex.model.TOCItem tocItem = new com.pageindex.model.TOCItem();
            tocItem.setTitle(title);
            tocItem.setStructure(structure.toString());
            tocItem.setPhysicalIndex(pageNumber);
            items.add(tocItem);
            
            logger.info("Found PDF bookmark: '{}' (level: {}, structure: {}, page: {})", 
                    title, currentLevel, structure.toString(), pageNumber != null ? pageNumber : "unknown");
            
            // 递归处理子书签
            PDOutlineItem child = item.getFirstChild();
            if (child != null) {
                extractBookmarkRecursive(child, document, items, levelCounters, currentLevel + 1);
            }
            
            // 处理同级书签
            PDOutlineItem sibling = item.getNextSibling();
            if (sibling != null) {
                extractBookmarkRecursive(sibling, document, items, levelCounters, currentLevel);
            }
        } catch (Exception e) {
            logger.warn("Error extracting bookmark: {}", e.getMessage());
        }
    }
    
    /**
     * 获取书签指向的页码
     */
    private Integer getBookmarkPageNumber(PDOutlineItem item, PDDocument document) {
        try {
            org.apache.pdfbox.pdmodel.interactive.action.PDAction action = item.getAction();
            if (action instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo) {
                org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo goTo = 
                    (org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo) action;
                org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination destination = 
                    goTo.getDestination();
                if (destination instanceof org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination) {
                    org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination pageDest = 
                        (org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination) destination;
                    PDPage page = pageDest.getPage();
                    if (page != null) {
                        // PDFBox 使用 0-based 索引，需要 +1
                        return document.getPages().indexOf(page) + 1;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to get bookmark page number: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 从PDF页面提取图片
     */
    private List<PageContent.ImageInfo> extractImagesFromPage(PDDocument document, int pageNumber) {
        List<PageContent.ImageInfo> images = new ArrayList<>();
        
        try {
            PDPage page = document.getPage(pageNumber - 1); // PDFBox使用0-based索引
            PDResources resources = page.getResources();
            
            if (resources == null) {
                return images;
            }
            
            // 遍历页面资源中的所有对象
            for (org.apache.pdfbox.cos.COSName name : resources.getXObjectNames()) {
                PDXObject xObject = resources.getXObject(name);
                
                if (xObject instanceof PDImageXObject) {
                    PDImageXObject image = (PDImageXObject) xObject;
                    
                    try {
                        // 获取图片格式
                        String imageFormat = image.getSuffix();
                        if (imageFormat == null || imageFormat.isEmpty()) {
                            imageFormat = "PNG"; // 默认格式
                        }
                        
                        // 获取图片尺寸
                        int width = image.getWidth();
                        int height = image.getHeight();
                        
                        // 过滤太小的图片（可能是装饰性图标）
                        if (width >= 50 && height >= 50) {
                            // 获取图片数据 - PDFBox 3.0.0使用getImage()方法
                            java.awt.image.BufferedImage bufferedImage = image.getImage();
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(bufferedImage, imageFormat, baos);
                            byte[] imageData = baos.toByteArray();
                            
                            PageContent.ImageInfo imageInfo = new PageContent.ImageInfo(
                                    imageData, imageFormat, width, height);
                            images.add(imageInfo);
                            logger.debug("Extracted image from page {}: {}x{} ({})", 
                                    pageNumber, width, height, imageFormat);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to extract image from page {}", pageNumber, e);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to extract images from page {}", pageNumber, e);
        }
        
        return images;
    }
}
