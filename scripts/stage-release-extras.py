"""Stage only tracked optional modules/docs; verify their exact bytes."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess

REPORTS = (
    "agent-multiturn-sync-20261008-184748",
    "agent-multiturn-stream-20261008-185308",
    "dedicated-rerank-20261008-183756",
    "evidence-challenge-20261008-185227",
)
REQUIRED = (
    "README.md", "docs/ML_INTENT_ROUTING.md", "docs/RELEASE.md",
    "ml-intent/README.md", "ml-intent/experiment.py", "ml-intent/test_experiment.py",
    "ml-intent/requirements.txt", "ml-intent/data/intent-v1.jsonl",
    "evals/ml-intent/20261009/REPORT.md", "evals/ml-intent/20261009/metrics.json",
    "evals/ml-intent/20261009/test-predictions.csv", "evals/ml-intent/20261009/report.html",
    "python-eval/README.md", "python-eval/requirements.txt",
    "python-eval/xiaozhi_eval/service.py", "python-eval/xiaozhi_eval/metrics.py",
    "scripts/stage-release-extras.py", "scripts/check-release.py",
)
SUFFIXES = {".py", ".md", ".txt", ".json", ".jsonl", ".csv", ".html", ".ini",
            ".mjs", ".cjs", ".js", ".ps1", ".sql", ".example", ".sh"}
def selected(name):
    p = PurePosixPath(name)
    if p.is_absolute() or ".." in p.parts:
        return False
    if any(part in {".venv", "runs", "__pycache__", ".pytest_cache", "node_modules", "test-results"} for part in p.parts):
        return False
    allowed = name == "README.md" or any(name.startswith(prefix) for prefix in (
        "docs/", "scripts/", "deploy/", "sql/", "mcp/", "python-eval/", "ml-intent/",
        "evals/portfolio/", "evals/ml-intent/"))
    allowed = allowed or any(name.startswith("evals/results/"+r+"/") for r in REPORTS)
    return allowed and p.suffix in SUFFIXES

def selected_files(source):
    raw = subprocess.check_output(["git", "-C", str(source), "ls-files", "--cached", "-z"])
    files = sorted(n for n in raw.decode("utf-8").split("\0") if n and selected(n))
    missing = set(REQUIRED) - set(files)
    if missing:
        raise ValueError("Required tracked extras missing: "+", ".join(sorted(missing)))
    if not any(n.startswith("mcp/") and n.endswith(".mjs") for n in files):
        raise ValueError("MCP runtime source missing")
    return files

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def stage(source, target):
    source, target = source.resolve(), target.resolve()
    if source == target:
        raise ValueError("release cannot overwrite source")
    files = selected_files(source)
    target.mkdir(parents=True, exist_ok=True)
    manifest = {"source_commit": subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"]).decode().strip(),
                "scope": "tracked optional modules and instructions; no runtime data", "files": {}}
    for name in files:
        src, dst = source / name, target / name
        if src.is_symlink() or not src.is_file():
            raise ValueError("Invalid tracked source: "+name)
        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.is_symlink():
            raise ValueError("Refuse release symlink: "+name)
        shutil.copyfile(src, dst)
        manifest["files"][name] = digest(src)
    (target / "EXTRAS.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    verify(source, target)
    return len(files)

def verify(source, target):
    manifest = json.loads((target / "EXTRAS.json").read_text(encoding="utf-8"))
    expected = set(selected_files(source))
    if set(manifest["files"]) != expected:
        raise ValueError("Release extras list differs from source")
    for name in expected:
        path = target / name
        if not path.is_file() or path.is_symlink():
            raise ValueError("Missing/invalid release extra: "+name)
        if manifest["files"][name] != digest(source / name) or digest(path) != manifest["files"][name]:
            raise ValueError("Stale/modified release extra: "+name)
    forbidden = [p for p in target.rglob("*") if p.is_file() and (p.suffix in {".joblib", ".sqlite3", ".pyc"} or
                any(x in {".venv", "node_modules", "__pycache__", ".pytest_cache"} for x in p.relative_to(target).parts))]
    if forbidden:
        raise ValueError("Runtime/cache files in release: "+str(forbidden[0]))
    return len(expected)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--release", required=True, type=Path)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    count = verify(args.source, args.release) if args.check_only else stage(args.source, args.release)
    print("RELEASE_EXTRAS_OK files="+str(count))
