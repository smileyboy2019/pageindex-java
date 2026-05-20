package com.pageindex.llm;

import com.pageindex.model.PageContent;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Tesseract OCR 图片识别器
 * 使用本地Tesseract OCR引擎进行图片文字识别，速度更快，无需网络请求
 */
public class TesseractOCRRecognizer implements ImageRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(TesseractOCRRecognizer.class);
    
    private final ITesseract tesseract;
    private final String language; // OCR语言，如 "chi_sim" (简体中文), "eng" (英文)
    
    /**
     * 创建Tesseract OCR识别器
     * 
     * @param tessdataPath Tesseract数据文件路径（可选，null则使用默认路径）
     * @param language OCR语言代码，如 "chi_sim+eng" (中文+英文)
     */
    public TesseractOCRRecognizer(String tessdataPath, String language) {
        this.language = language != null && !language.isEmpty() ? language : "chi_sim+eng";
        this.tesseract = new Tesseract();
        
        try {
            // 设置Tesseract数据文件路径
            if (tessdataPath != null && !tessdataPath.isEmpty()) {
                tesseract.setDatapath(tessdataPath);
            }
            
            // 设置OCR语言
            tesseract.setLanguage(this.language);
            
            // 设置OCR配置（提高识别准确率）
            tesseract.setPageSegMode(1); // 自动页面分割
            tesseract.setOcrEngineMode(1); // LSTM OCR引擎
            
            // 测试Tesseract是否可用（尝试创建一个测试实例）
            logger.info("Tesseract OCR initialized with language: {}", this.language);
        } catch (Exception e) {
            logger.error("Failed to initialize Tesseract OCR: {}. Please install Tesseract or use another recognition method.", e.getMessage());
            logger.warn("Tesseract OCR will return null for all recognition requests. Consider installing Tesseract or switching to 'multimodal' method.");
            // 不抛出异常，允许程序继续运行，但识别会返回null
        }
    }
    
    /**
     * 使用默认配置创建Tesseract OCR识别器（中文+英文）
     */
    public TesseractOCRRecognizer() {
        this(null, "chi_sim+eng");
    }
    
    @Override
    public String recognizeImage(byte[] imageData, String imageFormat) {
        if (tesseract == null) {
            logger.warn("Tesseract OCR is not initialized. Please install Tesseract or use another recognition method.");
            return null;
        }
        
        try {
            // 读取图片
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                logger.warn("Failed to read image data");
                return null;
            }
            
            // 使用Tesseract进行OCR识别
            String text = tesseract.doOCR(image);
            
            if (text != null && !text.trim().isEmpty()) {
                // 清理识别结果（移除多余的空白字符）
                text = text.replaceAll("\\s+", " ").trim();
                logger.debug("Tesseract OCR recognized text (length: {})", text.length());
                return text;
            } else {
                logger.debug("Tesseract OCR returned empty text");
                return null;
            }
        } catch (TesseractException e) {
            logger.warn("Tesseract OCR error: {}. Image recognition failed.", e.getMessage());
            return null;
        } catch (IOException e) {
            logger.warn("Failed to read image: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            logger.warn("Unexpected error during OCR: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public CompletableFuture<Void> recognizeImagesAsync(List<PageContent.ImageInfo> images) {
        if (images == null || images.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // 并行处理所有图片（使用CompletableFuture的默认线程池）
        for (PageContent.ImageInfo image : images) {
            if (image.getDescription() == null || image.getDescription().isEmpty()) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        String description = recognizeImage(image.getImageData(), image.getImageFormat());
                        if (description != null && !description.isEmpty()) {
                            image.setDescription(description);
                            logger.debug("Successfully recognized image using Tesseract OCR");
                        } else {
                            logger.debug("Tesseract OCR returned empty description");
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to recognize image with Tesseract OCR", e);
                    }
                });
                futures.add(future);
            }
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
