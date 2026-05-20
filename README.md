# PageIndex Java

PageIndex Java is a structured retrieval engine for long documents. It parses PDF and Word files into a hierarchical index with page ranges, node IDs, and summaries, then uses `PageSearch` to locate the most relevant nodes for a user question. Only the matched page excerpts are sent to the answer model.

The workflow does not require a vector database or fixed-size chunking. Retrieval is guided by document structure: table of contents, headings, PDF bookmarks, Word heading styles, node summaries, and page ranges. This is closer to how a human reads a long report: inspect the structure first, then jump to the relevant section.

## Highlights

- Structured indexing for PDF and Word `.docx` documents.
- PDF bookmark and outline extraction before falling back to TOC and heading rules.
- Word heading-style parsing for Heading 1/2/3 based document structure.
- Rule-based tree generation to reduce dependency on LLM calls.
- Node summaries with LLM generation and HanLP fallback.
- Image recognition support through Tesseract, PaddleOCR, multimodal models, or native Java logic.
- `PageSearch` retrieval with observable node selection, source excerpts, answer text, and token/character reduction metrics.
- Built-in accuracy reporting for page ranges, summaries, and overall index quality.

## Accuracy

For clean, well-structured PDF and Word documents, PageIndex Java is designed to reach **95%+ indexing accuracy**. The exact score depends on document quality, heading consistency, OCR quality, and the configured model.

Accuracy is not treated as a black box. The project includes an accuracy evaluator that reports:

```text
summaryAccuracy       How well node summaries match their source text
pageRangeAccuracy     Whether node page ranges are valid and aligned
overallAccuracy       Combined summary and page-range score
```

Run the evaluator on a PDF or Word document:

```bash
java -cp target/pageindex-java-1.0.0.jar com.pageindex.PageIndexAccuracyTester document.pdf ./test-results
java -cp target/pageindex-java-1.0.0.jar com.pageindex.PageIndexAccuracyTester document.docx ./test-results
```

The report is written to:

```text
./test-results/accuracy_report.json
```

For retrieval quality, inspect the `PageSearch` result:

```text
selectedNodeIds             Nodes selected by the navigation step
sourceSections              Matched section titles and page ranges
extractedText               Text actually sent to the answer model
answer                      Final answer generated from the excerpt
charReductionRatio          Character reduction versus full document text
tokenReductionRatio         Token reduction versus full document text
rawNodeSelectionResponse    Raw model response for node selection
```

With a labeled question set, these fields make it easy to measure whether failures come from index generation, node selection, excerpt extraction, or answer synthesis.

## Improvements Over The Python Version

The Java version is not just a direct port. It adds production-oriented capabilities that are useful for real document workflows:

- **Word support**: the Python flow is mainly PDF-oriented through `--pdf_path`; Java uses `DocumentParser` and supports both PDF and Word `.docx`.
- **Word structure parsing**: Java reads paragraph styles and heading levels, which is important for contracts, reports, proposals, and internal documents that are often authored as Word files.
- **Stronger PDF structure extraction**: Java uses PDF bookmarks/outlines first, then falls back to TOC pages and heading rules.
- **Accuracy reporting**: Java can generate JSON reports for page-range accuracy, summary accuracy, and overall index quality.
- **Observable retrieval**: `PageSearch` exposes selected nodes, source sections, extracted excerpts, raw node-selection responses, and context-reduction metrics.
- **Local-first options**: Java supports HanLP summary fallback, Tesseract/PaddleOCR image recognition, Ollama local models, and OpenAI-compatible services.

These additions make the Java version a stronger main project for continued development: it handles more document formats, exposes quality metrics, and supports full retrieval instead of only tree generation.

## Requirements

- Java 17+
- Maven 3.6+
- Optional: Ollama, OpenAI-compatible API, Tesseract, PaddleOCR

## Build

```bash
mvn clean package
```

The packaged JAR is created at:

```text
target/pageindex-java-1.0.0.jar
```

## Quick Start

Build a document structure index:

```bash
java -jar target/pageindex-java-1.0.0.jar --file_path document.pdf
```

Build an index and run retrieval:

```bash
java -jar target/pageindex-java-1.0.0.jar --file_path document.pdf --query "What is the main risk?"
```

Common CLI options:

```text
--file_path <path>          PDF or Word document path
--pdf_path <path>           Backward-compatible alias for --file_path
--config <path>             Custom config file path
--output <path>             Output directory, default ./results
--query <question>          Run PageSearch after indexing
--search <question>         Alias for --query
--include_node_text yes|no  Keep node source text in the structure JSON
```

Output files:

- `<doc>_structure.json`: document tree index
- `<doc>_search.json`: observable retrieval result, generated only when `--query` or `--search` is used

## Examples

Index a PDF:

```bash
java -jar target/pageindex-java-1.0.0.jar \
  --file_path tests/pdfs/2023-annual-report.pdf \
  --output ./results
```

Index a Word document:

```bash
java -jar target/pageindex-java-1.0.0.jar \
  --file_path ./docs/policy-manual.docx \
  --output ./results
```

Index and ask a question:

```bash
java -jar target/pageindex-java-1.0.0.jar \
  --file_path tests/pdfs/2023-annual-report.pdf \
  --query "What were the main revenue drivers?" \
  --output ./results
```

Use a local Ollama config:

