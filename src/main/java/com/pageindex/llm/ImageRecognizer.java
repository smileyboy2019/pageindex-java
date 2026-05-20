package com.pageindex.llm;

import com.pageindex.model.PageContent;
import java.util.concurrent.CompletableFuture;
import java.util.List;

/**
 * 图片识别接口
 */
public interface ImageRecognizer {
    /**
     * 识别图片内容
     * 
     * @param imageData 图片数据（字节数组）
     * @param imageFormat 图片格式（PNG, JPEG等）
     * @return 图片描述
     */
    String recognizeImage(byte[] imageData, String imageFormat);
    
    /**
     * 批量识别图片（串行）
     */
    default void recognizeImages(List<PageContent.ImageInfo> images) {
        if (images == null || images.isEmpty()) {
            return;
        }
        
        for (PageContent.ImageInfo image : images) {
            if (image.getDescription() == null || image.getDescription().isEmpty()) {
                String description = recognizeImage(image.getImageData(), image.getImageFormat());
                if (description != null && !description.isEmpty()) {
                    image.setDescription(description);
                }
            }
        }
    }
    
    /**
     * 批量识别图片（并行，默认实现）
     */
    default CompletableFuture<Void> recognizeImagesAsync(List<PageContent.ImageInfo> images) {
        if (images == null || images.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        
        for (PageContent.ImageInfo image : images) {
            if (image.getDescription() == null || image.getDescription().isEmpty()) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    String description = recognizeImage(image.getImageData(), image.getImageFormat());
                    if (description != null && !description.isEmpty()) {
                        image.setDescription(description);
                    }
                });
                futures.add(future);
            }
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
