# Tesseract OCR 配置说明

## 为什么使用 Tesseract？

相比多模态API（如Ollama），Tesseract OCR具有以下优势：
1. **本地识别**：无需网络请求，避免网络延迟
2. **速度快**：本地处理，响应时间短
3. **并发友好**：可以更好地利用多线程
4. **免费开源**：无需API密钥

## 安装 Tesseract

### Windows

1. 下载安装包：
   - 访问：https://github.com/UB-Mannheim/tesseract/wiki
   - 下载 Windows 安装包（推荐64位版本）

2. 安装：
   - 运行安装程序
   - 记住安装路径（默认：`C:\Program Files\Tesseract-OCR`）

3. 下载中文语言包：
   - 访问：https://github.com/tesseract-ocr/tessdata
   - 下载 `chi_sim.traineddata`（简体中文）
   - 将文件放到 Tesseract 安装目录的 `tessdata` 文件夹中
   - 例如：`C:\Program Files\Tesseract-OCR\tessdata\chi_sim.traineddata`

### Linux (Ubuntu/Debian)

```bash
sudo apt-get update
sudo apt-get install tesseract-ocr
sudo apt-get install tesseract-ocr-chi-sim  # 中文语言包
```

### macOS

```bash
brew install tesseract
brew install tesseract-lang  # 包含中文语言包
```

## 配置

在 `config.yaml` 中配置：

```yaml
multimodal:
  enabled: true
  recognition_method: "tesseract"  # 使用Tesseract OCR
  tesseract_data_path: ""  # 可选，为空则使用默认路径
  tesseract_language: "chi_sim+eng"  # 中文+英文
```

### 语言代码说明

- `chi_sim`：简体中文
- `chi_tra`：繁体中文
- `eng`：英文
- `chi_sim+eng`：中文+英文（推荐，可识别中英文混合文本）

### 自定义数据路径

如果Tesseract安装在非默认位置，可以指定数据路径：

```yaml
tesseract_data_path: "C:/Program Files/Tesseract-OCR/tessdata"
```

## 性能对比

根据测试，使用Tesseract OCR相比多模态API可以显著提升速度：

| 方法 | 图片识别时间（bbb.pdf） | 优势 |
|------|----------------------|------|
| 多模态API | ~79秒 | 识别准确率高，但需要网络 |
| Tesseract OCR | ~10-20秒（预估） | 本地识别，速度快，无需网络 |

## 注意事项

1. **首次使用**：Tesseract需要加载语言模型，第一次识别可能稍慢
2. **内存占用**：Tesseract会占用一定内存，但比多模态模型小得多
3. **识别准确率**：对于清晰文本，Tesseract准确率很高；对于复杂图表，可能不如多模态模型
