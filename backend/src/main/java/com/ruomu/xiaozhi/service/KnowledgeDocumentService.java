package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.KnowledgePreviewResponse;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeDocumentService {

    private static final Logger log =
            LoggerFactory.getLogger(KnowledgeDocumentService.class);

    private static final String RESOURCE_PATH =
            "knowledge/demo-guide.txt";

    private static final int MAX_SEGMENT_SIZE = 300;
    private static final int MAX_OVERLAP_SIZE = 40;

    private final KnowledgePreviewResponse preview;

    public KnowledgeDocumentService() {
        ClassPathResource resource =
                new ClassPathResource(RESOURCE_PATH);

        try (InputStream input = resource.getInputStream()) {
            // 使用 UTF-8 解析纯文本文件。
            Document document =
                    new TextDocumentParser(StandardCharsets.UTF_8)
                            .parse(input);

            // 来源信息会传递到分段结果中。
            document.metadata().put("source", RESOURCE_PATH);
            document.metadata().put("document_type", "DEMO");

            // 这两个参数以字符为单位，不是 token 数量。
            List<TextSegment> segments = DocumentSplitters
                    .recursive(MAX_SEGMENT_SIZE, MAX_OVERLAP_SIZE)
                    .split(document);

            List<KnowledgePreviewResponse.Chunk> chunks =
                    new ArrayList<>();

            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);

                chunks.add(new KnowledgePreviewResponse.Chunk(
                        i,
                        segment.metadata().getString("source"),
                        segment.text().length(),
                        segment.text()
                ));
            }

            preview = new KnowledgePreviewResponse(
                    RESOURCE_PATH,
                    chunks.size(),
                    MAX_SEGMENT_SIZE,
                    MAX_OVERLAP_SIZE,
                    List.copyOf(chunks)
            );

            log.info(
                    "演示知识文档加载完成：source={}, chunks={}",
                    RESOURCE_PATH,
                    chunks.size()
            );
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "无法加载演示知识文档，请检查 src/main/resources/"
                            + RESOURCE_PATH
                            + " 是否存在、内容非空且保存为 UTF-8",
                    e
            );
        }
    }

    public KnowledgePreviewResponse preview() {
        return preview;
    }
}