import json
from pathlib import Path

import pytest

from xiaozhi_eval.metrics import REPORTS, agent_report, build_report, latency, percentile

REPO = Path(__file__).resolve().parents[2]

def test_percentiles_use_linear_interpolation():
    assert percentile([1, 2, 3, 4], .5) == 2.5
    assert percentile([1, 2, 3, 4], .95) == pytest.approx(3.85)
    assert latency([9])["n"] == 1
    with pytest.raises(ValueError):
        latency([-1])
    with pytest.raises(ValueError):
        latency([float("nan")])

def test_reports_keep_injected_failures_and_model_turns_separate():
    result = build_report(REPO)
    for agent in result["agents"]:
        assert agent["executedTurns"] == 11
        assert agent["serverBooking"]["turns"] == 10
        assert agent["modelKnowledge"]["turns"] == 1
        t = agent["toolReturns"]
        assert t["injectedFailureCount"] == 1
        assert t["successful"] < t["attempted"]
        assert t["nonInjectedSuccessful"] == t["nonInjectedAttempted"]
    assert result["retrieval"]["baselineEvidenceRecall"] == .975
    assert result["retrieval"]["rerankedEvidenceRecall"] == 1
    assert result["correctiveChallenge"]["passed"] == 11
    assert len(result["correctiveChallenge"]["failures"]) == 1

def test_incomplete_recording_is_rejected(tmp_path):
    source = REPO / "evals/results" / REPORTS["sync"]
    for file in ("summary.json", "manual-review.json", "trials.jsonl"):
        (tmp_path / file).write_bytes((source / file).read_bytes())
    (tmp_path / "turns.jsonl").write_text("[]\n", encoding="utf-8")
    with pytest.raises(ValueError, match="count mismatch"):
        agent_report(tmp_path)
