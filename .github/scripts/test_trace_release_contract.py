#!/usr/bin/env python3
"""CI contract for the three-secret, explicit-release Link Plus Trace signer."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
RELEASE = ROOT / ".github/workflows/trace-fixed-release.yml"
GRADLE = ROOT / "app/build.gradle.kts"
SMOKE = ROOT / ".github/workflows/apk-smoke.yml"

class TraceReleaseContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = RELEASE.read_text(encoding="utf-8")
        cls.gradle = GRADLE.read_text(encoding="utf-8")
        cls.smoke = SMOKE.read_text(encoding="utf-8")

    def test_only_three_secrets(self):
        names = set(re.findall(r"secrets\.([A-Z0-9_]+)", self.workflow))
        self.assertEqual(names, {
            "TRACE_SIGNING_KEYSTORE_B64",
            "TRACE_SIGNING_STORE_PASSWORD",
            "TRACE_SIGNING_KEY_ALIAS",
        })
        self.assertNotIn("TRACE_SIGNING_KEY_PASSWORD", self.gradle)
        self.assertNotIn("TRACE_SIGNING_PUBLISH_APPROVED", self.workflow)
        self.assertIn("keyPassword = traceStorePassword", self.gradle)

    def test_auto_signed_release_only_for_experiment_runtime(self):
        self.assertIn(
            "on:\n  push:\n    branches:\n      - experiment/link-plus-trace-fixed-update-20261107",
            self.workflow,
        )
        self.assertIn("      - 'app/src/main/**'", self.workflow)
        self.assertIn("      - 'app/build.gradle.kts'", self.workflow)
        self.assertIn("      - '.github/workflows/trace-fixed-release.yml'", self.workflow)
        self.assertNotIn("trace-publish-request-v", self.workflow)
        self.assertNotIn("    tags:\n", self.workflow)
        self.assertIn('id: release_gate', self.workflow)
        self.assertIn('if: steps.release_gate.outputs.publish == \'true\'', self.workflow)
        self.assertIn('"$GITHUB_SHA" == "$branch_sha"', self.workflow)
        self.assertIn("if (( version_code <= max_released )); then", self.workflow)
        self.assertIn('publish=false', self.workflow)
        self.assertIn('publish=true', self.workflow)
        self.assertIn("Branch advanced during build", self.workflow)

    def test_release_metadata_and_identity_are_fenced(self):
        for value in (
            "3fc11b60da53fd634d668bde7f7e9e19f6ed042d59502de2797b949e53e482e3",
            "io.github.shaonialife.buicklinkplus.buick.trace",
            "exp-link-plus-trace-v${EXPECTED_VERSION_CODE}-${shortsha}",
            "updaterSchema=2",
            "artifactKind=experiment",
            "apksigner",
            "sha256sum",
        ):
            self.assertIn(value, self.workflow)
        self.assertIn("TRACE_FIXED_SIGNING_MISSING: refusing unsigned/debug fallback", self.gradle)
        self.assertIn('if (traceFixedSigning) "traceFixed" else "debug"', self.gradle)

    def test_smoke_never_uses_signing_secrets(self):
        self.assertIn("python3 .github/scripts/test_trace_release_contract.py", self.smoke)
        self.assertNotIn("TRACE_SIGNING_STORE_PASSWORD", self.smoke)
        self.assertNotIn("contents: write", self.smoke)

if __name__ == "__main__":
    unittest.main(verbosity=2)
