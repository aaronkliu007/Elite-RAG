import importlib.util
import contextlib
import hashlib
import io
import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).parents[1]
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("conflict_scope_corpus_probe", SCRIPTS / "conflict_scope_corpus_probe.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CorpusProbeTests(unittest.TestCase):
    def test_rejects_changed_added_and_missing_documents_before_external_call(self):
        reviewed = {"文档": {"模拟甲": "a", "模拟乙": "b"}}
        corpus = {"documents": [{"title": "模拟甲", "fingerprint": "a", "nodes": []},
                                {"title": "模拟乙", "fingerprint": "b", "nodes": []}], "blobs": ["1", "2"]}
        encoded=json.dumps(corpus["documents"],sort_keys=True,separators=(",", ":"),ensure_ascii=False).encode("utf-8")
        with patch.object(MODULE, "EXPECTED_PAYLOAD_HASH", hashlib.sha256(encoded).hexdigest(), create=True):
            self.assertTrue(MODULE.corpus_matches(corpus, reviewed, ["1", "2"], expected_count=2))
            corpus["documents"][0]["nodes"] = [{"text": "未获批准的内容"}]
            self.assertFalse(MODULE.corpus_matches(corpus, reviewed, ["1", "2"], expected_count=2))
            corpus["documents"][0]["nodes"] = []
            corpus["documents"][0]["fingerprint"] = "changed"
            self.assertFalse(MODULE.corpus_matches(corpus, reviewed, ["1", "2"], expected_count=2))
            corpus["documents"][0]["fingerprint"] = "a"
            corpus["documents"].append({"title": "其他", "fingerprint": "c", "nodes": []})
            self.assertFalse(MODULE.corpus_matches(corpus, reviewed, ["1", "2"], expected_count=2))
            corpus["documents"].pop()
            self.assertFalse(MODULE.corpus_matches(corpus, reviewed, ["1", "3"], expected_count=2))

    def test_does_not_send_anything_if_corpus_verification_fails(self):
        reviewed = {"文档": {f"模拟{i}": "expected" for i in range(18)}}
        corpus = {"documents": [{"title": f"模拟{i}", "fingerprint": "changed" if i == 0 else "expected", "nodes": []}
                                for i in range(18)], "blobs": ["1"]}
        with patch.object(sys, "argv", ["probe"]), patch.dict(os.environ, {"RAG_SYNTHETIC_CORPUS_PROBE": "true", "RAG_AI_API_KEY": "test"}), \
             patch.object(MODULE, "_fixture", side_effect=[reviewed, ["1"]]), patch.object(MODULE, "_corpus", return_value=corpus), \
             patch.object(MODULE.probe, "_request_with_retry") as request, contextlib.redirect_stderr(io.StringIO()):
            self.assertNotEqual(MODULE.main(), 0)
            request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
