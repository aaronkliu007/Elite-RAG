import importlib.util
import contextlib
import io
import os
import sys
import unittest
import urllib.error
from unittest.mock import patch
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("conflict_scope_probe", Path(__file__).parents[1] / "conflict_scope_probe.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ConflictScopeProbeTests(unittest.TestCase):
    def test_only_exact_unambiguous_result_is_accepted(self):
        self.assertEqual(MODULE.parse_scope('{"scope":"RELATED"}'), "RELATED")
        self.assertEqual(MODULE.parse_scope('{"scope":"UNRELATED"}'), "UNRELATED")
        for value in ('{"scope":"UNRELATED","scope":"RELATED"}', '{"scope":"UNRELATED","extra":1}',
                      '{"scope":"maybe"}', 'unrelated', '', '{"scope":null}'):
            self.assertEqual(MODULE.parse_scope(value), "UNKNOWN")

    def test_probe_uses_only_bounded_built_in_synthetic_material(self):
        cases = MODULE.synthetic_cases()
        self.assertGreaterEqual(len(cases), 6)
        self.assertLessEqual(len(cases), 20)
        self.assertEqual(len({case["id"] for case in cases}), len(cases))
        self.assertEqual({case["expected"] for case in cases}, {"RELATED", "UNRELATED", "UNKNOWN"})
        self.assertTrue(all(len(case["question"]) < 200 and len(case["documents"]) == 2 for case in cases))

    def test_provider_truncation_refusal_and_multiple_choices_are_never_unrelated(self):
        response = {"choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": '{"scope":"UNRELATED"}'}}],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 2}}
        self.assertEqual(MODULE.parse_provider_response(response)[0], "UNRELATED")
        response["choices"][0]["finish_reason"] = "length"
        self.assertEqual(MODULE.parse_provider_response(response)[0], "UNKNOWN")
        response["choices"][0]["finish_reason"] = "stop"
        response["choices"][0]["message"]["refusal"] = "无法判断"
        self.assertEqual(MODULE.parse_provider_response(response)[0], "UNKNOWN")
        response["choices"][0]["message"]["refusal"] = None
        response["choices"][0]["message"]["tool_calls"] = [{"id": "x"}]
        self.assertEqual(MODULE.parse_provider_response(response)[0], "UNKNOWN")
        response["choices"][0]["message"]["tool_calls"] = []
        response["choices"].append(response["choices"][0])
        self.assertEqual(MODULE.parse_provider_response(response)[0], "UNKNOWN")

    def test_unsafe_prediction_causes_nonzero_exit(self):
        cases = [{"id": "X", "question": "模拟问题", "documents": ["甲", "乙"], "expected": "RELATED"}]
        with patch.object(sys, "argv", ["probe"]), patch.dict(os.environ, {"RAG_SYNTHETIC_SCOPE_PROBE": "true", "RAG_AI_API_KEY": "test"}), \
             patch.object(MODULE, "synthetic_cases", return_value=cases), \
             patch.object(MODULE, "_request", return_value=("UNRELATED", 1, 1, True)), contextlib.redirect_stdout(io.StringIO()):
            self.assertNotEqual(MODULE.main(), 0)

    def test_transient_network_failure_is_retried_once(self):
        stats = {"attempts": 0, "httpErrors": 0, "networkErrors": 0, "timeouts": 0}
        with patch.object(MODULE, "_request", side_effect=[urllib.error.URLError("temporary"), ("UNKNOWN", 1, 1, True)]) as request:
            self.assertEqual(MODULE._request_with_retry({}, "test", stats)[0], "UNKNOWN")
            self.assertEqual(request.call_count, 2)
            self.assertEqual(stats["attempts"], 2)
            self.assertEqual(stats["networkErrors"], 1)
        with patch.object(MODULE, "_request", side_effect=[ConnectionResetError("temporary"), ("UNKNOWN", 1, 1, True)]) as request:
            self.assertEqual(MODULE._request_with_retry({}, "test")[0], "UNKNOWN")
            self.assertEqual(request.call_count, 2)


if __name__ == "__main__":
    unittest.main()
