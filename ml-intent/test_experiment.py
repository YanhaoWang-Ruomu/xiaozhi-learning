import json
from pathlib import Path
import joblib
import numpy as np
import pytest
import experiment as e

DATA = Path(__file__).parent / "data" / "intent-v1.jsonl"

@pytest.fixture(scope="module")
def trained(tmp_path_factory):
    output = tmp_path_factory.mktemp("intent")
    return e.run(DATA, output), output

def test_frozen_split_has_no_group_or_text_overlap():
    rows, splits = e.load_data(DATA)
    assert len(rows) == 180
    sets = [{r["group"] for r in splits[s]} for s in ("train", "validation", "test")]
    assert not (sets[0] & sets[1] or sets[0] & sets[2] or sets[1] & sets[2])
    assert [len(splits[s]) for s in ("train", "validation", "test")] == [120, 30, 30]

@pytest.mark.parametrize("corruption", ["duplicate_id", "duplicate_text", "cross_split", "unknown_label", "false_provenance"])
def test_dataset_rejects_invalid_or_leaking_rows(tmp_path, corruption):
    rows = [json.loads(l) for l in DATA.read_text(encoding="utf-8").splitlines()]
    if corruption == "duplicate_id": rows[1]["id"] = rows[0]["id"]
    if corruption == "duplicate_text": rows[1]["text"] = rows[0]["text"]
    if corruption == "cross_split": rows[-1]["group"] = rows[0]["group"]
    if corruption == "unknown_label": rows[0]["label"] = "DIAGNOSIS"
    if corruption == "false_provenance": rows[0]["human_reviewed"] = True
    path = tmp_path / "bad.jsonl"
    path.write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
    with pytest.raises(ValueError): e.load_data(path)

def test_vocabulary_only_sees_train():
    model = e.make_model("logistic", 2)
    _, splits = e.load_data(DATA)
    model.fit(*e.xy(splits["train"]))
    assert model.named_steps["tfidf"].transform(["独立保留字串𠀀𠀁𠀂"]).nnz == 0

def test_threshold_rejects_when_validation_has_no_accurate_accepts():
    probabilities = np.array([[.7, .3], [.6, .4]])
    threshold, trials = e.choose_threshold(probabilities, ["B", "B"], np.array(["A", "B"]))
    assert threshold == .9
    assert all(t["correct"] == 0 for t in trials)

def test_metrics_count_false_predictions():
    result = e.score(["GREETING", "BOOKING"], ["BOOKING", "BOOKING"])
    assert result["accuracy"] == .5
    assert result["confusion_matrix"][0][2] == 1
    assert result["confusion_matrix"][2][2] == 1

@pytest.mark.parametrize("text", ["", " " * 4, "字" * 2001, "🧩🧩", "你好我胸口疼想预约"])
def test_advisory_never_authorizes_actions_and_falls_back(trained, text):
    _, output = trained
    hint = e.advisory(joblib.load(output / "intent-model.joblib"), text)
    assert hint["advisory_only"] and not hint["action_authorized"]
    assert hint["fallback"] is not None
    assert not hint["scores_calibrated"]

def test_selection_uses_validation_and_reports_every_test_sample(trained):
    result, output = trained
    ranked = sorted(result["results"].items(), key=lambda pair: (pair[1].get("validation", {}).get("macro_f1", -1), pair[0] == "logistic"))
    assert result["selected"] == ranked[-1][0]
    for r in result["results"].values():
        assert sum(map(sum, r["test"]["confusion_matrix"])) == 30
        assert r["latency"]["samples"] == 300
    assert len((output / "test-predictions.csv").read_text(encoding="utf-8-sig").splitlines()) == 31

def test_model_round_trip_and_report_escape(trained):
    result, output = trained
    bundle = joblib.load(output / "intent-model.joblib")
    _, splits = e.load_data(DATA)
    predictions = bundle["model"].predict(e.xy(splits["test"])[0])
    assert e.score(e.xy(splits["test"])[1], predictions)["macro_f1"] == result["results"][result["selected"]]["test"]["macro_f1"]
    assert (output / "report.html").read_text(encoding="utf-8").count("<script") == 0
    assert len(result["shadow_challenges"]) == 6

def test_medical_rules_precede_booking():
    assert e.keyword_baseline("胸口痛，想预约") == "MEDICAL_MESSAGE"
    assert e.keyword_baseline("查询演示医生甲的排班") == "BOOKING"


def test_archived_metrics_match_raw_predictions_and_current_data():
    import csv
    import hashlib
    archive = Path(__file__).parent.parent / "evals" / "ml-intent" / "20261009"
    metrics = json.loads((archive / "metrics.json").read_text(encoding="utf-8"))
    records = list(csv.DictReader((archive / "test-predictions.csv").open(encoding="utf-8-sig", newline="")))
    _, splits = e.load_data(DATA)
    assert {r["id"] for r in records} == {r["id"] for r in splits["test"]}
    assert metrics["dataset"]["sha256"] == hashlib.sha256(DATA.read_bytes()).hexdigest()
    for name, result in metrics["results"].items():
        independent_accuracy = sum(r["label"] == r[name] for r in records) / len(records)
        assert independent_accuracy == result["test"]["accuracy"]
        matrix = [[sum(r["label"] == a and r[name] == b for r in records) for b in e.LABELS] for a in e.LABELS]
        assert matrix == result["test"]["confusion_matrix"]
