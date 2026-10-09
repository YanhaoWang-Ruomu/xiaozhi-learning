"""Local, single-worker evaluation API with a persistent SQLite task queue."""
import asyncio
from contextlib import asynccontextmanager, contextmanager, suppress
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
import sqlite3
import sys
import time
from typing import Literal
import uuid

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from pydantic import BaseModel, ConfigDict

def now():
    return datetime.now(timezone.utc).isoformat()

class Store:
    def __init__(self, path, capacity=16):
        self.path, self.capacity = Path(path), capacity
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""CREATE TABLE IF NOT EXISTS jobs(
                id TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL, kind TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
                created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT,
                duration_ms REAL, error_code TEXT, result_json TEXT)""")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def create(self, request_id, kind):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            old = db.execute("SELECT * FROM jobs WHERE request_id=?", (request_id,)).fetchone()
            if old:
                if old["kind"] != kind:
                    raise ValueError("Idempotency conflict")
                return dict(old), False
            count = db.execute("SELECT count(*) FROM jobs WHERE status IN ('QUEUED','RUNNING')").fetchone()[0]
            if count >= self.capacity:
                raise OverflowError("Queue full")
            job_id = str(uuid.uuid4())
            db.execute("INSERT INTO jobs(id,request_id,kind,status,created_at) VALUES(?,?,?,'QUEUED',?)",
                       (job_id, request_id, kind, now()))
            return dict(db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()), True

    def get(self, job_id):
        with self.connect() as db:
            row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            return dict(row) if row else None

    def list(self, limit, offset):
        with self.connect() as db:
            return [dict(r) for r in db.execute("SELECT * FROM jobs ORDER BY created_at DESC LIMIT ? OFFSET ?", (limit, offset))]

    def recover(self):
        with self.connect() as db:
            db.execute("UPDATE jobs SET status='FAILED',finished_at=?,error_code='WORKER_INTERRUPTED' WHERE status='RUNNING'", (now(),))

    def claim(self):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM jobs WHERE status='QUEUED' ORDER BY created_at LIMIT 1").fetchone()
            if not row:
                return None
            db.execute("UPDATE jobs SET status='RUNNING',started_at=? WHERE id=? AND status='QUEUED'", (now(), row["id"]))
            return dict(row)

    def finish(self, job_id, result=None, error=None, duration_ms=None):
        encoded = json.dumps(result, ensure_ascii=False) if result is not None else None
        with self.connect() as db:
            updated = db.execute("""UPDATE jobs SET status=?,finished_at=?,duration_ms=?,error_code=?,result_json=?
                WHERE id=? AND status='RUNNING'""",
                ("FAILED" if error else "SUCCEEDED", now(), duration_ms, error, encoded, job_id))
            if updated.rowcount != 1:
                raise RuntimeError("Invalid terminal transition")

    def interrupt(self, job_id, duration_ms=None):
        # Shutdown can race a completed database write; preserve an existing terminal result.
        with self.connect() as db:
            db.execute("""UPDATE jobs SET status='FAILED',finished_at=?,duration_ms=?,error_code='WORKER_INTERRUPTED'
                WHERE id=? AND status='RUNNING'""", (now(), duration_ms, job_id))

class InstanceLock:
    """OS lock is released on process exit, including crashes."""
    def __init__(self, path):
        self.path, self.file = path, None

    def acquire(self):
        self.file = open(self.path, "a+b")
        if self.file.tell() == 0:
            self.file.write(b"\0")
            self.file.flush()
        self.file.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.file.close()
            self.file = None
            raise RuntimeError("Evaluation runtime already has a worker; use --workers 1")

    def release(self):
        if self.file:
            self.file.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_UN)
            self.file.close()
            self.file = None

async def execute_metrics(repo, output):
    output.mkdir(parents=True, exist_ok=True)
    process = None
    with (output / "worker.log").open("wb") as log:
        try:
            process = await asyncio.create_subprocess_exec(
                sys.executable, "-m", "xiaozhi_eval.metrics", "--repo", str(repo), "--output", str(output),
                cwd=str(Path(__file__).resolve().parents[1]), stdout=log, stderr=log,
                env={**os.environ, "PYTHONIOENCODING": "utf-8"})
            await asyncio.wait_for(process.wait(), timeout=120)
            if process.returncode != 0:
                raise RuntimeError("EVALUATION_PROCESS_FAILED")
            report = json.loads((output / "metrics.json").read_text(encoding="utf-8"))
            if report.get("schemaVersion") != 1:
                raise RuntimeError("EVALUATION_RESULT_INVALID")
            return report
        finally:
            if process and process.returncode is None:
                process.terminate()
                try:
                    await asyncio.wait_for(process.wait(), timeout=5)
                except asyncio.TimeoutError:
                    process.kill()
                    await process.wait()

class Submission(BaseModel):
    model_config = ConfigDict(extra="forbid")
    kind: Literal["project_metrics"]

def visible(row):
    return {k: v for k, v in row.items() if k not in ("request_id", "result_json")}

def create_app(*, repo=None, runtime=None, token=None, runner=execute_metrics, capacity=16):
    repo = Path(repo or os.environ.get("EVAL_REPO_ROOT") or Path(__file__).resolve().parents[2]).resolve()
    runtime = Path(runtime or os.environ.get("EVAL_RUNTIME") or repo.parent / "xiaozhi-eval-runtime").resolve()
    token = token if token is not None else os.environ.get("EVAL_API_TOKEN", "")
    if len(token) < 24:
        raise ValueError("Set EVAL_API_TOKEN to at least 24 characters")
    runtime.mkdir(parents=True, exist_ok=True)
    store = Store(runtime / "tasks.sqlite3", capacity)
    lock = InstanceLock(runtime / "worker.lock")

    async def worker():
        while True:
            job = await asyncio.to_thread(store.claim)
            if not job:
                await asyncio.sleep(.05)
                continue
            started = time.perf_counter()
            try:
                result = await runner(repo, runtime / "jobs" / job["id"])
                await asyncio.to_thread(store.finish, job["id"], result=result,
                                        duration_ms=(time.perf_counter() - started) * 1000)
            except asyncio.CancelledError:
                await asyncio.to_thread(store.interrupt, job["id"],
                                        duration_ms=(time.perf_counter() - started) * 1000)
                raise
            except Exception as exc:
                # Details remain in local worker.log, never return paths/secrets to clients.
                code = "EVALUATION_TIMEOUT" if isinstance(exc, asyncio.TimeoutError) else "EVALUATION_FAILED"
                await asyncio.to_thread(store.finish, job["id"], error=code,
                                        duration_ms=(time.perf_counter() - started) * 1000)

    @asynccontextmanager
    async def lifespan(app):
        lock.acquire()
        try:
            await asyncio.to_thread(store.recover)
            task = asyncio.create_task(worker())
            app.state.worker = task
            try:
                yield
            finally:
                task.cancel()
                with suppress(asyncio.CancelledError):
                    await task
        finally:
            lock.release()

    app = FastAPI(title="Xiaozhi recorded-evaluation API", version="0.1.0", lifespan=lifespan)

    def authorize(authorization: str | None = Header(default=None)):
        expected = "Bearer " + token
        if not authorization or not secrets.compare_digest(authorization.encode('utf-8'), expected.encode('utf-8')):
            raise HTTPException(401, "Invalid evaluation token")

    @app.get("/health")
    async def health():
        if app.state.worker.done():
            raise HTTPException(503, "Evaluation worker unavailable")
        return {"status": "ok", "scope": "local recorded evaluation", "callsCloudModels": False}

    @app.post("/jobs", status_code=202, dependencies=[Depends(authorize)])
    async def submit(body: Submission, idempotency_key: str = Header(alias="Idempotency-Key")):
        if app.state.worker.done():
            raise HTTPException(503, "Evaluation worker unavailable")
        try:
            request_id = str(uuid.UUID(idempotency_key))
        except ValueError:
            raise HTTPException(422, "Idempotency-Key must be a UUID")
        try:
            row, created = await asyncio.to_thread(store.create, request_id, body.kind)
        except OverflowError:
            raise HTTPException(429, "Evaluation queue full")
        except ValueError:
            raise HTTPException(409, "Idempotency conflict")
        return {**visible(row), "created": created}

    @app.get("/jobs", dependencies=[Depends(authorize)])
    async def list_jobs(limit: int = Query(20, ge=1, le=50), offset: int = Query(0, ge=0)):
        return {"items": [visible(r) for r in await asyncio.to_thread(store.list, limit, offset)]}

    async def find_job(job_id):
        try:
            canonical = str(uuid.UUID(job_id))
        except ValueError:
            raise HTTPException(404, "Job not found")
        row = await asyncio.to_thread(store.get, canonical)
        if not row:
            raise HTTPException(404, "Job not found")
        return row

    @app.get("/jobs/{job_id}", dependencies=[Depends(authorize)])
    async def job_status(job_id: str):
        return visible(await find_job(job_id))

    @app.get("/jobs/{job_id}/result", dependencies=[Depends(authorize)])
    async def job_result(job_id: str):
        row = await find_job(job_id)
        if row["status"] != "SUCCEEDED":
            raise HTTPException(409, {"status": row["status"], "errorCode": row["error_code"]})
        return json.loads(row["result_json"])

    return app
