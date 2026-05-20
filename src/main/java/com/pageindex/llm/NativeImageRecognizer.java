package com.pageindex.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Java原生图片识别器
 * 使用图像处理算法分析图片内容
 */
public class NativeImageRecognizer implements ImageRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(NativeImageRecognizer.class);
    
    @Override
    public String recognizeImage(byte[] imageData, String imageFormat) {
        try {
            // 读取图片
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                logger.warn("Failed to read image");
                return null;
            }
            
            // 分析图片特征
            ImageAnalysis analysis = analyzeImage(image);
            
            // 生成描述
            return generateDescription(analysis);
        } catch (IOException e) {
            logger.error("Failed to recognize image", e);
            return null;
        }
    }
    
    /**
     * 分析图片特征
     */
    private ImageAnalysis analyzeImage(BufferedImage image) {
        ImageAnalysis analysis = new ImageAnalysis();
        analysis.width = image.getWidth();
        analysis.height = image.getHeight();
        
        // 计算平均颜色
        long totalR = 0, totalG = 0, totalB = 0;
        int pixelCount = 0;
        
        // 采样分析（避免处理所有像素）
        int sampleStep = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 100);
        
        for (int y = 0; y < image.getHeight(); y += sampleStep) {
            for (int x = 0; x < image.getWidth(); x += sampleStep) {
                int rgb = image.getRGB(x, y);
                Color color = new Color(rgb);
                totalR += color.getRed();
                totalG += color.getGreen();
                totalB += color.getBlue();
                pixelCount++;
            }
        }
        
        if (pixelCount > 0) {
            analysis.avgR = (int) (totalR / pixelCount);
            analysis.avgG = (int) (totalG / pixelCount);
            analysis.avgB = (int) (totalB / pixelCount);
        }
        
        // 检测图片类型（简单启发式方法）
        analysis.imageType = detectImageType(image, analysis);
        
        // 检测是否有文字区域（通过颜色变化检测）
        analysis.hasText = detectTextRegions(image);
        
        return analysis;
    }
    
    /**
     * 检测图片类型
     */
    private String detectImageType(BufferedImage image, ImageAnalysis analysis) {
        // 检测是否为图表（通过颜色数量和分布）
        Map<Integer, Integer> colorCount = new HashMap<>();
        int sampleStep = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 50);
        
        for (int y = 0; y < image.getHeight(); y += sampleStep) {
            for (int x = 0; x < image.getWidth(); x += sampleStep) {
                int rgb = image.getRGB(x, y);
                colorCount.put(rgb, colorCount.getOrDefault(rgb, 0) + 1);
            }
        }
        
        int uniqueColors = colorCount.size();
        int totalSamples = (image.getWidth() / sampleStep) * (image.getHeight() / sampleStep);
        
        // 如果颜色种类较少，可能是图表或示意图
        if (uniqueColors < totalSamples * 0.1) {
            return "图表或示意图";
        }
        
        // 如果颜色分布均匀，可能是照片
        if (uniqueColors > totalSamples * 0.5) {
            return "照片或复杂图像";
        }
        
        return "图像";
    }
    
    /**
     * 检测是否有文字区域
     */
    private boolean detectTextRegions(BufferedImage image) {
        // 简单的文字检测：查找高对比度区域
        int sampleStep = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 100);
        int highContrastCount = 0;
        int totalSamples = 0;
        
        for (int y = 0; y < image.getHeight() - sampleStep; y += sampleStep) {
            for (int x = 0; x < image.getWidth() - sampleStep; x += sampleStep) {
                Color c1 = new Color(image.getRGB(x, y));
                Color c2 = new Color(image.getRGB(x + sampleStep, y));
                
                // 计算对比度
                int contrast = Math.abs(c1.getRed() - c2.getRed()) +
                              Math.abs(c1.getGreen() - c2.getGreen()) +
                              Math.abs(c1.getBlue() - c2.getBlue());
                
                if (contrast > 100) { // 高对比度阈值
                    highContrastCount++;
                }
                totalSamples++;
            }
        }
        
        // 如果高对比度区域超过一定比例，可能有文字
        return totalSamples > 0 && (highContrastCount * 100.0 / totalSamples) > 5.0;
    }
    
    /**
     * 生成图片描述
     */
    private String generateDescription(ImageAnalysis analysis) {
        StringBuilder desc = new StringBuilder();
        
        desc.append("图片尺寸：").append(analysis.width).append("x").append(analysis.height).append("像素。");
        desc.append("图片类型：").append(analysis.imageType).append("。");
        
        // 描述颜色特征
        String brightness = (analysis.avgR + analysis.avgG + analysis.avgB) / 3 > 128 ? "较亮" : "较暗";
        desc.append("整体色调：").append(brightness).append("。");
        
        // 如果有文字
        if (analysis.hasText) {
            desc.append("图片中包含文字内容。");
        }
        
        return desc.toString();
    }
    
    /**
     * 图片分析结果
     */
    private static class ImageAnalysis {
        int width;
        int height;
        int avgR, avgG, avgB;
        String imageType;
        boolean hasText;
    }
}
