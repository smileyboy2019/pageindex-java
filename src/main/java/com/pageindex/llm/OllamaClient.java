package com.pageindex.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pageindex.model.Config;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ollama API 客户端实现
 */
public class OllamaClient implements LLMClient {
    private static final Logger logger = LoggerFactory.getLogger(OllamaClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient client;
    private final Config.OllamaConfig config;
    private final String baseUrl;
    
    public OllamaClient(Config.OllamaConfig config) {
        this.config = config;
        this.baseUrl = config.getBaseUrl();
        
        this.client = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .build();
    }
    
    @Override
    public String call(String model, String prompt, List<ChatMessage> chatHistory) {
        try {
            String actualModel = config.getModel() != null && !config.getModel().isEmpty() 
                    ? config.getModel() 
                    : model;
            return callAPI(actualModel, prompt, chatHistory);
        } catch (Exception e) {
            logger.error("Error calling Ollama API: {}", e.getMessage());
            return "Error";
        }
    }
    
    @Override
    public CompletableFuture<String> callAsync(String model, String prompt) {
        return CompletableFuture.supplyAsync(() -> call(model, prompt, null));
    }
    
    @Override
    public LLMResponse callWithFinishReason(String model, String prompt, List<ChatMessage> chatHistory) {
        String content = call(model, prompt, chatHistory);
        return new LLMResponse(content, "finished");
    }
    
    private String callAPI(String model, String prompt, List<ChatMessage> chatHistory) throws IOException {
        List<Map<String, String>> messages = new ArrayList<>();
        
        if (chatHistory != null) {
            for (LLMClient.ChatMessage msg : chatHistory) {
                Map<String, String> message = new HashMap<>();
                message.put("role", msg.getRole());
                message.put("content", msg.getContent());
                messages.add(message);
            }
        }
        
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", config.getTemperature());
        requestBody.put("stream", false);
        requestBody.put("think", false);  // 禁用思考模式
        
        String jsonBody = mapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(jsonBody, JSON);
        
        Request request = new Request.Builder()
                .url(baseUrl + "/api/chat")
                .post(body)
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            
            String responseBody = response.body().string();
            JsonNode jsonNode = mapper.readTree(responseBody);
            return jsonNode.get("message").get("content").asText();
        }
    }
}
