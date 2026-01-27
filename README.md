# PageIndex Java

Java implementation of PageIndex - A vectorless, reasoning-based RAG system that builds hierarchical tree indexes from long documents.

## Features

- **No Vector DB**: Uses document structure and LLM reasoning for retrieval
- **No Chunking**: Documents are organized into natural sections
- **Human-like Retrieval**: Simulates how human experts navigate documents
- **Multi-LLM Support**: Supports OpenAI, Ollama, DeepSpeed, and custom APIs

## Requirements

- Java 17 or higher
- Maven 3.6+

## Building

```bash
mvn clean package
```

This will create a JAR file in `target/pageindex-java-1.0.0.jar`.

## Configuration

Create a `config.yaml` file (or use the default in `src/main/resources/config.yaml`):

```yaml
model: "gpt-4o-2024-11-20"
model_provider: "openai"

openai:
  api_key: "your-api-key"  # Or set CHATGPT_API_KEY environment variable
  base_url: ""
  temperature: 0

ollama:
  base_url: "http://localhost:11434"
  model: "llama2"
  temperature: 0
  timeout: 60

toc_check_page_num: 20
max_page_num_each_node: 10
max_token_num_each_node: 20000
if_add_node_id: "yes"
if_add_node_summary: "yes"
if_add_doc_description: "no"
if_add_node_text: "no"
```

## Usage

### Command Line

```bash
java -jar target/pageindex-java-1.0.0.jar --pdf_path document.pdf
```

### Programmatic API

```java
import com.pageindex.PageIndex;
import com.pageindex.model.Config;
import com.pageindex.utils.ConfigLoader;

// Load configuration
Config config = ConfigLoader.loadDefaultConfig();

// Create PageIndex instance
PageIndex pageIndex = new PageIndex(config);

// Build index
PageIndex.IndexResult result = pageIndex.buildIndex("document.pdf").join();

// Access tree structure
List<TreeNode> tree = result.getStructure();
```

## Supported LLM Providers

- **OpenAI**: Set `model_provider: "openai"` and configure API key
- **Ollama**: Set `model_provider: "ollama"` and configure base URL
- **DeepSpeed**: Set `model_provider: "deepspeed"` and configure base URL
- **Custom**: Set `model_provider: "custom"` and configure custom endpoint

## Project Structure

```
pageindex-java/
├── src/main/java/com/pageindex/
│   ├── model/          # Data models (TreeNode, Config, etc.)
│   ├── parser/          # PDF parser
│   ├── llm/            # LLM clients (OpenAI, Ollama, DeepSpeed)
│   ├── tree/           # Tree building and parsing
│   ├── utils/          # Utility classes
│   ├── PageIndex.java  # Main API class
│   └── PageIndexMain.java  # CLI entry point
└── src/main/resources/
    └── config.yaml     # Default configuration
```

## Differences from Python Version

- Token counting uses character estimation (full BPE implementation can be added)
- Some advanced features may be simplified
- Async operations use CompletableFuture instead of asyncio

## License

Same as the original PageIndex project.
