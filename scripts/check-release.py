"""Check packaged UI bytes and startup files without contacting business services."""
import argparse
from pathlib import Path
import re
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--jar", required=True, type=Path)
parser.add_argument("--dist", required=True, type=Path)
parser.add_argument("--release-dir", type=Path)
args = parser.parse_args()
assert (args.dist / "index.html").is_file(), "Vue build missing"
prefix = "BOOT-INF/classes/static/"
with zipfile.ZipFile(args.jar) as jar:
    names = set(jar.namelist())
    files = [p for p in args.dist.rglob("*") if p.is_file()]
    assert any(p.suffix == ".js" for p in files), "Vue JavaScript missing"
    for path in files:
        name = prefix + path.relative_to(args.dist).as_posix()
        assert name in names, f"Missing UI file: {name}"
        assert jar.read(name) == path.read_bytes(), f"Stale UI file: {name}"
    expected_assets = {prefix + p.relative_to(args.dist).as_posix()
                       for p in files if p.relative_to(args.dist).parts[0] == "assets"}
    packaged_assets = {n for n in names if n.startswith(prefix + "assets/") and not n.endswith("/")}
    assert packaged_assets == expected_assets, "Stale or missing packaged assets"
    assert prefix + "demo.html" not in names, "Legacy demo must not be packaged"
    for name in ("AdaptiveAgentRouter", "DedicatedReranker", "CorrectiveKnowledgeService",
                 "EvidenceBoundAnswer", "VerifiedBookingModels"):
        assert f"BOOT-INF/classes/com/ruomu/xiaozhi/service/{name}.class" in names, name
if args.release_dir:
    root = args.release_dir
    for name in ("xiaozhi.jar", "start.ps1", "config/application.properties", "COMMIT.txt"):
        assert (root / name).is_file(), f"Missing startup file: {name}"
    assert re.fullmatch(r"[0-9a-f]{40}", (root / "COMMIT.txt").read_text().strip()), "Invalid source commit"
    config = (root / "config/application.properties").read_text(encoding="utf-8")
    assert "spring.sql.init.mode=never" in config
    assert "xiaozhi.appointment.import-from-mongo=false" in config
    assert "${MYSQL_PASSWORD}" in config, "Use an environment variable for the database password"
print(f"RELEASE_CONTENT_OK uiFiles={len(files)} startupChecked={bool(args.release_dir)}")
