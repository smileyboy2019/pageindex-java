package com.pageindex.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 表示 PDF 页面的内容和 token 数量
 */
public class PageContent {
    private final String text;
    private final int tokenCount;
    private final List<ImageInfo> images;
    private final int pageNumber;
    private final List<HeadingInfo> headings; // 页面中的标题信息
    
    public PageContent(String text, int tokenCount) {
        this(text, tokenCount, new ArrayList<>(), 0, new ArrayList<>());
    }
    
    public PageContent(String text, int tokenCount, List<ImageInfo> images, int pageNumber) {
        this(text, tokenCount, images, pageNumber, new ArrayList<>());
    }
    
    public PageContent(String text, int tokenCount, List<ImageInfo> images, int pageNumber, List<HeadingInfo> headings) {
        this.text = text;
        this.tokenCount = tokenCount;
        this.images = images != null ? images : new ArrayList<>();
        this.pageNumber = pageNumber;
        this.headings = headings != null ? headings : new ArrayList<>();
    }
    
    public String getText() {
        return text;
    }
    
    public int getTokenCount() {
        return tokenCount;
    }
    
    public List<ImageInfo> getImages() {
        return images;
    }
    
    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }
    
    public int getPageNumber() {
        return pageNumber;
    }
    
    public List<HeadingInfo> getHeadings() {
        return headings;
    }
    
    public boolean hasHeadings() {
        return headings != null && !headings.isEmpty();
    }
    
    /**
     * 图片信息
     */
    public static class ImageInfo {
        private final byte[] imageData;
        private final String imageFormat; // PNG, JPEG等
        private final int width;
        private final int height;
        private String description; // 多模态识别结果
        
        public ImageInfo(byte[] imageData, String imageFormat, int width, int height) {
            this.imageData = imageData;
            this.imageFormat = imageFormat;
            this.width = width;
            this.height = height;
        }
        
        public byte[] getImageData() {
            return imageData;
        }
        
        public String getImageFormat() {
            return imageFormat;
        }
        
        public int getWidth() {
            return width;
        }
        
        public int getHeight() {
            return height;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
    }
    
    /**
     * 标题信息
     */
    public static class HeadingInfo {
        private final String text;
        private final int level; // 1=一级标题，2=二级标题，以此类推
        private final int paragraphIndex; // 段落索引
        
        public HeadingInfo(String text, int level, int paragraphIndex) {
            this.text = text;
            this.level = level;
            this.paragraphIndex = paragraphIndex;
        }
        
        public String getText() {
            return text;
        }
        
        public int getLevel() {
            return level;
        }
        
        public int getParagraphIndex() {
            return paragraphIndex;
        }
    }
}
