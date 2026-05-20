package com.pageindex.tree;

import com.pageindex.model.TOCItem;
import com.pageindex.model.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 树构建器，将 TOC 项列表转换为树结构
 */
public class TreeBuilder {
    private static final Logger logger = LoggerFactory.getLogger(TreeBuilder.class);
    
    /**
     * 将 TOC 项列表转换为树结构（嵌套结构）
     * 
     * @param items TOC 项列表
     * @return 树节点列表（嵌套结构）
     */
    public static List<TreeNode> listToTree(List<TOCItem> items) {
        Map<String, TreeNode> nodes = new HashMap<>();
        List<TreeNode> rootNodes = new ArrayList<>();
        
        // 第一遍：创建所有节点，并设置初始页码范围
        for (int i = 0; i < items.size(); i++) {
            TOCItem item = items.get(i);
            TreeNode node = new TreeNode();
            node.setTitle(item.getTitle());
            
            Integer startPage = item.getPhysicalIndex();
            if (startPage == null || startPage <= 0) {
                startPage = i + 1; // 如果没有页码，使用索引+1
            }
            node.setStartIndex(startPage);
            
            // 设置结束页码：查找下一个同级节点或下一个父节点的同级节点
            Integer endPage = findEndPageForNode(items, i, item.getStructure());
            node.setEndIndex(endPage != null ? endPage : startPage);
            
            String structure = item.getStructure();
            if (structure != null) {
                nodes.put(structure, node);
            } else {
                // 如果没有结构代码，也添加到根节点列表
                rootNodes.add(node);
            }
        }
        
        // 第二遍：建立父子关系（嵌套结构）
        for (TOCItem item : items) {
            String structure = item.getStructure();
            if (structure == null) {
                continue;
            }
            
            TreeNode node = nodes.get(structure);
            String parentStructure = getParentStructure(structure);
            
            if (parentStructure != null && nodes.containsKey(parentStructure)) {
                // 有父节点，添加到父节点的子节点列表
                nodes.get(parentStructure).addChild(node);
            } else {
                // 没有父节点，是根节点
                rootNodes.add(node);
            }
        }
        
        // 清理空的子节点列表
        for (TreeNode node : nodes.values()) {
            if (node.getNodes() != null && node.getNodes().isEmpty()) {
                node.setNodes(null);
            }
        }
        
        return rootNodes;
    }
    
    /**
     * 为节点查找结束页码
     * 规则：
     * 1. 如果是子节点，查找下一个同级节点或下一个父节点的同级节点
     * 2. 如果是根节点，查找下一个同级节点
     */
    private static Integer findEndPageForNode(List<TOCItem> items, int currentIndex, String currentStructure) {
        if (currentStructure == null || currentStructure.isEmpty()) {
            // 没有结构代码，使用下一个节点的页码
            if (currentIndex + 1 < items.size()) {
                TOCItem nextItem = items.get(currentIndex + 1);
                if (nextItem.getPhysicalIndex() != null && nextItem.getPhysicalIndex() > 0) {
                    return nextItem.getPhysicalIndex() - 1;
                }
            }
            return null;
        }
        
        // 获取当前节点的层级
        int currentLevel = getLevelFromStructure(currentStructure);
        
        // 查找下一个同级节点或更高层级的节点
        for (int i = currentIndex + 1; i < items.size(); i++) {
            TOCItem nextItem = items.get(i);
            String nextStructure = nextItem.getStructure();
            
            if (nextStructure == null || nextStructure.isEmpty()) {
                continue;
            }
            
            int nextLevel = getLevelFromStructure(nextStructure);
            
            // 如果找到同级或更高层级的节点，使用它的起始页码-1作为结束页码
            if (nextLevel <= currentLevel) {
                if (nextItem.getPhysicalIndex() != null && nextItem.getPhysicalIndex() > 0) {
                    Integer endPage = nextItem.getPhysicalIndex() - 1;
                    // 确保结束页码至少等于起始页码
                    TOCItem currentItem = items.get(currentIndex);
                    if (currentItem.getPhysicalIndex() != null && endPage < currentItem.getPhysicalIndex()) {
                        endPage = currentItem.getPhysicalIndex();
                    }
                    return endPage;
                }
            }
        }
        
        // 如果没有找到下一个同级或更高层级的节点，返回null（将在updatePageRanges中处理）
        return null;
    }
    
    /**
     * 从结构编号获取层级（0=一级，1=二级，2=三级...）
     */
    private static int getLevelFromStructure(String structure) {
        if (structure == null || structure.isEmpty()) {
            return 0;
        }
        return structure.split("\\.").length - 1;
    }
    
    /**
     * 获取父节点的结构代码
     * 例如：1.2.3 -> 1.2
     */
    private static String getParentStructure(String structure) {
        if (structure == null || structure.isEmpty()) {
            return null;
        }
        
        String[] parts = structure.split("\\.");
        if (parts.length <= 1) {
            return null;
        }
        
        return String.join(".", Arrays.copyOf(parts, parts.length - 1));
    }
    
