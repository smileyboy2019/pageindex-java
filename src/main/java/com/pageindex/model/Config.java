package com.pageindex.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PageIndex 配置类
 */
public class Config {
    private String model;
    
    @JsonProperty("model_provider")
    private String modelProvider;
    
    private OpenAIConfig openai;
    private OllamaConfig ollama;
    private DeepSpeedConfig deepspeed;
    private CustomConfig custom;
    private MultimodalConfig multimodal;
    
    @JsonProperty("toc_check_page_num")
    private Integer tocCheckPageNum;
    
    @JsonProperty("max_page_num_each_node")
    private Integer maxPageNumEachNode;
    
    @JsonProperty("max_token_num_each_node")
    private Integer maxTokenNumEachNode;
    
    @JsonProperty("if_add_node_id")
    private String ifAddNodeId;
    
    @JsonProperty("if_add_node_summary")
    private String ifAddNodeSummary;
    
    @JsonProperty("summary_method")
    private String summaryMethod; // "llm" 使用LLM生成, "hanlp" 使用HanLP生成
    
    @JsonProperty("if_add_doc_description")
    private String ifAddDocDescription;
    
    @JsonProperty("if_add_node_text")
    private String ifAddNodeText;
    
    @JsonProperty("use_rule_based_tree")
    private String useRuleBasedTree; // "yes" 使用基于规则的方法构建树结构，不依赖LLM
    
    // Getters and Setters
    public String getModel() {
        return model;
    }
    
    public void setModel(String model) {
        this.model = model;
    }
    
    public String getModelProvider() {
        return modelProvider != null ? modelProvider : "openai";
    }
    
    public void setModelProvider(String modelProvider) {
        this.modelProvider = modelProvider;
    }
    
    public OpenAIConfig getOpenai() {
        return openai;
    }
    
    public void setOpenai(OpenAIConfig openai) {
        this.openai = openai;
    }
    
    public OllamaConfig getOllama() {
        return ollama;
    }
    
    public void setOllama(OllamaConfig ollama) {
        this.ollama = ollama;
    }
    
    public DeepSpeedConfig getDeepspeed() {
        return deepspeed;
    }
    
    public void setDeepspeed(DeepSpeedConfig deepspeed) {
        this.deepspeed = deepspeed;
    }
    
    public CustomConfig getCustom() {
        return custom;
    }
    
    public void setCustom(CustomConfig custom) {
        this.custom = custom;
    }
    
    public MultimodalConfig getMultimodal() {
        return multimodal;
    }
    
    public void setMultimodal(MultimodalConfig multimodal) {
        this.multimodal = multimodal;
    }
    
    public Integer getTocCheckPageNum() {
        return tocCheckPageNum != null ? tocCheckPageNum : 20;
    }
    
    public void setTocCheckPageNum(Integer tocCheckPageNum) {
        this.tocCheckPageNum = tocCheckPageNum;
    }
    
    public Integer getMaxPageNumEachNode() {
        return maxPageNumEachNode != null ? maxPageNumEachNode : 10;
    }
    
    public void setMaxPageNumEachNode(Integer maxPageNumEachNode) {
        this.maxPageNumEachNode = maxPageNumEachNode;
    }
    
    public Integer getMaxTokenNumEachNode() {
        return maxTokenNumEachNode != null ? maxTokenNumEachNode : 20000;
    }
    
    public void setMaxTokenNumEachNode(Integer maxTokenNumEachNode) {
        this.maxTokenNumEachNode = maxTokenNumEachNode;
    }
    
    public String getIfAddNodeId() {
        return ifAddNodeId != null ? ifAddNodeId : "yes";
    }
    
    public void setIfAddNodeId(String ifAddNodeId) {
        this.ifAddNodeId = ifAddNodeId;
    }
    
    public String getIfAddNodeSummary() {
        return ifAddNodeSummary != null ? ifAddNodeSummary : "yes";
    }
    
