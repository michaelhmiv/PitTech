"""Tests for the release-note tools."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

source = Path(__file__).resolve().parents[1] / "release_notes.py"
spec = importlib.util.spec_from_file_location("release_notes", source)
notes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notes)


class ReleaseNotesTests(unittest.TestCase):
    def test_valid_notes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "whatsnew-en-US"
            path.write_text("Improved cook charts.", encoding="utf-8")
            notes.validate_text(path, 500)

    def test_long_notes_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "whatsnew-en-US"
            path.write_text("x" * 501, encoding="utf-8")
            with self.assertRaises(ValueError):
                notes.validate_text(path, 500)

    def test_placeholder_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "whatsnew-en-US"
            path.write_text("TODO: update before release", encoding="utf-8")
            with self.assertRaises(ValueError):
                notes.validate_text(path, 500)

    def test_dev_notes_link_actual_commit(self):
        with patch.object(notes, "commit_title", return_value="Improve food cards"):
            content = notes.render_dev("a" * 40, "0.1.42-dev", "michaelhmiv/PitTech")
        self.assertIn("Improve food cards", content)
        self.assertIn("/commit/" + "a" * 40, content)

    def test_invalid_repository_rejected(self):
        with self.assertRaises(ValueError):
            notes.render_dev("a" * 40, "0.1.42-dev", "owner/repo\ninjected")


if __name__ == "__main__":
    unittest.main()
