package com.pageindex.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pageindex.model.Config;
import com.pageindex.utils.JsonUtils;
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
 * OpenAI API 客户端实现
 */
public class OpenAIClient implements LLMClient {
    private static final Logger logger = LoggerFactory.getLogger(OpenAIClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient client;
    private final Config.OpenAIConfig config;
    private final String apiKey;
    private final String baseUrl;
    
    public OpenAIClient(Config.OpenAIConfig config) {
        this.config = config;
        this.apiKey = config.getApiKey() != null && !config.getApiKey().isEmpty() 
                ? config.getApiKey() 
                : System.getenv("CHATGPT_API_KEY");
        this.baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isEmpty()
                ? config.getBaseUrl()
                : "https://api.openai.com/v1";
        
        this.client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }
    
    @Override
    public String call(String model, String prompt, List<ChatMessage> chatHistory) {
        int maxRetries = 10;
        for (int i = 0; i < maxRetries; i++) {
            try {
                List<Map<String, String>> messages = buildMessages(prompt, chatHistory);
                String response = callAPI(model, messages);
                return response;
            } catch (Exception e) {
                logger.error("Error calling OpenAI API (attempt {}/{}): {}", i + 1, maxRetries, e.getMessage());
                if (i < maxRetries - 1) {
                    try {
                        Thread.sleep(1000); // Wait 1 second before retrying
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return "Error";
                    }
                } else {
                    logger.error("Max retries reached for prompt: " + prompt);
                    return "Error";
                }
            }
        }
        return "Error";
    }
    
    @Override
    public CompletableFuture<String> callAsync(String model, String prompt) {
        return CompletableFuture.supplyAsync(() -> call(model, prompt, null));
    }
    
    @Override
    public LLMResponse callWithFinishReason(String model, String prompt, List<ChatMessage> chatHistory) {
        int maxRetries = 10;
        for (int i = 0; i < maxRetries; i++) {
            try {
                List<Map<String, String>> messages = buildMessages(prompt, chatHistory);
                return callAPIWithFinishReason(model, messages);
            } catch (Exception e) {
                logger.error("Error calling OpenAI API (attempt {}/{}): {}", i + 1, maxRetries, e.getMessage());
                if (i < maxRetries - 1) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return new LLMResponse("Error", "error");
                    }
                } else {
                    return new LLMResponse("Error", "error");
                }
            }
        }
        return new LLMResponse("Error", "error");
    }
    
    private List<Map<String, String>> buildMessages(String prompt, List<ChatMessage> chatHistory) {
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
        
        return messages;
    }
    
    private String callAPI(String model, List<Map<String, String>> messages) throws IOException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", config.getTemperature());
        
        String jsonBody = mapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(jsonBody, JSON);
        
        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            
            String responseBody = response.body().string();
            JsonNode jsonNode = mapper.readTree(responseBody);
            return jsonNode.get("choices").get(0).get("message").get("content").asText();
        }
    }
    
    private LLMResponse callAPIWithFinishReason(String model, List<Map<String, String>> messages) throws IOException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", config.getTemperature());
        
        String jsonBody = mapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(jsonBody, JSON);
        
        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            
            String responseBody = response.body().string();
            JsonNode jsonNode = mapper.readTree(responseBody);
            String content = jsonNode.get("choices").get(0).get("message").get("content").asText();
            String finishReason = jsonNode.get("choices").get(0).get("finish_reason").asText();
            
            if ("length".equals(finishReason)) {
                return new LLMResponse(content, "max_output_reached");
            } else {
                return new LLMResponse(content, "finished");
            }
        }
    }
}
