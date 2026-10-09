"""Run real localhost HTTP requests while a subprocess reads project reports."""
import json
import hashlib
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import uuid

import httpx
from xiaozhi_eval.metrics import latency

def main():
    output = Path(sys.argv[1]).resolve()
    output.mkdir(parents=True, exist_ok=True)
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    token = secrets.token_urlsafe(32)
    headers = {"Authorization": "Bearer " + token, "Idempotency-Key": str(uuid.uuid4())}
    with tempfile.TemporaryDirectory(prefix="xiaozhi-eval-smoke-") as state:
        env = {**os.environ, "EVAL_API_TOKEN": token, "EVAL_RUNTIME": state}
        with (output / "service.log").open("wb") as log:
            process = subprocess.Popen([sys.executable, "-m", "uvicorn", "xiaozhi_eval.service:create_app",
                                        "--factory", "--host", "127.0.0.1", "--port", str(port), "--workers", "1"],
                                       env=env, stdout=log, stderr=log, cwd=Path(__file__).parent)
            try:
                with httpx.Client(base_url=f"http://127.0.0.1:{port}", timeout=10) as client:
                    deadline = time.monotonic() + 15
                    while True:
                        try:
                            if client.get("/health").status_code == 200:
                                break
                        except httpx.ConnectError:
                            pass
                        if time.monotonic() > deadline:
                            raise RuntimeError("API did not start")
                        time.sleep(.1)
                    start = time.perf_counter()
                    created = client.post("/jobs", headers=headers, json={"kind": "project_metrics"})
                    created.raise_for_status()
                    submit_ms = (time.perf_counter() - start) * 1000
                    job_id = created.json()["id"]
                    health_ms = []
                    health_while_running = 0
                    for _ in range(30):
                        start = time.perf_counter()
                        client.get("/health").raise_for_status()
                        health_ms.append((time.perf_counter() - start) * 1000)
                        health_while_running += client.get(f"/jobs/{job_id}", headers=headers).json()["status"] == "RUNNING"
                    duplicate = client.post("/jobs", headers=headers, json={"kind": "project_metrics"}).json()
                    assert duplicate["id"] == job_id and duplicate["created"] is False
                    deadline = time.monotonic() + 15
                    while True:
                        job = client.get(f"/jobs/{job_id}", headers=headers).json()
                        if job["status"] in ("SUCCEEDED", "FAILED"):
                            break
                        if time.monotonic() > deadline:
                            raise RuntimeError("Evaluation task did not finish")
                        time.sleep(.05)
                    assert job["status"] == "SUCCEEDED", job
                    report = client.get(f"/jobs/{job_id}/result", headers=headers)
                    report.raise_for_status()
                    assert report.json()["retrieval"]["positiveQueries"] == 20
                    result = {"scope": "real localhost HTTP, one queued offline evaluation; no cloud calls",
                              "submitMs": submit_ms, "healthLatency": latency(health_ms, "sequential localhost health HTTP round trips; not model latency or a load test"),
                              "healthSamplesWhileTaskRunning": health_while_running,
                              "taskDurationMs": job["duration_ms"], "idempotencyVerified": True,
                              "successfulTaskCount": 1, "concurrentLoadClaim": False,
                              "serviceSourceSha256": hashlib.sha256((Path(__file__).parent / "xiaozhi_eval" / "service.py").read_bytes()).hexdigest(),
                              "requirementsSha256": hashlib.sha256((Path(__file__).parent / "requirements.txt").read_bytes()).hexdigest(),
                              "python": sys.version, "recordedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
                    (output / "http-smoke.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
                    print("PYTHON_HTTP_SMOKE_OK")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()

if __name__ == "__main__":
    main()