    /**
     * 为树节点添加页码范围
     * 根据子节点的起始页码更新父节点的结束页码
     */
    public static void updatePageRanges(List<TreeNode> nodes, int totalPages) {
        updatePageRanges(nodes, totalPages, null);
    }
    
    /**
     * 为树节点添加页码范围（递归版本，支持父节点上下文）
     */
    private static void updatePageRanges(List<TreeNode> nodes, int totalPages, TreeNode parentNode) {
        for (int i = 0; i < nodes.size(); i++) {
            TreeNode node = nodes.get(i);
            
            if (node.hasChildren()) {
                // 有子节点：递归更新子节点的页码范围
                updatePageRanges(node.getNodes(), totalPages, node);
                
                // 父节点的起始页码是第一个子节点的起始页码
                TreeNode firstChild = node.getNodes().get(0);
                if (firstChild.getStartIndex() != null && firstChild.getStartIndex() > 0) {
                    node.setStartIndex(firstChild.getStartIndex());
                }
                
                // 父节点的结束页码是最后一个子节点的结束页码
                TreeNode lastChild = node.getNodes().get(node.getNodes().size() - 1);
                if (lastChild.getEndIndex() != null && lastChild.getEndIndex() > 0) {
                    node.setEndIndex(lastChild.getEndIndex());
                } else {
                    // 如果最后一个子节点没有结束页码，使用下一个同级节点的起始页码-1
                    Integer nextStartPage = findNextSiblingStartPage(nodes, i);
                    if (nextStartPage != null) {
                        node.setEndIndex(nextStartPage - 1);
                    } else if (parentNode != null && parentNode.getEndIndex() != null) {
                        // 如果有父节点，使用父节点的结束页码
                        node.setEndIndex(parentNode.getEndIndex());
                    } else {
                        node.setEndIndex(totalPages);
                    }
                }
            } else {
                // 叶子节点：如果没有结束页码或结束页码等于起始页码，需要更新
                if (node.getEndIndex() == null || node.getEndIndex().equals(node.getStartIndex()) || node.getEndIndex() <= 0) {
                    // 查找下一个同级节点的起始页码
                    Integer nextStartPage = findNextSiblingStartPage(nodes, i);
                    
                    if (nextStartPage != null) {
                        // 如果找到下一个节点，结束页码是下一个节点的起始页码-1
                        node.setEndIndex(nextStartPage - 1);
                    } else if (parentNode != null && parentNode.getEndIndex() != null) {
                        // 如果有父节点，使用父节点的结束页码（但不能超过父节点的结束页码）
                        node.setEndIndex(parentNode.getEndIndex());
                    } else if (i == nodes.size() - 1) {
                        // 最后一个节点，使用总页数
                        node.setEndIndex(totalPages);
                    } else {
                        // 保持当前值或设置为起始页码+1（至少跨一页）
                        node.setEndIndex(Math.max(node.getStartIndex(), node.getStartIndex() + 1));
                    }
                }
                
                // 确保子节点的起始页码至少等于父节点的起始页码
                if (parentNode != null && parentNode.getStartIndex() != null) {
                    if (node.getStartIndex() == null || node.getStartIndex() < parentNode.getStartIndex()) {
                        node.setStartIndex(parentNode.getStartIndex());
                    }
                }
                
                // 确保子节点的结束页码不超过父节点的结束页码
                if (parentNode != null && parentNode.getEndIndex() != null) {
                    if (node.getEndIndex() == null || node.getEndIndex() > parentNode.getEndIndex()) {
                        node.setEndIndex(parentNode.getEndIndex());
                    }
                }
            }
        }
    }
    
    /**
     * 查找下一个同级节点的起始页码
     */
    private static Integer findNextSiblingStartPage(List<TreeNode> nodes, int currentIndex) {
        for (int j = currentIndex + 1; j < nodes.size(); j++) {
            TreeNode nextNode = nodes.get(j);
            if (nextNode.getStartIndex() != null && nextNode.getStartIndex() > 0) {
                return nextNode.getStartIndex();
            }
        }
        return null;
    }
    
    /**
     * 为树节点分配节点ID和父节点ID
     */
    public static void assignNodeIds(List<TreeNode> nodes) {
        assignNodeIds(nodes, 0, null);
    }
    
    private static int assignNodeIds(List<TreeNode> nodes, int startId, String parentId) {
        int currentId = startId;
        for (TreeNode node : nodes) {
            node.setNodeId(String.format("%04d", currentId));
            node.setParentNodeId(parentId); // 设置父节点ID
            currentId++;
            
            if (node.hasChildren()) {
                currentId = assignNodeIds(node.getNodes(), currentId, node.getNodeId());
            }
        }
        return currentId;
    }
}
