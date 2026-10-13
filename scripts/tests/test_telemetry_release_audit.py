import sys
import tempfile
from pathlib import Path
from unittest import TestCase

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from telemetry_release_audit import APPROVED, audit

class PublicTelemetryAuditTests(TestCase):
    def fixture(self, location):
        root = Path(location)
        src = root / "app/src/main/java/com/pittech/devices"
        src.mkdir(parents=True, exist_ok=True)
        (src / "TelemetryContract.kt").write_text(" ".join('"' + x + '"' for x in APPROVED))
        docs = root / "docs"
        docs.mkdir(exist_ok=True)
        (docs / "APP_REVIEW_CONNECTION_EVIDENCE.md").write_text(" ".join(
            x.replace("_", " ") for x in APPROVED))
        manifest = root / "app/src/main/AndroidManifest.xml"
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text('<manifest><application /></manifest>')
        return root

    def test_approved_registry_and_isolated_sources_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual([], audit(self.fixture(folder)))

    def test_proprietary_controller_in_public_sources_blocks_release(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.fixture(folder)
            path = root / "app/src/main/java/com/pittech/devices/PrimePolarisApi.kt"
            path.write_text("class PrimePolarisApi")
            self.assertTrue(any("PrimePolarisApi.kt" in e for e in audit(root)))

    def test_developer_http_allowed_but_production_http_is_blocked(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.fixture(folder)
            dev = root / "app/src/dev/AndroidManifest.xml"
            dev.parent.mkdir(parents=True)
            dev.write_text('<application usesCleartextTraffic="true" />')
            self.assertEqual([], audit(root))
            public = root / "app/src/main/AndroidManifest.xml"
            public.write_text('<application android:usesCleartextTraffic="true" />')
            self.assertTrue(any("Cleartext network traffic" in e for e in audit(root)))

    def test_unknown_direct_grillirG_registration_blocks_release(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.fixture(folder)
            src = root / "app/src/main/java/com/pittech/devices/TelemetryContract.kt"
            src.write_text(src.read_text() + ' "grillirg"')
            self.assertTrue(any("unapproved" in e.lower() for e in audit(root)))
