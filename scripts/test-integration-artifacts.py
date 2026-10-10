#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("integration", Path(__file__).with_name("verify-integration-artifacts.py"))
integration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(integration)


class IntegrationProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repository = Path(self.directory.name)
        self.revision = "a" * 40
        self.paths = []
        version = "0.0.0-internal.g" + self.revision
        for name in integration.MODULES:
            path = self.repository / "com/jlshell/link" / name / version / f"{name}-{version}.jar"
            path.parent.mkdir(parents=True)
            self.paths.append(path)
            self.jar(path, self.revision)

    def jar(self, path, revision, duplicate=False, folded=False):
        value = revision[:20] + "\r\n " + revision[20:] if folded else revision
        manifest = "Manifest-Version: 1.0\r\nJLShell-Build-Revision: " + value + "\r\n"
        if duplicate:
            manifest += "JLShell-Build-Revision: " + revision + "\r\n"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", manifest + "\r\n")

    def test_all_downloaded_modules_match_revision(self):
        report = integration.verify(self.repository, self.revision)
        self.assertEqual(len(report["libraries"]), 3)
        self.assertTrue(all(len(entry["sha256"]) == 64 for entry in report["libraries"]))

    def test_folded_manifest_header(self):
        self.jar(self.paths[0], self.revision, folded=True)
        integration.verify(self.repository, self.revision)

    def test_rejects_mixed_source(self):
        self.jar(self.paths[0], "b" * 40)
        with self.assertRaises(ValueError):
            integration.verify(self.repository, self.revision)

    def test_rejects_duplicate_source_header(self):
        self.jar(self.paths[0], self.revision, duplicate=True)
        with self.assertRaises(ValueError):
            integration.verify(self.repository, self.revision)

    def test_rejects_missing_library(self):
        self.paths[0].unlink()
        with self.assertRaises(ValueError):
            integration.verify(self.repository, self.revision)

    def test_rejects_short_or_untrusted_revision(self):
        for value in ("latest", "a" * 7, "../other", "a" * 40 + "\n"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                integration.verify(self.repository, value)

    def test_rejects_library_symlink(self):
        copy = self.repository / "copy.jar"
        self.paths[0].rename(copy)
        self.paths[0].symlink_to(copy)
        with self.assertRaises(ValueError):
            integration.verify(self.repository, self.revision)


if __name__ == "__main__":
    unittest.main()
