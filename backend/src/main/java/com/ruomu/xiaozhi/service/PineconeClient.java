package com.ruomu.xiaozhi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PineconeClient {

    public static final int DIMENSION = 1024;

    private final ObjectMapper mapper;
    private final String apiKey;
    private final String indexHost;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public PineconeClient(
            ObjectMapper mapper,
            @Value("${PINECONE_API_KEY:}") String apiKey,
            @Value("${PINECONE_INDEX_HOST:}") String indexHost) {
        this.mapper = mapper;
        this.apiKey = apiKey.strip();
        this.indexHost = indexHost.strip();
    }

    public Stats stats() {
        JsonNode root = request("/describe_index_stats", Map.of());
        JsonNode dimension = root.path("dimension");
        JsonNode count = root.path("totalVectorCount");

        if (!dimension.isIntegralNumber()
                || !dimension.canConvertToInt()
                || dimension.intValue() != DIMENSION
                || !count.isIntegralNumber()
                || !count.canConvertToLong()
                || count.longValue() < 0) {
            throw badGateway("Pinecone 统计异常或索引维度不是 1024");
        }

        if (root.has("metric")
                && !"cosine".equals(root.path("metric").asText())) {
            throw badGateway("当前检索要求索引使用 cosine 相似度");
        }

        return new Stats(
                checkedHost().getHost(),
                DIMENSION,
                count.longValue()
        );
    }

    public Map<String, JsonNode> fetch(String namespace, List<String> ids) {
        Map<String, JsonNode> result = new LinkedHashMap<>();

        for (int start = 0; start < ids.size(); start += 50) {
            StringBuilder path = new StringBuilder("/vectors/fetch?namespace=")
                    .append(encode(namespace));

            for (String id : ids.subList(
                    start, Math.min(start + 50, ids.size()))) {
                path.append("&ids=").append(encode(id));
            }

            JsonNode vectors = request(path.toString(), null).path("vectors");

            if (!vectors.isObject()) {
                throw badGateway("Pinecone 返回的向量记录格式异常");
            }

            vectors.fields().forEachRemaining(
                    entry -> result.put(entry.getKey(), entry.getValue())
            );
        }

        return result;
    }

    public void upsert(String namespace, List<Map<String, Object>> vectors) {
        for (int start = 0; start < vectors.size(); start += 32) {
            var batch = vectors.subList(
                    start, Math.min(start + 32, vectors.size())
            );

            JsonNode count = request(
                    "/vectors/upsert",
                    Map.of("namespace", namespace, "vectors", batch)
            ).path("upsertedCount");

            if (!count.isIntegralNumber()
                    || count.longValue() != batch.size()) {
                throw badGateway("Pinecone 写入数量未确认，请稍后重新同步");
            }
        }
    }

    public JsonNode query(
            String namespace,
            String revision,
            float[] vector,
            int topK) {

        JsonNode matches = request("/query", Map.of(
                "namespace", namespace,
                "vector", vector,
                "topK", topK,
                "includeMetadata", true,
                "includeValues", false,
                "filter", Map.of("revision", Map.of("$eq", revision))
        )).path("matches");

        if (!matches.isArray()) {
            throw badGateway("Pinecone 返回的检索结果格式异常");
        }

        return matches;
    }

    private JsonNode request(String path, Object body) {
        if (apiKey.isBlank()
                || apiKey.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "请检查 PINECONE_API_KEY 环境变量并完全重启 IDEA"
            );
        }

        URI uri = checkedHost().resolve(path);

        try {
            var builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Api-Key", apiKey)
                    .header("X-Pinecone-Api-Version", "2025-10")
                    .header("Content-Type", "application/json");

            if (body == null) {
                builder.GET();
            } else {
                builder.POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(body)
                ));
            }

            var response = http.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() != 200) {
                String reason = switch (response.statusCode()) {
                    case 401, 403 ->
                            "Pinecone 拒绝访问，请检查密钥、所属项目和权限";
                    case 404 ->
                            "Pinecone 地址不存在，请检查索引 Host";
                    case 429 ->
                            "Pinecone 请求受到限制，请稍后重试并检查配额";
                    default ->
                            "Pinecone 请求失败，HTTP 状态码：" + response.statusCode();
                };

                throw badGateway(reason);
            }

            JsonNode root = mapper.readTree(response.body());

            if (root == null || !root.isObject()) {
                throw badGateway("Pinecone 返回的 JSON 格式异常");
            }

            return root;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Pinecone 请求被中断"
            );

        } catch (IOException e) {
            throw badGateway("Pinecone 网络请求或响应解析失败，请稍后重试");
        }
    }

    private URI checkedHost() {
        try {
            URI uri = URI.create(
                    indexHost.contains("://")
                            ? indexHost
                            : "https://" + indexHost
            );

            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || !uri.getHost().endsWith(".pinecone.io")
                    || uri.getPort() != -1
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }

            return uri;

        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "请检查 PINECONE_INDEX_HOST 环境变量，填写索引详情页的完整 Host"
            );
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static ResponseStatusException badGateway(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    public record Stats(
            String host,
            int dimension,
            long totalVectorCount) {
    }
}