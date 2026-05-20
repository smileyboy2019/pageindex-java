package com.pageindex.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * 表示文档树结构中的节点
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TreeNode {
    private String title;
    private Integer startIndex;
    private Integer endIndex;
    private String nodeId;
    private String parentNodeId; // 父节点ID
    private String summary;
    private String text;
    private List<TreeNode> nodes;
    private List<ImageDescription> images; // 图片识别结果
    
    public TreeNode() {
        this.nodes = new ArrayList<>();
    }
    
    public TreeNode(String title, Integer startIndex, Integer endIndex) {
        this();
        this.title = title;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
    }
    
    // Getters and Setters
    public String getTitle() {
        return title;
    }
    
    public void setTitle(String title) {
        this.title = title;
    }
    
    public Integer getStartIndex() {
        return startIndex;
    }
    
    public void setStartIndex(Integer startIndex) {
        this.startIndex = startIndex;
    }
    
    public Integer getEndIndex() {
        return endIndex;
    }
    
    public void setEndIndex(Integer endIndex) {
        this.endIndex = endIndex;
    }
    
    public String getNodeId() {
        return nodeId;
    }
    
    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }
    
    public String getParentNodeId() {
        return parentNodeId;
    }
    
    public void setParentNodeId(String parentNodeId) {
        this.parentNodeId = parentNodeId;
    }
    
    public String getSummary() {
        return summary;
    }
    
    public void setSummary(String summary) {
        this.summary = summary;
    }
    
    public String getText() {
        return text;
    }
    
    public void setText(String text) {
        this.text = text;
    }
    
    public List<TreeNode> getNodes() {
        return nodes;
    }
    
    public void setNodes(List<TreeNode> nodes) {
        this.nodes = nodes;
    }
    
    public void addChild(TreeNode child) {
        if (this.nodes == null) {
            this.nodes = new ArrayList<>();
        }
        this.nodes.add(child);
    }
    
    public boolean hasChildren() {
        return nodes != null && !nodes.isEmpty();
    }
    
    public List<ImageDescription> getImages() {
        return images;
    }
    
    public void setImages(List<ImageDescription> images) {
        this.images = images;
    }
    
    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }
    
    /**
     * 图片描述信息
     */
    public static class ImageDescription {
        private int pageNumber;
        private String description;
        private int width;
        private int height;
        private String format;
        
        public ImageDescription(int pageNumber, String description, int width, int height, String format) {
            this.pageNumber = pageNumber;
            this.description = description;
            this.width = width;
            this.height = height;
            this.format = format;
        }
        
        public int getPageNumber() {
            return pageNumber;
        }
        
        public void setPageNumber(int pageNumber) {
            this.pageNumber = pageNumber;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public int getWidth() {
            return width;
        }
        
        public void setWidth(int width) {
            this.width = width;
        }
        
        public int getHeight() {
            return height;
        }
        
        public void setHeight(int height) {
            this.height = height;
        }
        
        public String getFormat() {
            return format;
        }
        
        public void setFormat(String format) {
            this.format = format;
        }
    }
}
