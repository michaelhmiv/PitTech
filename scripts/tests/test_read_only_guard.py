"""Regression tests for the intended observation-only controller experience."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
DIAGNOSTICS = ROOT / "app/src/internal/java/com/pittech/ui/ControllerDiagnosticsScreen.kt"
PROVISIONER = ROOT / "app/src/internal/java/com/pittech/devices/GrillirGWifiProvisioner.kt"


class ObservationOnlyTests(unittest.TestCase):
    def test_no_wifi_setup_action_in_controller_screen(self):
        ui = DIAGNOSTICS.read_text(encoding="utf-8")
        self.assertNotIn("GrillirGWifiSetupPanel(", ui)
        self.assertNotIn("grillirg-open-wifi-setup", ui)

    def test_provision_operation_fails_closed(self):
        source = PROVISIONER.read_text(encoding="utf-8")
        start = source.index("    override fun provision(")
        end = source.index("    override fun close()", start)
        body = source[start:end]
        self.assertIn("observation-only monitoring", body)
        self.assertIn('fail(', body)
        self.assertNotIn("createWifiCredentialFrame", body)
        self.assertNotIn("writeFrame(", body)


if __name__ == "__main__":
    unittest.main()
