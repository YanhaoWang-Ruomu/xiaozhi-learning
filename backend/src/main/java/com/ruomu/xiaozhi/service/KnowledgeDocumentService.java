package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.KnowledgePreviewResponse;
import com.ruomu.xiaozhi.dto.KnowledgePreviewResponse.Chunk;
import com.ruomu.xiaozhi.dto.KnowledgePreviewResponse.DocumentSummary;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.BlankDocumentException;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

@Service
public class KnowledgeDocumentService {

    private static final Logger log =
            LoggerFactory.getLogger(KnowledgeDocumentService.class);

    private static final String ROOT = "knowledge/";
    private static final String CATALOG = ROOT + "catalog.txt";

    // 以下两个数值的单位是字符，不是 token。
    private static final int MAX_SEGMENT_SIZE = 300;
    private static final int MAX_OVERLAP_SIZE = 40;

    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_PDF_BYTES = 10 * 1024 * 1024;
    private static final int MAX_PDF_TEXT_CHARS = 1_000_000;

    private final KnowledgePreviewResponse preview;

    public KnowledgeDocumentService() {
        try {
            List<String> sources = readCatalog();
            List<Chunk> chunks = new ArrayList<>();
            List<DocumentSummary> documents = new ArrayList<>();

            for (String source : sources) {
                String text = source.toLowerCase(Locale.ROOT).endsWith(".pdf")
                        ? readPdf(source)
                        : readUtf8(source);

                if (text.isBlank()) {
                    throw new IllegalArgumentException(
                            "知识文件内容为空：" + source
                    );
                }

                // 使用资源流读取，兼容 IDEA 运行和 JAR 包运行。
                Document document;

                try (InputStream input = new ByteArrayInputStream(
                        text.getBytes(StandardCharsets.UTF_8))) {

                    document = new TextDocumentParser(
                            StandardCharsets.UTF_8
                    ).parse(input);
                }

                document.metadata().put("source", source);
                document.metadata().put("document_type", "DEMO");

                // 每份文档独立切分，不将不同文件混入同一片段。
                List<TextSegment> segments = DocumentSplitters
                        .recursive(MAX_SEGMENT_SIZE, MAX_OVERLAP_SIZE)
                        .split(document);

                if (segments.isEmpty()) {
                    throw new IllegalArgumentException(
                            "知识文件没有有效片段：" + source
                    );
                }

                int firstIndex = chunks.size();

                for (TextSegment segment : segments) {
                    chunks.add(new Chunk(
                            chunks.size(),
                            source,
                            segment.text().length(),
                            segment.text()
                    ));
                }

                documents.add(new DocumentSummary(
                        source,
                        text.length(),
                        firstIndex,
                        segments.size()
                ));

                log.info(
                        "知识文件加载完成：source={}, chunks={}",
                        source,
                        segments.size()
                );
            }

            preview = new KnowledgePreviewResponse(
                    CATALOG,
                    chunks.size(),
                    MAX_SEGMENT_SIZE,
                    MAX_OVERLAP_SIZE,
                    List.copyOf(chunks),
                    documents.size(),
                    List.copyOf(documents)
            );

            log.info(
                    "多文档知识库加载完成：documents={}, chunks={}",
                    preview.documentCount(),
                    preview.chunkCount()
            );

        } catch (IOException | RuntimeException e) {
            // 不静默跳过问题文件，避免误以为全部资料已成功加载。
            throw new IllegalStateException(
                    "知识库加载失败：" + e.getMessage()
                            + "。请检查 src/main/resources/knowledge/catalog.txt"
                            + " 及列出的知识文件",
                    e
            );
        }
    }

    public KnowledgePreviewResponse preview() {
        return preview;
    }

    private static List<String> readCatalog() throws IOException {
        // 固定按文件名排序，调整清单行顺序不会改变片段顺序。
        TreeSet<String> sources = new TreeSet<>();
        String[] lines = readUtf8(CATALOG).split("\n");

        for (int i = 0; i < lines.length; i++) {
            String filename = lines[i].strip();

            if (filename.isEmpty() || filename.startsWith("#")) {
                continue;
            }

            String lower = filename.toLowerCase(Locale.ROOT);

            if (filename.startsWith(".")
                    || filename.contains("/")
                    || filename.contains("\\")
                    || filename.contains(":")
                    || filename.chars().anyMatch(Character::isISOControl)
                    || !(lower.endsWith(".txt")
                    || lower.endsWith(".md")
                    || lower.endsWith(".pdf"))
                    || lower.equals("catalog.txt")) {

                throw new IllegalArgumentException(
                        "清单第 " + (i + 1)
                                + " 行无效：只填写 knowledge 目录内的"
                                + " TXT、MD 或 PDF 文件名：" + filename
                );
            }

            if (!sources.add(ROOT + filename)) {
                throw new IllegalArgumentException(
                        "清单重复列出了文件：" + filename
                );
            }
        }

        if (sources.isEmpty()) {
            throw new IllegalArgumentException("知识资料清单为空");
        }

        return List.copyOf(sources);
    }

    private static String readPdf(String source) throws IOException {
        // 仅提取 PDF 文本层，当前不执行 OCR。
        try (InputStream input =
                     new ClassPathResource(source).getInputStream()) {

            byte[] bytes = input.readNBytes(MAX_PDF_BYTES + 1);

            if (bytes.length > MAX_PDF_BYTES) {
                throw new IOException(
                        "PDF 超过当前演示上限 10 MiB"
                );
            }

            Document parsed;

            try (InputStream pdfInput =
                         new ByteArrayInputStream(bytes)) {

                parsed = new ApachePdfBoxDocumentParser().parse(pdfInput);
            }

            String text = normalizeText(parsed.text());

            if (text.isBlank()) {
                throw new BlankDocumentException();
            }

            if (text.length() > MAX_PDF_TEXT_CHARS) {
                throw new IOException(
                        "PDF 提取文字超过当前演示上限 100 万字符，请拆分资料"
                );
            }

            return text;

        } catch (BlankDocumentException e) {
            throw new IOException(
                    "PDF 没有可提取的文字：" + source
                            + "；可能是空白文件或扫描件，当前尚未接入 OCR",
                    e
            );

        } catch (IOException | RuntimeException e) {
            throw new IOException(
                    "无法解析 PDF " + source
                            + "：请检查文件是否完整、是否需要密码；原因："
                            + e.getMessage(),
                    e
            );
        }
    }

    private static String normalizeText(String text) {
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        // 保留段落和行内空格，统一平台换行并去除首尾空白。
        return text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
    }

    private static String readUtf8(String source) throws IOException {
        try (InputStream input =
                     new ClassPathResource(source).getInputStream()) {

            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);

            if (bytes.length > MAX_FILE_BYTES) {
                throw new IOException(
                        "单个知识文件超过当前演示上限 1 MiB：" + source
                );
            }

            // 编码不正确时明确报错，不悄悄替换成乱码字符。
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();

            return normalizeText(text);

        } catch (IOException e) {
            throw new IOException(
                    "无法读取 UTF-8 资源 " + source
                            + "：" + e.getMessage(),
                    e
            );
        }
    }
}