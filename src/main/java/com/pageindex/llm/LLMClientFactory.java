package com.pageindex.llm;

import com.pageindex.model.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 客户端工厂，根据配置创建相应的客户端
 */
public class LLMClientFactory {
    private static final Logger logger = LoggerFactory.getLogger(LLMClientFactory.class);
    
    /**
     * 根据配置创建 LLM 客户端
     */
    public static LLMClient createClient(Config config) {
        String provider = config.getModelProvider();
        logger.info("Creating LLM client with provider: {}", provider);
        
        switch (provider.toLowerCase()) {
            case "openai":
                if (config.getOpenai() == null) {
                    logger.warn("OpenAI config not found, creating default config");
                    Config.OpenAIConfig openaiConfig = new Config.OpenAIConfig();
                    openaiConfig.setApiKey(System.getenv("CHATGPT_API_KEY"));
                    openaiConfig.setTemperature(0.0);
                    config.setOpenai(openaiConfig);
                }
                return new OpenAIClient(config.getOpenai());
                
            case "ollama":
                if (config.getOllama() == null) {
                    logger.warn("Ollama config not found, creating default config");
                    Config.OllamaConfig ollamaConfig = new Config.OllamaConfig();
                    ollamaConfig.setBaseUrl("http://localhost:11434");
                    ollamaConfig.setTemperature(0.0);
                    ollamaConfig.setTimeout(60);
                    config.setOllama(ollamaConfig);
                }
                return new OllamaClient(config.getOllama());
                
            case "deepspeed":
                if (config.getDeepspeed() == null) {
                    logger.warn("DeepSpeed config not found, creating default config");
                    Config.DeepSpeedConfig deepspeedConfig = new Config.DeepSpeedConfig();
                    deepspeedConfig.setBaseUrl("http://localhost:8000");
                    deepspeedConfig.setTemperature(0.0);
                    deepspeedConfig.setTimeout(120);
                    config.setDeepspeed(deepspeedConfig);
                }
                return new DeepSpeedClient(config.getDeepspeed());
                
            case "custom":
                if (config.getCustom() == null || config.getCustom().getBaseUrl() == null) {
                    throw new IllegalArgumentException("custom.base_url must be configured");
                }
                // Custom client 可以使用 DeepSpeedClient 的实现（兼容 OpenAI 格式）
                Config.DeepSpeedConfig customConfig = new Config.DeepSpeedConfig();
                customConfig.setBaseUrl(config.getCustom().getBaseUrl());
                customConfig.setModel(config.getCustom().getModel());
                customConfig.setTemperature(config.getCustom().getTemperature());
                customConfig.setTimeout(config.getCustom().getTimeout());
                customConfig.setHeaders(config.getCustom().getHeaders());
                return new DeepSpeedClient(customConfig);
                
            default:
                logger.warn("Unknown model provider: {}, using OpenAI as default", provider);
                Config.OpenAIConfig openaiConfig = new Config.OpenAIConfig();
                openaiConfig.setApiKey(System.getenv("CHATGPT_API_KEY"));
                openaiConfig.setTemperature(0.0);
                return new OpenAIClient(openaiConfig);
        }
    }
}
