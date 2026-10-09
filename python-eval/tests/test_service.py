import asyncio
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import time
import uuid

import pytest
from fastapi.testclient import TestClient

from xiaozhi_eval.service import InstanceLock, Store, create_app

TOKEN = "test-only-token-with-32-characters"
HEADERS = {"Authorization": "Bearer " + TOKEN}

def submit(client, key=None):
    return client.post("/jobs", json={"kind": "project_metrics"},
                       headers={**HEADERS, "Idempotency-Key": key or str(uuid.uuid4())})

def wait(client, job_id, status):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        row = client.get("/jobs/" + job_id, headers=HEADERS).json()
        if row["status"] == status:
            return row
        time.sleep(.02)
    raise AssertionError(row)

async def success(repo, output):
    await asyncio.sleep(.03)
    return {"schemaVersion": 1, "sampleCount": 24}

def app(tmp_path, **kwargs):
    return create_app(runtime=tmp_path, token=TOKEN, runner=kwargs.pop("runner", success), **kwargs)

def test_requires_configured_token(tmp_path):
    with pytest.raises(ValueError):
        create_app(runtime=tmp_path, token="short")

def test_authentication_and_unknown_job(tmp_path):
    with TestClient(app(tmp_path)) as client:
        assert client.get("/health").status_code == 200
        assert client.get("/jobs").status_code == 401
        assert client.get("/jobs", headers={"Authorization": "Bearer wrong"}).status_code == 401
        assert client.get("/jobs/not-a-uuid", headers=HEADERS).status_code == 404
        assert client.get("/jobs/" + str(uuid.uuid4()), headers=HEADERS).status_code == 404

def test_rejects_unbounded_and_arbitrary_job_input(tmp_path):
    with TestClient(app(tmp_path)) as client:
        h = {**HEADERS, "Idempotency-Key": str(uuid.uuid4())}
        assert client.post("/jobs", json={"kind": "shell"}, headers=h).status_code == 422
        assert client.post("/jobs", json={"kind": "project_metrics", "command": "anything"}, headers=h).status_code == 422
        assert client.post("/jobs", json={"kind": "project_metrics"}, headers=HEADERS).status_code == 422
        assert client.post("/jobs", json={"kind": "project_metrics"}, headers={**HEADERS,"Idempotency-Key": "../../bad"}).status_code == 422
        assert client.get("/jobs?limit=10000", headers=HEADERS).status_code == 422

def test_result_and_persistence(tmp_path):
    with TestClient(app(tmp_path)) as client:
        job = submit(client).json()
        completed = wait(client, job["id"], "SUCCEEDED")
        assert completed["duration_ms"] >= 0
        assert completed["finished_at"]
        assert client.get("/jobs/" + job["id"] + "/result", headers=HEADERS).json()["sampleCount"] == 24
        assert "result_json" not in completed
    with TestClient(app(tmp_path)) as client:
        assert client.get("/jobs/" + job["id"], headers=HEADERS).json()["status"] == "SUCCEEDED"

def test_idempotency_under_concurrent_submission(tmp_path):
    calls = []
    async def run(repo, output):
        calls.append(output)
        return {"ok": True}
    with TestClient(app(tmp_path, runner=run)) as client:
        key = str(uuid.uuid4())
        with ThreadPoolExecutor(max_workers=6) as pool:
            jobs = list(pool.map(lambda _: submit(client, key).json(), range(12)))
        assert len({j["id"] for j in jobs}) == 1
        assert sum(j["created"] for j in jobs) == 1
        wait(client, jobs[0]["id"], "SUCCEEDED")
        assert len(calls) == 1

def test_queue_backpressure_and_pending_result(tmp_path):
    async def blocked(repo, output):
        await asyncio.sleep(60)
    with TestClient(app(tmp_path, runner=blocked, capacity=1)) as client:
        job = submit(client).json()
        wait(client, job["id"], "RUNNING")
        assert submit(client).status_code == 429
        assert client.get("/jobs/" + job["id"] + "/result", headers=HEADERS).status_code == 409
        assert client.get("/health").status_code == 200
    assert Store(tmp_path / "tasks.sqlite3").get(job["id"])["error_code"] == "WORKER_INTERRUPTED"

def test_failure_is_terminal_and_private(tmp_path):
    async def failed(repo, output):
        raise RuntimeError("private-key-or-path")
    with TestClient(app(tmp_path, runner=failed)) as client:
        job = submit(client).json()
        row = wait(client, job["id"], "FAILED")
        assert row["error_code"] == "EVALUATION_FAILED"
        assert "private-key-or-path" not in str(row)
        assert client.get("/jobs/" + job["id"] + "/result", headers=HEADERS).status_code == 409

def test_recovery_does_not_replay_interrupted_job(tmp_path):
    store = Store(tmp_path / "tasks.sqlite3")
    first, _ = store.create(str(uuid.uuid4()), "project_metrics")
    store.claim()
    second, _ = store.create(str(uuid.uuid4()), "project_metrics")
    with TestClient(app(tmp_path)) as client:
        assert client.get("/jobs/" + first["id"], headers=HEADERS).json()["error_code"] == "WORKER_INTERRUPTED"
        wait(client, second["id"], "SUCCEEDED")

def test_second_worker_cannot_claim_same_runtime(tmp_path):
    first, second = InstanceLock(tmp_path / "lock"), InstanceLock(tmp_path / "lock")
    first.acquire()
    try:
        with pytest.raises(RuntimeError):
            second.acquire()
    finally:
        first.release()
    second.acquire()
    second.release()

def test_terminal_state_cannot_be_overwritten(tmp_path):
    store = Store(tmp_path / "tasks.sqlite3")
    job, _ = store.create(str(uuid.uuid4()), "project_metrics")
    store.claim()
    store.finish(job["id"], result={"ok": True})
    with pytest.raises(RuntimeError):
        store.finish(job["id"], error="overwrite")

def test_stopped_worker_rejects_new_jobs(tmp_path):
    application = app(tmp_path)
    with TestClient(application) as client:
        client.portal.call(application.state.worker.cancel)
        assert client.get("/health").status_code == 503
        assert submit(client).status_code == 503
        assert client.get("/jobs", headers=HEADERS).json()["items"] == []

def test_shutdown_does_not_overwrite_already_saved_result(tmp_path):
    store = Store(tmp_path / "tasks.sqlite3")
    job, _ = store.create(str(uuid.uuid4()), "project_metrics")
    store.claim()
    store.finish(job["id"], result={"ok": True})
    store.interrupt(job["id"])
    assert store.get(job["id"])["status"] == "SUCCEEDED"
    assert store.get(job["id"])["result_json"] == '{"ok": true}'

def test_runner_timeout_has_distinct_terminal_status(tmp_path):
    async def timeout(repo, output):
        raise asyncio.TimeoutError()
    with TestClient(app(tmp_path, runner=timeout)) as client:
        job = submit(client).json()
        row = wait(client, job["id"], "FAILED")
        assert row["error_code"] == "EVALUATION_TIMEOUT"
