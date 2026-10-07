package com.ruomu.xiaozhi.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruomu.xiaozhi.config.DashScopeNetworkConfig;
import com.ruomu.xiaozhi.config.KnowledgeEmbeddingConfig;
import com.ruomu.xiaozhi.dto.KnowledgePreviewResponse;
import com.ruomu.xiaozhi.dto.KnowledgeSearchResponse.Match;
import com.ruomu.xiaozhi.service.KnowledgeDocumentService;
import com.ruomu.xiaozhi.service.KnowledgeRetrievalAugmentor;
import com.ruomu.xiaozhi.service.KnowledgeSearchService;
import com.ruomu.xiaozhi.service.PineconeClient;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Test-side, read-only evaluator. No Spring context, chat model or business database. */
public final class RetrievalEval {
    static final ObjectMapper JSON = new ObjectMapper();
    static final double MIN_SCORE = 0.80;
    static final int CHAT_LIMIT = 2;
    public record Evidence(String source, String quote) {}
    public record Case(String id, String category, String query, List<Evidence> evidence) {}
    public record Grade(Double recallAt3, Double reciprocalRankAt3,
                        Double acceptedRecallAt2, Boolean falseAcceptance) {}
    public record Trial(String id, String status, long durationMs, List<Match> candidates,
                        Grade grade, String errorType) {}

    static String normalize(String text) { return text.replaceAll("\\s+", ""); }

