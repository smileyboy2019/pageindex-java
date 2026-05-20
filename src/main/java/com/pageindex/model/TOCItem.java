package com.pageindex.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 表示目录项（Table of Contents Item）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TOCItem {
    private String structure;
    private String title;
    private Integer physicalIndex;
    private Integer page;
    private String start;
    
    public TOCItem() {
    }
    
    public TOCItem(String structure, String title, Integer physicalIndex) {
        this.structure = structure;
        this.title = title;
        this.physicalIndex = physicalIndex;
    }
    
    // Getters and Setters
    public String getStructure() {
        return structure;
    }
    
    public void setStructure(String structure) {
        this.structure = structure;
    }
    
    public String getTitle() {
        return title;
    }
    
    public void setTitle(String title) {
        this.title = title;
    }
    
    public Integer getPhysicalIndex() {
        return physicalIndex;
    }
    
    public void setPhysicalIndex(Integer physicalIndex) {
        this.physicalIndex = physicalIndex;
    }
    
    public Integer getPage() {
        return page;
    }
    
    public void setPage(Integer page) {
        this.page = page;
    }
    
    public String getStart() {
        return start;
    }
    
    public void setStart(String start) {
        this.start = start;
    }
}