    public void setIfAddNodeSummary(String ifAddNodeSummary) {
        this.ifAddNodeSummary = ifAddNodeSummary;
    }
    
    public String getSummaryMethod() {
        return summaryMethod != null && !summaryMethod.isEmpty() ? summaryMethod.toLowerCase() : "llm";
    }
    
    public void setSummaryMethod(String summaryMethod) {
        this.summaryMethod = summaryMethod;
    }
    
    public String getIfAddDocDescription() {
        return ifAddDocDescription != null ? ifAddDocDescription : "no";
    }
    
    public void setIfAddDocDescription(String ifAddDocDescription) {
        this.ifAddDocDescription = ifAddDocDescription;
    }
    
    public String getIfAddNodeText() {
        return ifAddNodeText != null ? ifAddNodeText : "no";
    }
    
    public void setIfAddNodeText(String ifAddNodeText) {
        this.ifAddNodeText = ifAddNodeText;
    }
    
    public String getUseRuleBasedTree() {
        return useRuleBasedTree != null ? useRuleBasedTree : "no";
    }
    
    public void setUseRuleBasedTree(String useRuleBasedTree) {
        this.useRuleBasedTree = useRuleBasedTree;
    }
    
    // 嵌套配置类
    public static class OpenAIConfig {
        @JsonProperty("api_key")
        private String apiKey;
        
        @JsonProperty("base_url")
        private String baseUrl;
        
        private Double temperature;
        
        public String getApiKey() {
            return apiKey;
        }
        
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
        
        public String getBaseUrl() {
            return baseUrl;
        }
        
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
        
