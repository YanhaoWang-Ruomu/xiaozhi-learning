package com.ruomu.xiaozhi.eval;

import com.ruomu.xiaozhi.dto.KnowledgeSearchResponse.Match;
import com.ruomu.xiaozhi.service.KnowledgeDocumentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static com.ruomu.xiaozhi.eval.RetrievalEval.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalEvalTest {
    @TempDir Path temp;
    private static Evidence gold(String quote) { return new Evidence("source", quote); }
    private static Case positive() { return new Case("R01", "fixture", "question", List.of(gold("first fact"), gold("second fact"))); }
    private static Match match(int i, double score, String text) { return new Match(i, "source", score, text); }

    @Test void datasetReferencesActualParsedDocumentsIncludingPdf() throws Exception {
        Path file = Path.of("../evals/retrieval-v1.jsonl");
        if (!Files.exists(file)) file = Path.of("evals/retrieval-v1.jsonl");
        var cases = load(file, new KnowledgeDocumentService().preview());
        assertEquals(24, cases.size());
        assertEquals(4, cases.stream().filter(c -> c.evidence().isEmpty()).count());
        assertEquals(4, cases.stream().flatMap(c -> c.evidence().stream()).map(Evidence::source).distinct().count());
    }

    @Test void partialRecallAndReciprocalRankUseGoldDenominator() {
        Grade g = grade(positive(), List.of(match(0, .99, "irrelevant"), match(1, .9, "first fact")));
        assertEquals(.5, g.recallAt3());
        assertEquals(.5, g.reciprocalRankAt3());
        assertEquals(.5, g.acceptedRecallAt2());
    }

    @Test void repeatedSupportDoesNotDoubleCountOneGoldFact() {
        Grade g = grade(positive(), List.of(match(0, .9, "first fact"), match(1, .85, "first fact")));
        assertEquals(.5, g.recallAt3());
    }

    @Test void wrongSourceDoesNotCountEvenWhenTextMatches() {
        assertEquals(0, grade(positive(), List.of(new Match(0, "wrong", .99, "first fact second fact"))).recallAt3());
    }

    @Test void thresholdAndChatLimitCanRemoveCorrectCandidate() {
        var g = grade(positive(), List.of(match(0, .99, "other"), match(1, .9, "other"), match(2, .85, "first fact second fact")));
        assertEquals(1, g.recallAt3());
        assertEquals(1.0 / 3, g.reciprocalRankAt3());
        assertEquals(0, g.acceptedRecallAt2());
        assertEquals(0, grade(positive(), List.of(match(0, .799, "first fact"))).acceptedRecallAt2());
        assertEquals(.5, grade(positive(), List.of(match(0, .80, "first fact"))).acceptedRecallAt2());
    }

    @Test void negativesMeasureAcceptanceNotFabrication() {
        var negative = new Case("N01", "out_of_domain", "unrelated", List.of());
        assertTrue(grade(negative, List.of(match(0, .8, "text"))).falseAcceptance());
        assertFalse(grade(negative, List.of(match(0, .799, "text"))).falseAcceptance());
        assertFalse(grade(negative, List.of()).falseAcceptance());
        assertNull(grade(negative, List.of()).recallAt3());
    }

    @Test void errorsAreNotSuccessfulAbstentionsOrZeroQualityTrials() {
        var trials = List.of(new Trial("N01", "ERROR", 100, List.of(), null, "IOException"),
                new Trial("R01", "NOT_RUN", 0, List.of(), null, "Stopped"),
                new Trial("R02", "OK", 1, List.of(), new Grade(.5, .5, 0.0, null), null));
        var summary = summarize(trials);
        assertEquals(3, summary.get("planned"));
        assertEquals(1, summary.get("completed"));
        assertEquals(1L, summary.get("errors"));
        assertEquals(1L, summary.get("notRun"));
        assertEquals(1, summary.get("positiveDenominator"));
        assertEquals(0, summary.get("negativeDenominator"));
        assertNull(summary.get("negativeFalseAcceptanceRate"));
        assertEquals(.5, summary.get("macroEvidenceRecallAt3"));
    }

    @Test void malformedRankingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> grade(positive(), List.of(match(0, .9, "x"), match(0, .8, "x"))));
        assertThrows(IllegalArgumentException.class, () -> grade(positive(), List.of(match(0, Double.NaN, "x"))));
        assertThrows(IllegalArgumentException.class, () -> grade(positive(), List.of(match(0, .8, "x"), match(1, .9, "x"))));
    }

    @Test void missingGoldAndDuplicateCasesFailBeforeCloudCalls() throws Exception {
        var preview = new KnowledgeDocumentService().preview();
        Path file = temp.resolve("bad.jsonl");
        Files.writeString(file, JSON.writeValueAsString(positive()));
        assertThrows(IllegalArgumentException.class, () -> load(file, preview));
        String negative = JSON.writeValueAsString(new Case("N01", "out_of_domain", "question", List.of()));
        Files.writeString(file, negative + "\n" + negative);
        assertThrows(IllegalArgumentException.class, () -> load(file, preview));
    }

    @Test void evaluatorPolicyMatchesProduction() throws Exception { checkProductionPolicy(); }
}
