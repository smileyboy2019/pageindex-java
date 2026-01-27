# PageIndex Java 使用示例

## 基本使用

### 1. 命令行使用

```bash
# 使用默认配置
java -jar pageindex-java-1.0.0.jar --pdf_path document.pdf

# 使用自定义配置
java -jar pageindex-java-1.0.0.jar --pdf_path document.pdf --config custom-config.yaml

# 指定输出目录
java -jar pageindex-java-1.0.0.jar --pdf_path document.pdf --output ./my-results
```

### 2. 编程方式使用

#### 基本示例

```java
import com.pageindex.PageIndex;
import com.pageindex.model.Config;
import com.pageindex.utils.ConfigLoader;
import java.util.concurrent.CompletableFuture;

public class Example {
    public static void main(String[] args) {
        // 加载配置
        Config config = ConfigLoader.loadDefaultConfig();
        
        // 创建 PageIndex 实例
        PageIndex pageIndex = new PageIndex(config);
        
        // 构建索引
        CompletableFuture<PageIndex.IndexResult> future = 
            pageIndex.buildIndex("document.pdf");
        
        // 获取结果
        PageIndex.IndexResult result = future.join();
        
        // 访问树结构
        System.out.println("Document: " + result.getDocName());
        System.out.println("Tree nodes: " + result.getStructure().size());
    }
}
```

#### 使用自定义配置

```java
import com.pageindex.PageIndex;
import com.pageindex.model.Config;
import com.pageindex.utils.ConfigLoader;

// 从文件加载配置
Config config = ConfigLoader.loadConfig("custom-config.yaml");

// 或者手动创建配置
Config config = new Config();
config.setModel("gpt-4o-2024-11-20");
config.setModelProvider("openai");

Config.OpenAIConfig openaiConfig = new Config.OpenAIConfig();
openaiConfig.setApiKey("your-api-key");
config.setOpenai(openaiConfig);

PageIndex pageIndex = new PageIndex(config);
```

#### 使用 Ollama

```java
Config config = new Config();
config.setModelProvider("ollama");

Config.OllamaConfig ollamaConfig = new Config.OllamaConfig();
ollamaConfig.setBaseUrl("http://localhost:11434");
ollamaConfig.setModel("llama2");
config.setOllama(ollamaConfig);

PageIndex pageIndex = new PageIndex(config);
PageIndex.IndexResult result = pageIndex.buildIndex("document.pdf").join();
```

## 配置说明

### 环境变量

- `CHATGPT_API_KEY`: OpenAI API 密钥（如果使用 OpenAI）

### 配置文件字段

- `model`: 使用的模型名称
- `model_provider`: 模型提供者（openai, ollama, deepspeed, custom）
- `toc_check_page_num`: 检查目录的页数（默认 20）
- `max_page_num_each_node`: 每个节点的最大页数（默认 10）
- `max_token_num_each_node`: 每个节点的最大 token 数（默认 20000）
- `if_add_node_id`: 是否添加节点ID（yes/no，默认 yes）
- `if_add_node_summary`: 是否添加节点摘要（yes/no，默认 yes）
- `if_add_doc_description`: 是否添加文档描述（yes/no，默认 no）
- `if_add_node_text`: 是否添加节点文本（yes/no，默认 no）

## 输出格式

生成的 JSON 文件格式：

```json
{
  "docName": "document.pdf",
  "docDescription": "Document description (if enabled)",
  "structure": [
    {
      "title": "Chapter 1",
      "startIndex": 1,
      "endIndex": 10,
      "nodeId": "0000",
      "summary": "Summary of chapter 1",
      "nodes": [
        {
          "title": "Section 1.1",
          "startIndex": 2,
          "endIndex": 5,
          "nodeId": "0001"
        }
      ]
    }
  ]
}
```