    static List<Case> load(Path path, KnowledgePreviewResponse preview) throws Exception {
        List<Case> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> queries = new HashSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            Case c = JSON.readValue(line, Case.class);
            if (c.id() == null || !c.id().matches("[RN][0-9]{2}") || !ids.add(c.id())
                    || c.query() == null || c.query().isBlank() || c.query().length() > 500
                    || !queries.add(normalize(c.query())) || c.evidence() == null
                    || c.category() == null || c.category().isBlank()) {
                throw new IllegalArgumentException("Invalid or duplicate evaluation case");
            }
            if (c.id().startsWith("N") != c.evidence().isEmpty()) {
                throw new IllegalArgumentException("Negative cases must have no gold evidence: " + c.id());
            }
            Set<Evidence> unique = new HashSet<>();
            for (Evidence e : c.evidence()) {
                if (e.source() == null || e.quote() == null || normalize(e.quote()).length() < 6
                        || !unique.add(e) || preview.chunks().stream().noneMatch(chunk ->
                        e.source().equals(chunk.source())
                                && normalize(chunk.text()).contains(normalize(e.quote())))) {
                    throw new IllegalArgumentException("Gold evidence missing from actual chunks: " + c.id());
                }
            }
            cases.add(c);
        }
        if (cases.isEmpty()) throw new IllegalArgumentException("Empty evaluation dataset");
        return List.copyOf(cases);
    }

    static boolean supports(Match m, Evidence e) {
        return m.source().equals(e.source()) && normalize(m.text()).contains(normalize(e.quote()));
    }

    static Grade grade(Case c, List<Match> raw) {
        // Match the production order; reject malformed results instead of silently rewarding them.
        Set<Integer> ids = new HashSet<>();
        double previous = Double.POSITIVE_INFINITY;
        for (Match m : raw) {
            if (!ids.add(m.index()) || !Double.isFinite(m.score()) || m.score() < 0
                    || m.score() > 1 || m.score() > previous) {
                throw new IllegalArgumentException("Duplicate, invalid or unordered candidate");
            }
            previous = m.score();
        }
        if (raw.size() > 3) throw new IllegalArgumentException("Unexpected production topK");
        List<Match> accepted = raw.stream().filter(m -> m.score() >= MIN_SCORE).limit(CHAT_LIMIT).toList();
        if (c.evidence().isEmpty()) return new Grade(null, null, null, !accepted.isEmpty());
        double recall = recall(c.evidence(), raw);
        double rr = 0;
        for (int i = 0; i < raw.size(); i++) {
            Match m = raw.get(i);
            if (c.evidence().stream().anyMatch(e -> supports(m, e))) { rr = 1.0 / (i + 1); break; }
        }
        return new Grade(recall, rr, recall(c.evidence(), accepted), null);
    }

    private static double recall(List<Evidence> gold, List<Match> matches) {
        return gold.stream().filter(e -> matches.stream().anyMatch(m -> supports(m, e))).count()
                / (double) gold.size();
    }

    static Map<String, Object> summarize(List<Trial> trials) {
        var positives = trials.stream().filter(t -> t.status().equals("OK") && t.grade().recallAt3() != null).toList();
        var negatives = trials.stream().filter(t -> t.status().equals("OK") && t.grade().falseAcceptance() != null).toList();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("planned", trials.size());
        s.put("completed", positives.size() + negatives.size());
        s.put("errors", trials.stream().filter(t -> t.status().equals("ERROR")).count());
        s.put("notRun", trials.stream().filter(t -> t.status().equals("NOT_RUN")).count());
        s.put("positiveDenominator", positives.size());
        s.put("negativeDenominator", negatives.size());
        s.put("macroEvidenceRecallAt3", positives.isEmpty() ? null : positives.stream().mapToDouble(t -> t.grade().recallAt3()).average().orElseThrow());
        s.put("mrrAt3", positives.isEmpty() ? null : positives.stream().mapToDouble(t -> t.grade().reciprocalRankAt3()).average().orElseThrow());
        s.put("macroAcceptedEvidenceRecallAt2", positives.isEmpty() ? null : positives.stream().mapToDouble(t -> t.grade().acceptedRecallAt2()).average().orElseThrow());
        s.put("negativeFalseAcceptanceRate", negatives.isEmpty() ? null : negatives.stream().filter(t -> t.grade().falseAcceptance()).count() / (double) negatives.size());
        return s;
    }

    static void checkProductionPolicy() throws Exception {
        var score = KnowledgeRetrievalAugmentor.class.getDeclaredField("CHAT_MIN_SCORE");
        var limit = KnowledgeRetrievalAugmentor.class.getDeclaredField("CHAT_MAX_RESULTS");
        score.setAccessible(true);
        limit.setAccessible(true);
        if (score.getDouble(null) != MIN_SCORE || limit.getInt(null) != CHAT_LIMIT) {
            throw new IllegalStateException("Production retrieval policy changed; update evaluator and version");
        }
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !(args[0].equals("validate") || args[0].equals("live"))) {
            throw new IllegalArgumentException("Usage: validate|live dataset.jsonl output-directory git-head tracked-dirty");
        }
        checkProductionPolicy();
        Path dataset = Path.of(args[1]);
        var documents = new KnowledgeDocumentService();
        List<Case> cases = load(dataset, documents.preview());
        System.out.println("EVAL_DATASET_VALID cases=" + cases.size());
        if (args[0].equals("validate")) return;

        // Initialize only the same network/embedding/search classes used by the application.
        // Deliberately never call sync(), an AI Service, MongoDB, MySQL, or appointment tools.
        requiredEnv("DASHSCOPE_API_KEY");
        var pinecone = new PineconeClient(JSON, requiredEnv("PINECONE_API_KEY"), requiredEnv("PINECONE_INDEX_HOST"));
        DashScopeNetworkConfig.dashScopeNetworkPolicy(new StandardEnvironment()).postProcessBeanFactory(null);
        var search = new KnowledgeSearchService(documents, new KnowledgeEmbeddingConfig().knowledgeEmbeddingModel(), pinecone);

        Path output = Path.of(args[2]);
        Files.createDirectory(output); // Refuse to overwrite another run.
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "retrieval-eval-v1");
        report.put("startedAt", Instant.now().toString());
        report.put("mode", "LIVE_EMBEDDING_AND_PINECONE_NO_GENERATION");
        report.put("gitHead", args[3]);
        report.put("trackedDirty", Boolean.parseBoolean(args[4]));
        report.put("datasetSha256", sha(Files.readAllBytes(dataset)));
        report.put("knowledgePreviewSha256", sha(JSON.writeValueAsBytes(documents.preview())));
        report.put("embeddingModel", KnowledgeEmbeddingConfig.MODEL_NAME);
        report.put("policy", Map.of("candidateTopK", 3, "acceptedTopK", CHAT_LIMIT, "normalizedMinimumScore", MIN_SCORE));
        report.put("qualityGate", "UNSET_BASELINE_ONLY");
        List<Trial> trials = new ArrayList<>();
        int errors = 0;
        for (Case c : cases) {
            Trial t;
            if (errors >= 2) {
                t = new Trial(c.id(), "NOT_RUN", 0, List.of(), null, "StoppedAfterTwoErrors");
            } else {
                long start = System.nanoTime();
                try {
                    var result = search.search(c.query());
                    t = new Trial(c.id(), "OK", (System.nanoTime() - start) / 1_000_000,
                            result.matches(), grade(c, result.matches()), null);
                } catch (RuntimeException ex) {
                    errors++;
                    // Do not persist exception messages, SDK payloads, headers or credentials.
                    t = new Trial(c.id(), "ERROR", (System.nanoTime() - start) / 1_000_000,
                            List.of(), null, ex.getClass().getSimpleName());
                }
            }
            trials.add(t);
            Files.writeString(output.resolve("trials.jsonl"), JSON.writeValueAsString(t) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("EVAL_CASE id=" + t.id() + " status=" + t.status() + " durationMs=" + t.durationMs());
        }
        report.put("finishedAt", Instant.now().toString());
        report.put("status", errors == 0 ? "COMPLETE" : "INCOMPLETE");
        report.put("summary", summarize(trials));
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("summary.json").toFile(), report);
        System.out.println("EVAL_REPORT=" + output.toAbsolutePath());
        System.out.println(JSON.writeValueAsString(report.get("summary")));
        // Force SDK worker threads to terminate. This is a standalone CLI, never a server entry point.
        System.exit(errors == 0 ? 0 : 2);
    }
}
