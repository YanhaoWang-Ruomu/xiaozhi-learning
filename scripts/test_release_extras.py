import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("release_extras", Path(__file__).with_name("stage-release-extras.py"))
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class ReleaseExtrasTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name) / "source"; self.source.mkdir()
        self.target = Path(self.temp.name) / "release"
        subprocess.run(["git", "init", "-q", str(self.source)], check=True, capture_output=True)
        for name in (*module.REQUIRED, "mcp/server.mjs", "mcp/package.json"):
            path = self.source / name; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("tracked "+name, encoding="utf-8")
        subprocess.run(["git", "-C", str(self.source), "add", "."], check=True, capture_output=True)
        subprocess.run(["git", "-C", str(self.source), "-c", "user.name=Package test",
                        "-c", "user.email=package-test@example.invalid", "commit", "-qm", "fixture"],
                       check=True, capture_output=True)

    def test_all_required_bytes_are_preserved_and_untracked_data_excluded(self):
        (self.source / ".env").write_text("not-a-real-key", encoding="utf-8")
        (self.source / "ml-intent" / ".venv").mkdir()
        (self.source / "ml-intent" / ".venv" / "marker.txt").write_text("not shipped")
        count = module.stage(self.source, self.target)
        self.assertEqual(count, len(module.REQUIRED) + 2)
        self.assertFalse((self.target / ".env").exists())
        self.assertFalse((self.target / "ml-intent" / ".venv").exists())
        self.assertEqual(module.verify(self.source, self.target), count)

    def test_missing_module_file_is_rejected(self):
        module.stage(self.source, self.target)
        (self.target / "ml-intent" / "experiment.py").unlink()
        with self.assertRaisesRegex(ValueError, "Missing/invalid"): module.verify(self.source, self.target)

    def test_stale_report_is_rejected(self):
        module.stage(self.source, self.target)
        (self.target / "evals/ml-intent/20261009/report.html").write_text("old")
        with self.assertRaisesRegex(ValueError, "Stale/modified"): module.verify(self.source, self.target)

    def test_source_manifest_cannot_silently_omit_new_module(self):
        module.stage(self.source, self.target)
        manifest = json.loads((self.target / "EXTRAS.json").read_text(encoding="utf-8"))
        del manifest["files"]["ml-intent/requirements.txt"]
        (self.target / "EXTRAS.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "list differs"): module.verify(self.source, self.target)

    def test_runtime_model_cannot_leak_into_zip(self):
        module.stage(self.source, self.target)
        (self.target / "model.joblib").write_bytes(b"not a model")
        with self.assertRaisesRegex(ValueError, "Runtime/cache"): module.verify(self.source, self.target)

if __name__ == "__main__": unittest.main()