        public Double getTemperature() {
            return temperature != null ? temperature : 0.0;
        }
        
        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }
    }
    
    public static class OllamaConfig {
        @JsonProperty("base_url")
        private String baseUrl;
        
        private String model;
        private Double temperature;
        private Integer timeout;
        
        public String getBaseUrl() {
            return baseUrl != null ? baseUrl : "http://localhost:11434";
        }
        
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
        
        public String getModel() {
            return model;
        }
        
        public void setModel(String model) {
            this.model = model;
        }
        
        public Double getTemperature() {
            return temperature != null ? temperature : 0.0;
        }
        
        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }
        
        public Integer getTimeout() {
            return timeout != null ? timeout : 60;
        }
        
        public void setTimeout(Integer timeout) {
            this.timeout = timeout;
        }
    }
    
    public static class DeepSpeedConfig {
        @JsonProperty("base_url")
        private String baseUrl;
        
        private String model;
        private Double temperature;
        private Integer timeout;
        private java.util.Map<String, String> headers;
        
        public String getBaseUrl() {
            return baseUrl != null ? baseUrl : "http://localhost:8000";
        }
        
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
        
        public String getModel() {
            return model;
        }
        
        public void setModel(String model) {
            this.model = model;
        }
        
        public Double getTemperature() {
            return temperature != null ? temperature : 0.0;
        }
        
        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }
        
        public Integer getTimeout() {
            return timeout != null ? timeout : 120;
        }
        
        public void setTimeout(Integer timeout) {
            this.timeout = timeout;
        }
        
        public java.util.Map<String, String> getHeaders() {
            return headers;
        }
        
        public void setHeaders(java.util.Map<String, String> headers) {
            this.headers = headers;
        }
    }
    
    public static class CustomConfig {
        @JsonProperty("base_url")
        private String baseUrl;
        
        private String model;
        
        @JsonProperty("api_key")
        private String apiKey;
        
        private java.util.Map<String, String> headers;
        private Double temperature;
        private Integer timeout;
        
        public String getBaseUrl() {
            return baseUrl;
        }
        
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
        
        public String getModel() {
            return model;
        }
        
        public void setModel(String model) {
            this.model = model;
        }
        
        public String getApiKey() {
            return apiKey;
        }
        
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
        
        public java.util.Map<String, String> getHeaders() {
            return headers;
        }
        
        public void setHeaders(java.util.Map<String, String> headers) {
            this.headers = headers;
        }
        
        public Double getTemperature() {
            return temperature != null ? temperature : 0.0;
        }
        
        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }
        
        public Integer getTimeout() {
            return timeout != null ? timeout : 60;
        }
        
        public void setTimeout(Integer timeout) {
            this.timeout = timeout;
        }
    }
    
    public static class MultimodalConfig {
        @JsonProperty("base_url")
        private String baseUrl;
        
        private String model;
        private Integer timeout;
        private Boolean enabled;
        
        public String getBaseUrl() {
            return baseUrl != null ? baseUrl : "http://192.168.1.155:11434";
        }
        
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
        
        public String getModel() {
            return model != null ? model : "minicpm-v";
        }
        
        public void setModel(String model) {
            this.model = model;
        }
        
        public Integer getTimeout() {
            return timeout != null ? timeout : 300;
        }
        
        public void setTimeout(Integer timeout) {
            this.timeout = timeout;
        }
        
        public Boolean getEnabled() {
            return enabled != null ? enabled : true;
        }
        
        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
        
        @JsonProperty("recognition_method")
        private String recognitionMethod; // "tesseract", "multimodal", "paddleocr", "native"
        
        @JsonProperty("tesseract_data_path")
        private String tesseractDataPath; // Tesseract数据文件路径
        
        @JsonProperty("tesseract_language")
        private String tesseractLanguage; // Tesseract OCR语言，如 "chi_sim+eng"
        
        public String getRecognitionMethod() {
            return recognitionMethod != null ? recognitionMethod : "tesseract";
        }
        
        public void setRecognitionMethod(String recognitionMethod) {
            this.recognitionMethod = recognitionMethod;
        }
        
        public String getTesseractDataPath() {
            return tesseractDataPath;
        }
        
        public void setTesseractDataPath(String tesseractDataPath) {
            this.tesseractDataPath = tesseractDataPath;
        }
        
        public String getTesseractLanguage() {
            return tesseractLanguage != null && !tesseractLanguage.isEmpty() 
                    ? tesseractLanguage : "chi_sim+eng";
        }
        
        public void setTesseractLanguage(String tesseractLanguage) {
            this.tesseractLanguage = tesseractLanguage;
        }
        
        @JsonProperty("paddleocr_model_path")
        private String paddleocrModelPath; // PaddleOCR模型路径
        
        public String getPaddleocrModelPath() {
            return paddleocrModelPath != null ? paddleocrModelPath : "./paddleocr_models";
        }
        
        public void setPaddleocrModelPath(String paddleocrModelPath) {
            this.paddleocrModelPath = paddleocrModelPath;
        }
        
        @JsonProperty("paddleocr_api_key")
        private String paddleocrApiKey; // 百度AI Studio Token或百度智能云OCR API Key
        
        @JsonProperty("paddleocr_secret_key")
        private String paddleocrSecretKey; // 百度智能云OCR Secret Key（标准OCR使用）
        
        @JsonProperty("paddleocr_layout_api_url")
        private String paddleocrLayoutApiUrl; // 百度AI Studio布局解析API地址
        
        public String getPaddleocrApiKey() {
            return paddleocrApiKey;
        }
        
        public void setPaddleocrApiKey(String paddleocrApiKey) {
            this.paddleocrApiKey = paddleocrApiKey;
        }
        
        public String getPaddleocrSecretKey() {
            return paddleocrSecretKey;
        }
        
        public void setPaddleocrSecretKey(String paddleocrSecretKey) {
            this.paddleocrSecretKey = paddleocrSecretKey;
        }
        
        public String getPaddleocrLayoutApiUrl() {
            return paddleocrLayoutApiUrl != null ? paddleocrLayoutApiUrl 
                    : "https://28a0l9vdl4m4i7n4.aistudio-app.com/layout-parsing";
        }
        
        public void setPaddleocrLayoutApiUrl(String paddleocrLayoutApiUrl) {
            this.paddleocrLayoutApiUrl = paddleocrLayoutApiUrl;
        }
    }
}
