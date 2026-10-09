"""Recompute project metrics from recorded trials; never call a cloud model."""
import argparse
import hashlib
import json
import math
import platform
import statistics
from pathlib import Path

REPORTS = {
    "sync": "agent-multiturn-sync-20261008-184748",
    "stream": "agent-multiturn-stream-20261008-185308",
    "rerank": "dedicated-rerank-20261008-183756",
    "challenge": "evidence-challenge-20261008-185227",
}

def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))

def read_lines(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]

def percentile(values, q):
    ordered = sorted(values)
    pos = (len(ordered) - 1) * q
    lo, hi = math.floor(pos), math.ceil(pos)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (pos - lo)

def latency(values, definition='recorded evaluation turn duration; not TTFT or load-test latency'):
    if not values:
        return {"n": 0, "meanMs": None, "p50Ms": None, "p95Ms": None}
    if any(not isinstance(v, (int, float)) or not math.isfinite(v) or v < 0 for v in values):
        raise ValueError("Invalid recorded duration")
    return {"n": len(values), "meanMs": statistics.mean(values),
            "p50Ms": percentile(values, .5), "p95Ms": percentile(values, .95),
            "minMs": min(values), "maxMs": max(values),
            "definition": definition}

def agent_report(path):
    turns = read_lines(path / "turns.jsonl")
    summary = read_json(path / "summary.json")
    trials = read_lines(path / "trials.jsonl")
    review = read_json(path / "manual-review.json")
    if len(turns) != summary["completedTurns"] or len(trials) != summary["recordedTrials"]:
        raise ValueError("Report count mismatch")
    tools = []
    for turn in turns:
        for tool in turn["toolExecutions"]:
            result = json.loads(tool["result"])
            status = result.get("status")
            if tool["name"] == "queryAppointmentSessions":
                expected = "DEMO_DATA"
            elif tool["name"] == "createAppointmentDraft":
                expected = "PENDING_CONFIRMATION"
            else:
                raise ValueError("Unknown tool metric; define its success contract first")
            tools.append({"name": tool["name"], "scenario": turn["scenario"], "status": status,
                          "successfulReturn": status == expected,
                          "injectedFailure": status == "QUERY_FAILED" and turn["faultInvocations"] > 0})
    normal = [t for t in tools if not t["injectedFailure"]]
    success = sum(t["successfulReturn"] for t in tools)
    normal_success = sum(t["successfulReturn"] for t in normal)
    deterministic = [t for t in turns if t["selectedRoute"] == "SERVER_BOOKING_FLOW"]
    model = [t for t in turns if t["selectedRoute"] != "SERVER_BOOKING_FLOW"]
    return {
        "recording": path.name, "recordedAt": summary["at"], "transport": summary["transport"],
        "plannedTurns": summary["plannedTurns"], "executedTurns": len(turns),
        "automatedPassedTrials": summary["automatedPassedTrials"], "recordedTrials": len(trials),
        "reviewVerdict": review["verdict"],
        "toolReturns": {"successful": success, "attempted": len(tools),
                        "rate": success / len(tools) if tools else None,
                        "nonInjectedSuccessful": normal_success, "nonInjectedAttempted": len(normal),
                        "nonInjectedRate": normal_success / len(normal) if normal else None,
                        "injectedFailureCount": sum(t["injectedFailure"] for t in tools),
                        "definition": "valid tool return status / recorded tool executions; not autonomous tool-selection accuracy",
                        "executions": tools},
        "serverBooking": {"turns": len(deterministic), "latency": latency([t["durationMs"] for t in deterministic])},
        "modelKnowledge": {"turns": len(model), "latency": latency([t["durationMs"] for t in model]),
                           "tokens": sum(t["tokenUsage"]["total"] for t in model),
                           "definition": "tokens logged on the final chat path; excludes unlogged checker calls"},
        "allTurnLatency": latency([t["durationMs"] for t in turns]),
        "database": {"maxActiveAppointments": max(t["activeAppointments"] for t in turns),
                     "stateCheckFailures": sum(bool(t["failures"]) for t in turns)},
        "limits": summary["limits"], "correctiveRagEnabledInRecording": summary["correctiveRagEnabled"],
    }

