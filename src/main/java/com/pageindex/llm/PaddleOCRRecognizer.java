package com.pageindex.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * PaddleOCR图片识别器
 * 支持三种方式：
 * 1. 百度AI Studio布局解析API（推荐，支持文档布局解析）
 * 2. 本地PaddleOCR Java SDK（如果可用）
 * 3. 百度智能云OCR API（标准OCR）
 */
public class PaddleOCRRecognizer implements ImageRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(PaddleOCRRecognizer.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient client;
    private final String apiKey;
    private final String secretKey;
    private final String accessToken;
    private final String layoutParsingApiUrl;
    private final boolean useLayoutParsingAPI;
    private final boolean useLocalSDK;
    private Object paddleOCRInstance;
    private boolean initialized = false;
    
    /**
     * 使用百度AI Studio布局解析API
     */
    public PaddleOCRRecognizer(String apiKey, String layoutParsingApiUrl) {
        this.apiKey = apiKey;
        this.secretKey = null;
        this.layoutParsingApiUrl = layoutParsingApiUrl != null ? layoutParsingApiUrl 
                : "https://28a0l9vdl4m4i7n4.aistudio-app.com/layout-parsing";
        this.useLayoutParsingAPI = true;
        this.useLocalSDK = false;
        this.accessToken = null;
        
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS) // 布局解析可能需要更长时间
                .build();
    }
    
    /**
     * 使用百度智能云OCR API
     */
    public PaddleOCRRecognizer(String apiKey, String secretKey, boolean useStandardOCR) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.layoutParsingApiUrl = null;
        this.useLayoutParsingAPI = false;
        this.useLocalSDK = false;
        this.accessToken = getAccessToken();
        
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 使用本地PaddleOCR SDK
     */
    public PaddleOCRRecognizer() {
        this.apiKey = null;
        this.secretKey = null;
        this.layoutParsingApiUrl = null;
        this.useLayoutParsingAPI = false;
        this.useLocalSDK = true;
        this.accessToken = null;
        
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        
        initializeLocalPaddleOCR();
    }
    
    /**
     * 初始化本地PaddleOCR SDK
     */
    private void initializeLocalPaddleOCR() {
        try {
            // 尝试使用反射加载PaddleOCR类
            Class<?> paddleOCRClass = Class.forName("com.baidu.paddleocr.PaddleOCR");
            String modelPath = System.getProperty("paddleocr.model.path", "./paddleocr_models");
            paddleOCRInstance = paddleOCRClass.getConstructor(String.class).newInstance(modelPath);
            initialized = true;
            logger.info("Local PaddleOCR SDK initialized successfully");
        } catch (ClassNotFoundException e) {
            logger.warn("PaddleOCR SDK not found, will use API mode or fallback");
        } catch (Exception e) {
            logger.error("Failed to initialize local PaddleOCR", e);
        }
    }
    
    /**
     * 获取百度智能云Access Token
     */
    private String getAccessToken() {
        if (apiKey == null || secretKey == null) {
            return null;
        }
        
        try {
            String url = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials&client_id=" 
                    + apiKey + "&client_secret=" + secretKey;
            
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create("", null))
                    .build();
            
            try (Response response = new OkHttpClient().newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    JsonNode json = mapper.readTree(responseBody);
                    return json.get("access_token").asText();
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get Baidu access token", e);
        }
        
        return null;
    }
    
    @Override
    public String recognizeImage(byte[] imageData, String imageFormat) {
        // 优先使用布局解析API（如果配置）
        if (useLayoutParsingAPI && apiKey != null) {
            return recognizeWithLayoutParsingAPI(imageData, imageFormat);
        }
        
        // 使用本地SDK
        if (useLocalSDK && initialized && paddleOCRInstance != null) {
            return recognizeWithLocalSDK(imageData, imageFormat);
        }
        
        // 使用百度智能云OCR API
        if (accessToken != null) {
            return recognizeWithBaiduAPI(imageData, imageFormat);
        }
        
        // 回退方案
        return fallbackImageAnalysis(imageData, imageFormat);
    }
    
    /**
     * 使用百度AI Studio布局解析API识别图片
     */
    private String recognizeWithLayoutParsingAPI(byte[] imageData, String imageFormat) {
        try {
            String base64Image = Base64.getEncoder().encodeToString(imageData);
            
            // 判断文件类型：PDF=0, 图片=1
            int fileType = isPDFFormat(imageFormat) ? 0 : 1;
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("file", base64Image);
            payload.put("fileType", fileType);
            payload.put("useDocOrientationClassify", false);
            payload.put("useDocUnwarping", false);
            payload.put("useChartRecognition", false);
            
            String jsonBody = mapper.writeValueAsString(payload);
            RequestBody body = RequestBody.create(jsonBody, JSON);
            
            Request request = new Request.Builder()
                    .url(layoutParsingApiUrl)
                    .post(body)
                    .header("Authorization", "token " + apiKey)
                    .header("Content-Type", "application/json")
                    .build();
            
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("Layout parsing API request failed: {} - {}", response.code(), response.message());
                    String errorBody = response.body() != null ? response.body().string() : "";
                    logger.debug("Error response: {}", errorBody);
                    return null;
                }
                
                String responseBody = response.body().string();
                JsonNode json = mapper.readTree(responseBody);
                
                // 解析布局解析结果
                if (json.has("result") && json.get("result").has("layoutParsingResults")) {
                    JsonNode results = json.get("result").get("layoutParsingResults");
                    if (results.isArray() && results.size() > 0) {
                        JsonNode firstResult = results.get(0);
                        if (firstResult.has("markdown") && firstResult.get("markdown").has("text")) {
                            String markdownText = firstResult.get("markdown").get("text").asText();
                            // 提取纯文本内容（去除Markdown/HTML格式）
                            String plainText = extractTextFromMarkdown(markdownText);
                            // 如果提取到有效文本，返回纯文本；否则返回null
                            if (plainText != null && !plainText.isEmpty()) {
                                return plainText;
                            }
                            return null;
                        }
                    }
                }
                
                logger.warn("Unexpected response format from layout parsing API");
                return null;
            }
        } catch (Exception e) {
            logger.error("Failed to recognize with layout parsing API", e);
            return null;
        }
    }
    
    /**
     * 判断是否为PDF格式
     */
    private boolean isPDFFormat(String format) {
        return format != null && (format.equalsIgnoreCase("PDF") || format.equalsIgnoreCase("pdf"));
    }
    
    /**
     * 从Markdown/HTML文本中提取纯文本内容
     */
    private String extractTextFromMarkdown(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        
        String text = markdown;
        
        // 1. 移除HTML标签（包括img标签）
        text = text.replaceAll("<[^>]+>", "");
        
        // 2. 移除Markdown图片语法 ![alt](path) 或 [text](path)
        text = text.replaceAll("!\\[.*?\\]\\([^)]+\\)", "");
        text = text.replaceAll("\\[([^\\]]+)\\]\\([^\\)]+\\)", "$1"); // 保留链接文本
        
        // 3. 移除图片路径（如 imgs/img_xxx.jpg）
        text = text.replaceAll("imgs/[^\\s]+", "");
        text = text.replaceAll("[^\\s]+\\.(jpg|jpeg|png|gif|bmp|webp)", "");
        
        // 4. 移除Markdown格式标记
        text = text.replaceAll("#+\\s+", ""); // 移除标题标记
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", "$1"); // 移除粗体标记
        text = text.replaceAll("\\*([^*]+)\\*", "$1"); // 移除斜体标记
        text = text.replaceAll("`([^`]+)`", "$1"); // 移除代码标记
        
        // 5. 移除HTML实体编码
        text = text.replace("&nbsp;", " ");
        text = text.replace("&lt;", "<");
        text = text.replace("&gt;", ">");
        text = text.replace("&amp;", "&");
        text = text.replace("&quot;", "\"");
        text = text.replace("&#39;", "'");
        
        // 6. 清理多余的空白字符
        text = text.replaceAll("\\s+", " ");
        text = text.trim();
        
        // 7. 如果提取的文本为空或只包含"图片内容（布局解析）："，返回空
        if (text.isEmpty() || text.equals("图片内容（布局解析）：")) {
            return "";
        }
        
        // 8. 移除前缀"图片内容（布局解析）："（如果存在）
        if (text.startsWith("图片内容（布局解析）：")) {
            text = text.substring("图片内容（布局解析）：".length()).trim();
        }
        
        return text;
    }
    
    /**
     * 使用本地PaddleOCR SDK识别
     */
    private String recognizeWithLocalSDK(byte[] imageData, String imageFormat) {
        try {
            Path tempFile = Files.createTempFile("paddleocr_", "." + imageFormat.toLowerCase());
            Files.write(tempFile, imageData);
            
            java.lang.reflect.Method ocrMethod = paddleOCRInstance.getClass()
                    .getMethod("ocr", String.class);
            Object result = ocrMethod.invoke(paddleOCRInstance, tempFile.toString());
            
            Files.deleteIfExists(tempFile);
            return parseOCRResult(result);
        } catch (Exception e) {
            logger.error("Failed to recognize with local PaddleOCR SDK", e);
            return null;
        }
    }
    
    /**
     * 使用百度智能云OCR API识别
     */
    private String recognizeWithBaiduAPI(byte[] imageData, String imageFormat) {
        try {
            String base64Image = Base64.getEncoder().encodeToString(imageData);
            
            Map<String, Object> params = new HashMap<>();
            params.put("image", base64Image);
            
            String jsonBody = mapper.writeValueAsString(params);
            RequestBody body = RequestBody.create(jsonBody, JSON);
            
            String url = "https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic?access_token=" + accessToken;
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .header("Content-Type", "application/json")
                    .build();
            
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("Baidu OCR API request failed: {}", response.code());
                    return null;
                }
                
                String responseBody = response.body().string();
                JsonNode json = mapper.readTree(responseBody);
                
                // 解析百度OCR返回结果
                if (json.has("words_result")) {
                    StringBuilder text = new StringBuilder();
                    for (JsonNode word : json.get("words_result")) {
                        if (word.has("words")) {
                            text.append(word.get("words").asText()).append(" ");
                        }
                    }
                    
                    String recognizedText = text.toString().trim();
                    if (!recognizedText.isEmpty()) {
                        return "图片中的文字内容：" + recognizedText;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to recognize with Baidu OCR API", e);
        }
        
        return null;
    }
    
    /**
     * 解析OCR识别结果
     */
    private String parseOCRResult(Object result) {
        if (result == null) {
            return null;
        }
        
        try {
            if (result instanceof java.util.List) {
                java.util.List<?> resultList = (java.util.List<?>) result;
                StringBuilder text = new StringBuilder();
                
                for (Object item : resultList) {
                    if (item instanceof java.util.List) {
                        java.util.List<?> line = (java.util.List<?>) item;
                        if (!line.isEmpty() && line.get(0) instanceof String) {
                            text.append(line.get(0)).append(" ");
                        }
                    } else if (item instanceof String) {
                        text.append(item).append(" ");
                    }
                }
                
                String recognizedText = text.toString().trim();
                if (!recognizedText.isEmpty()) {
                    return "图片中的文字内容：" + recognizedText;
                }
            }
            
            if (result instanceof String) {
                String text = (String) result;
                if (!text.isEmpty()) {
                    return "图片中的文字内容：" + text;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse OCR result", e);
        }
        
        return null;
    }
    
    /**
     * 回退方案：基本图像分析
     */
    private String fallbackImageAnalysis(byte[] imageData, String imageFormat) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                return "无法读取图片";
            }
            
            StringBuilder desc = new StringBuilder();
            desc.append("图片尺寸：").append(image.getWidth()).append("x").append(image.getHeight()).append("像素。");
            desc.append("图片格式：").append(imageFormat).append("。");
            desc.append("（PaddleOCR未配置，使用基本图像分析）");
            
            return desc.toString();
        } catch (IOException e) {
            logger.error("Failed to analyze image", e);
            return null;
        }
    }
}
