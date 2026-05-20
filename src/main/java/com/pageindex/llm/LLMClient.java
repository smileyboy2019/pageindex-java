package com.pageindex.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * LLM 客户端接口
 */
public interface LLMClient {
    
    /**
     * 同步调用 LLM API
     * 
     * @param model 模型名称
     * @param prompt 提示词
     * @param chatHistory 聊天历史（可选）
     * @return LLM 响应文本
     */
    String call(String model, String prompt, List<ChatMessage> chatHistory);
    
    /**
     * 异步调用 LLM API
     * 
     * @param model 模型名称
     * @param prompt 提示词
     * @return CompletableFuture 包含 LLM 响应文本
     */
    CompletableFuture<String> callAsync(String model, String prompt);
    
    /**
     * 带完成原因的调用（用于检测是否达到最大输出长度）
     * 
     * @param model 模型名称
     * @param prompt 提示词
     * @param chatHistory 聊天历史（可选）
     * @return LLMResponse 包含响应文本和完成原因
     */
    LLMResponse callWithFinishReason(String model, String prompt, List<ChatMessage> chatHistory);
    
    /**
     * 聊天消息类
     */
    class ChatMessage {
        private String role;
        private String content;
        
        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
        
        public String getRole() {
            return role;
        }
        
        public void setRole(String role) {
            this.role = role;
        }
        
        public String getContent() {
            return content;
        }
        
        public void setContent(String content) {
            this.content = content;
        }
    }
    
    /**
     * LLM 响应类
     */
    class LLMResponse {
        private String content;
        private String finishReason;
        
        public LLMResponse(String content, String finishReason) {
            this.content = content;
            this.finishReason = finishReason;
        }
        
        public String getContent() {
            return content;
        }
        
        public String getFinishReason() {
            return finishReason;
        }
    }
}
