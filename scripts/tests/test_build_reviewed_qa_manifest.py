import hashlib
import hmac
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("reviewed_manifest", Path(__file__).parents[1] / "build_reviewed_qa_manifest.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReviewedManifestTests(unittest.TestCase):
    def test_outputs_only_hmac_and_approved_metadata(self):
        key = b"k" * 32
        doc = "00000000-0000-0000-0000-000000000001"
        version = "00000000-0000-0000-0000-000000000002"
        reviewed = {"questions": [{"question": "备份多久？", "approvalId": version, "allDocumentsReviewed": True,
            "documents": [{"documentId": doc, "contextFingerprint": "a" * 64, "relation": "RELEVANT"}],
            "claims": [{"documentId": doc, "versionId": version, "nodeId": "n", "text": "30天", "quote": "保留30天"}]}]}
        result = MODULE.build_manifest(reviewed, key)
        item = result["questions"][0]
        expected = hmac.new(key, "question:备份多久?".encode(), hashlib.sha256).hexdigest()
        self.assertEqual(item["questionDigest"], expected)
        self.assertEqual(item["claims"][0]["claimDigest"], hmac.new(key, "claim:30天".encode(), hashlib.sha256).hexdigest())
        self.assertEqual(item["claims"][0]["quoteDigest"], hmac.new(key, "quote:保留30天".encode(), hashlib.sha256).hexdigest())
        self.assertNotIn("30天", str(result))
        self.assertNotIn("备份多久", str(result))
        sealed = MODULE.seal_manifest(result, key)
        self.assertEqual(sealed["signature"], hmac.new(key, ("manifest:" + sealed["payload"]).encode(), hashlib.sha256).hexdigest())
        self.assertNotIn("30天", str(sealed))

    def test_rejects_unapproved_records_and_ambiguous_subjects(self):
        with self.assertRaises(ValueError):
            MODULE.build_manifest({"questions": [{"question": "星河²项目？", "allDocumentsReviewed": False}]}, b"k" * 32)
        with self.assertRaises(ValueError):
            MODULE._question("星河²项目的备份保留多久？")

    def test_cli_uses_the_runtime_key_and_refuses_overwrite(self):
        doc = "00000000-0000-0000-0000-000000000001"
        version = "00000000-0000-0000-0000-000000000002"
        review = {"questions": [{"question": "备份多久？", "approvalId": version, "allDocumentsReviewed": True,
            "documents": [{"documentId": doc, "contextFingerprint": "a" * 64, "relation": "RELEVANT"}],
            "claims": [{"documentId": doc, "versionId": version, "nodeId": "n", "text": "30天", "quote": "保留30天"}]}]}
        with tempfile.TemporaryDirectory() as temp:
            source, target = Path(temp) / "review.json", Path(temp) / "manifest.json"
            source.write_text(json.dumps(review, ensure_ascii=False), encoding="utf-8")
            environment = dict(os.environ, RAG_QA_QUESTION_HMAC_KEY="k" * 32)
            command = [sys.executable, str(SPEC.origin), "--input", str(source), "--output", str(target)]
            first = subprocess.run(command, env=environment, capture_output=True)
            self.assertEqual(first.returncode, 0)
            content = target.read_text(encoding="utf-8")
            self.assertNotIn("30天", content)
            self.assertNotIn("k" * 32, content)
            self.assertNotEqual(subprocess.run(command, env=environment, capture_output=True).returncode, 0)


if __name__ == "__main__":
    unittest.main()