```bash
java -jar target/pageindex-java-1.0.0.jar \
  --file_path ./docs/contract.docx \
  --query "What are the termination conditions?" \
  --config ./config-ollama.yaml
```

Include node source text in the structure output:

```bash
java -jar target/pageindex-java-1.0.0.jar \
  --file_path ./docs/report.pdf \
  --include_node_text yes
```

Example structure output:

```json
{
  "docName": "policy-manual.docx",
  "structure": [
    {
      "title": "Data Retention Policy",
      "startIndex": 4,
      "endIndex": 8,
      "nodeId": "0003",
      "summary": "This section defines retention windows, archive rules, and deletion responsibilities.",
      "nodes": [
        {
          "title": "Retention Windows",
          "startIndex": 5,
          "endIndex": 6,
          "nodeId": "0004",
          "summary": "Customer records are retained for seven years unless legal holds apply."
        }
      ]
    }
  ]
}
```

Example PageSearch output:

```json
{
  "query": "What are the termination conditions?",
  "docName": "contract.docx",
  "selectedNodeIds": ["0012"],
  "sourceSections": [
    "Termination [0012] (pages 14-16)"
  ],
  "selectionReasoning": "The summary explicitly mentions termination rights and notice periods.",
  "extractedTextCharCount": 4218,
  "fullTextCharCount": 58240,
  "charReductionRatio": 0.9276,
  "answer": "Either party may terminate for material breach after a 30-day cure period..."
}
```

Example accuracy report:

```json
{
  "totalNodes": 86,
  "nodesWithSummary": 86,
  "summaryAccurateCount": 83,
  "pageRangeAccurateCount": 85,
  "summaryAccuracy": 0.9651,
  "pageRangeAccuracy": 0.9883,
  "overallAccuracy": 0.9767
}
```

## Java API

Build an index:

```java
import com.pageindex.PageIndex;
import com.pageindex.model.Config;
import com.pageindex.utils.ConfigLoader;

Config config = ConfigLoader.loadDefaultConfig();
PageIndex pageIndex = new PageIndex(config);

PageIndex.IndexResult index = pageIndex.buildIndex("document.pdf").join();
```

Run retrieval with `PageSearch`:

```java
import com.pageindex.PageSearch;

PageSearch pageSearch = new PageSearch(config);
PageIndex.RetrievalResult result = pageSearch
        .search("document.pdf", "What is the main risk?", index)
        .join();

System.out.println(result.getAnswer());
System.out.println(result.getSelectedNodeIds());
System.out.println(result.getSourceSections());
System.out.println(result.getCharReductionRatio());
```

Main `RetrievalResult` fields:

```text
query                       User question
docName                     Document name
selectedNodeIds             Selected node IDs
sourceSections              Selected titles and page ranges
selectionReasoning          Node-selection reason
rawNodeSelectionResponse    Raw model response from node selection
extractedText               Source excerpt sent to the answer model
answer                      Final answer
fullTextCharCount           Full document character count
extractedTextCharCount      Extracted excerpt character count
charReductionRatio          Character reduction ratio
fullTextTokenCount          Estimated full document tokens
extractedTextTokenCount     Estimated extracted excerpt tokens
tokenReductionRatio         Token reduction ratio
elapsedMs                   Search elapsed time
```

## Configuration

The default configuration lives at `src/main/resources/config.yaml`. For production use, copy it to a local path and pass it through `--config`. Do not commit real API keys.

```yaml
model: "gpt-4o-2024-11-20"
model_provider: "openai"

openai:
  api_key: ""        # Prefer CHATGPT_API_KEY environment variable
  base_url: ""       # Leave empty for the default OpenAI endpoint
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
summary_method: "llm"       # llm or hanlp
if_add_doc_description: "no"
if_add_node_text: "no"
use_rule_based_tree: "yes"
```

## Supported Model Providers

- `openai`: OpenAI or any compatible `/v1/chat/completions` endpoint
- `ollama`: local or remote Ollama
- `deepspeed`: OpenAI-compatible DeepSpeed chat completions
- `custom`: custom OpenAI-compatible service

## Project Structure

```text
pageindex-java/
├── src/main/java/com/pageindex/
│   ├── PageIndex.java          # Builds document structure indexes
│   ├── PageSearch.java         # Runs retrieval over an existing tree
│   ├── PageIndexMain.java      # CLI entry point
│   ├── model/                  # TreeNode, PageContent, Config, etc.
│   ├── parser/                 # PDF / Word parsing
│   ├── tree/                   # TOC detection, rule-based tree building
│   ├── llm/                    # OpenAI, Ollama, DeepSpeed, custom clients
│   └── utils/                  # Config, JSON, token, summary utilities
└── src/main/resources/
    ├── config.yaml
    └── logback.xml
```

## Tests

Run the PageSearch test without external model dependencies:

```bash
mvn -Dtest=PageSearchTest test
```

The test uses a fake LLM and verifies node selection, source extraction, answer generation, and reduction metrics.

## Difference From Traditional RAG

Traditional RAG usually chunks the document, embeds each chunk, and retrieves by vector similarity. PageIndex Java builds a document tree first. At query time, the model reasons over the tree to select relevant nodes, and Java extracts only the corresponding page ranges.

This makes retrieval more explainable and reduces the amount of context sent to the answer model.

## License

Same as the original PageIndex project.