def build_report(repo):
    root = repo / "evals" / "results"
    agents = [agent_report(root / REPORTS[mode]) for mode in ("sync", "stream")]
    rows = read_lines(root / REPORTS["rerank"] / "trials.jsonl")
    summary = read_json(root / REPORTS["rerank"] / "summary.json")
    if len(rows) != summary["recorded"] or any(r["status"] != "OK" for r in rows):
        raise ValueError("Incomplete reranker recording")
    positives = [r for r in rows if r["gold"]]
    negatives = [r for r in rows if not r["gold"]]
    baseline = statistics.mean(r["baselineRecall"] for r in positives)
    reranked = statistics.mean(r["rerankRecall"] for r in positives)
    if not math.isclose(baseline, summary["baselineRecall"]) or not math.isclose(reranked, summary["rerankRecall"]):
        raise ValueError("Recall mismatch")
    challenge = read_lines(root / REPORTS["challenge"] / "trials.jsonl")
    failures = [{"caseId": r["case"]["id"], "expectedStatus": r["case"]["status"],
                 "actualStatus": r["actual"]["status"], "failureCode": r["actual"].get("failureCode", "")}
                for r in challenge if not r["passed"]]
    sources = []
    for folder in REPORTS.values():
        for path in sorted((root / folder).glob("*")):
            if path.is_file() and path.suffix in (".json", ".jsonl"):
                sources.append({"path": path.relative_to(repo).as_posix(),
                                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    return {
        "schemaVersion": 1, "scope": "historical fixed-sample recordings, recomputed offline",
        "environment": {"python": platform.python_version(), "platform": platform.platform()},
        "agents": agents,
        "retrieval": {"recording": REPORTS["rerank"], "positiveQueries": len(positives),
                      "negativeQueries": len(negatives), "baselineEvidenceRecall": baseline,
                      "rerankedEvidenceRecall": reranked,
                      "baselineFalseAccepts": sum(r["baselineFalseAccept"] for r in negatives),
                      "rerankedFalseAccepts": sum(r["rerankFalseAccept"] for r in negatives),
                      "extraRerankLatency": latency([r["rerankMs"] for r in rows]),
                      "definition": "mean gold-evidence recall over positive queries; not answer accuracy",
                      "improvedQueryIds": [r["id"] for r in positives if r["rerankRecall"] > r["baselineRecall"]]},
        "correctiveChallenge": {"recording": REPORTS["challenge"], "passed": sum(r["passed"] for r in challenge),
                                "total": len(challenge), "failures": failures},
        "sourceFiles": sources,
        "limits": ["Small fixed samples; no estimated general success rate.",
                   "Server-controlled booking is counted separately from cloud-model turns.",
                   "An injected tool failure is included in all-call return rate and identified separately.",
                   "The recordings are from 2026-10-08, not a new production benchmark."],
    }

def markdown(report):
    lines = ["# 小智实测记录汇总", "", "由原始评测记录离线重算，不会调用模型或修改业务数据库。",
             "记录日期为2026-10-08，汇总时间不代表重新执行评测。", "",
             "| 模式 | 场景通过 | 工具有效返回（含故障注入） | 非注入调用有效返回 | 程序预约轮次 | 模型知识轮次 |",
             "|---|---|---|---|---|---|"]
    for a in report["agents"]:
        t = a["toolReturns"]
        lines.append(f'| {a["transport"]} | {a["automatedPassedTrials"]}/{a["recordedTrials"]} | {t["successful"]}/{t["attempted"]} | {t["nonInjectedSuccessful"]}/{t["nonInjectedAttempted"]} | {a["serverBooking"]["turns"]} | {a["modelKnowledge"]["turns"]} |')
    lines += ["", "工具有效返回只核对协议状态，不代表模型选工具准确率。故障注入场景正确停止也能通过业务检查。",
              "", "| 模式与路径 | 样本数 | 平均耗时ms | P50 ms | P95 ms |", "|---|---|---|---|---|"]
    for a in report["agents"]:
        for key, title in (("serverBooking", "程序预约"), ("modelKnowledge", "模型资料")):
            l = a[key]["latency"]
            lines.append(f'| {a["transport"]} / {title} | {l["n"]} | {l["meanMs"]:.1f} | {l["p50Ms"]:.1f} | {l["p95Ms"]:.1f} |')
    r = report["retrieval"]
    lines += ["", "每种模式只有1轮模型资料样本，其分位数不能作为稳定性或负载结论；不是首token延迟。",
              "", f'检索对照：{r["positiveQueries"]}道正例、{r["negativeQueries"]}道无关题；平均证据召回{r["baselineEvidenceRecall"]:.1%}→{r["rerankedEvidenceRecall"]:.1%}，无关误接纳分别为{r["baselineFalseAccepts"]}和{r["rerankedFalseAccepts"]}。',
              f'额外排序调用平均{r["extraRerankLatency"]["meanMs"]:.1f}ms；改善题号：{", ".join(r["improvedQueryIds"])}。',
              "", f'纠错独立挑战通过{report["correctiveChallenge"]["passed"]}/{report["correctiveChallenge"]["total"]}，失败：']
    for f in report["correctiveChallenge"]["failures"]:
        lines.append(f'- {f["caseId"]}：预期{f["expectedStatus"]}，实际{f["actualStatus"]}，错误码{f["failureCode"] or "无"}。')
    lines += ["", "源码与报告路径、原始文件SHA256见同目录metrics.json。完整原始回答和历史失败保留在evals/results。", ""]
    return "\n".join(lines)

def write_report(repo, output):
    report = build_report(repo)
    output.mkdir(parents=True, exist_ok=True)
    (output / "metrics.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "REPORT.md").write_text(markdown(report), encoding="utf-8")
    return report

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    write_report(args.repo.resolve(), args.output.resolve())
    print("PROJECT_METRICS_OK")
