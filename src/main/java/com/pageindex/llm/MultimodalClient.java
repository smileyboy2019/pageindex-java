package com.pageindex.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 多模态客户端，用于图片识别
 */
public class MultimodalClient implements ImageRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(MultimodalClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient client;
    private final String baseUrl;
    private final String model;
    private final int timeout;
    
    public MultimodalClient(String baseUrl, String model, int timeout) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeout = timeout;
        
        this.client = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 识别图片内容
     * 
     * @param imageData 图片数据（字节数组）
     * @param imageFormat 图片格式（PNG, JPEG等）
     * @return 图片描述
     */
    public String recognizeImage(byte[] imageData, String imageFormat) {
        try {
            // 将图片转换为base64
            String base64Image = Base64.getEncoder().encodeToString(imageData);
            
            // 规范化图片格式（Ollama可能需要特定的格式）
            String normalizedFormat = normalizeImageFormat(imageFormat);
            
            logger.debug("Recognizing image: format={}, size={} bytes, base64 length={}", 
                    normalizedFormat, imageData.length, base64Image.length());
            
            // 构建请求 - 使用Ollama的chat API格式，支持多模态
            // Ollama支持两种格式：
            // 1. OpenAI格式：{"type": "image_url", "image_url": {"url": "data:image/..."}}
            // 2. Ollama原生格式：直接使用base64字符串
            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            
            // 构建消息内容：包含文本和图片
            List<Object> content = new ArrayList<>();
            content.add(Map.of("type", "text", "text", 
                "请详细描述这张图片的内容，包括图片中的所有文字、图表、表格、图形等可见信息。\n" +
                "要求：\n" +
                "1. 直接返回纯文本描述，不要包含HTML、Markdown格式或图片路径\n" +
                "2. 如果图片中有文字，请完整提取所有文字内容\n" +
                "3. 如果图片中有表格，请以文本形式描述表格的结构和内容\n" +
                "4. 如果图片中有图表，请描述图表的主要信息和数据\n" +
                "5. 只返回实际的文字内容，不要返回图片路径或HTML标签"));
            
            // 尝试使用Ollama原生格式（直接base64）
            Map<String, Object> imageContent = new HashMap<>();
            imageContent.put("type", "image");
            imageContent.put("source", Map.of(
                "type", "base64",
                "media_type", "image/" + normalizedFormat,
                "data", base64Image
            ));
            content.add(imageContent);
            
            userMessage.put("content", content);
            messages.add(userMessage);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("stream", false);
            
            String jsonBody = mapper.writeValueAsString(requestBody);
            RequestBody body = RequestBody.create(jsonBody, JSON);
            
            Request request = new Request.Builder()
                    .url(baseUrl + "/api/chat")
                    .post(body)
                    .build();
            
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("Multimodal API request failed: {} - {}", response.code(), response.message());
                    String errorBody = response.body() != null ? response.body().string() : "";
                    logger.debug("Error response body: {}", errorBody);
                    // 返回null，让调用者使用HanLP回退
                    return null;
                }
                
                String responseBody = response.body().string();
                JsonNode jsonNode = mapper.readTree(responseBody);
                
                // Ollama的响应格式：{"message": {"content": "..."}}
                if (jsonNode.has("message") && jsonNode.get("message").has("content")) {
                    String description = jsonNode.get("message").get("content").asText();
                    // 清理HTML/Markdown标签和图片路径
                    description = cleanImageDescription(description);
                    logger.debug("Image recognized successfully (length: {})", description.length());
                    return description;
                }
                
                logger.warn("Unexpected response format from multimodal API");
                return null;
            }
        } catch (Exception e) {
            logger.error("Failed to recognize image", e);
            return null;
        }
    }
    
    /**
     * 规范化图片格式
     */
    private String normalizeImageFormat(String format) {
        if (format == null || format.isEmpty()) {
            return "png";
        }
        String lower = format.toLowerCase();
        // 处理常见的格式变体
        if (lower.contains("jpeg") || lower.contains("jpg")) {
            return "jpeg";
        } else if (lower.contains("png")) {
            return "png";
        } else if (lower.contains("gif")) {
            return "gif";
        } else if (lower.contains("webp")) {
            return "webp";
        } else if (lower.contains("bmp")) {
            return "bmp";
        }
        return lower;
    }
    
    /**
     * 清理图片描述，移除HTML/Markdown标签和图片路径
     */
    private String cleanImageDescription(String description) {
        if (description == null || description.isEmpty()) {
            return description;
        }
        
        // 移除HTML标签
        description = description.replaceAll("<[^>]+>", "");
        
        // 移除Markdown图片语法 ![alt](path)
        description = description.replaceAll("!\\[.*?\\]\\([^)]+\\)", "");
        
        // 移除图片路径（如 imgs/img_xxx.jpg）
        description = description.replaceAll("imgs/[^\\s]+", "");
        description = description.replaceAll("[^\\s]+\\.(jpg|jpeg|png|gif|bmp|webp)", "");
        
        // 移除HTML实体编码
        description = description.replace("&nbsp;", " ");
        description = description.replace("&lt;", "<");
        description = description.replace("&gt;", ">");
        description = description.replace("&amp;", "&");
        description = description.replace("&quot;", "\"");
        
        // 清理多余的空白字符
        description = description.replaceAll("\\s+", " ");
        description = description.trim();
        
        return description;
    }
    
    /**
     * 批量识别图片
     */
    public void recognizeImages(java.util.List<com.pageindex.model.PageContent.ImageInfo> images) {
        if (images == null || images.isEmpty()) {
            return;
        }
        
        logger.info("Recognizing {} images using multimodal model {}", images.size(), model);
        
        for (com.pageindex.model.PageContent.ImageInfo image : images) {
            if (image.getDescription() == null || image.getDescription().isEmpty()) {
                String description = recognizeImage(image.getImageData(), image.getImageFormat());
                if (description != null && !description.isEmpty()) {
                    image.setDescription(description);
                    logger.debug("Recognized image: {}", description.substring(0, Math.min(50, description.length())));
                }
            }
        }
    }
}
