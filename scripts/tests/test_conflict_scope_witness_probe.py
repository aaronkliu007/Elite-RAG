import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).parents[1]
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("conflict_scope_witness_probe", SCRIPTS / "conflict_scope_witness_probe.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class WitnessProbeTests(unittest.TestCase):
    def test_requires_a_complete_literal_sentence_and_both_subjects(self):
        docs = [{"title": "模拟声明", "nodes": [{"text": "研发预算与备份完全无关。"}]}]
        self.assertTrue(MODULE.has_literal_witness("备份保留多久？", "研发预算", "备份", docs, "模拟声明", "研发预算与备份完全无关。"))
        self.assertFalse(MODULE.has_literal_witness("演练多久一次？", "研发预算", "备份", docs, "模拟声明", "研发预算与备份完全无关。"))
        self.assertFalse(MODULE.has_literal_witness("备份保留多久？", "研发预算", "备份", docs, "模拟声明", "预算与备份完全无关。"))
        docs[0]["nodes"][0]["text"] = "研发预算与备份完全无关，但预算不足时保留10天。"
        self.assertFalse(MODULE.has_literal_witness("备份保留多久？", "研发预算", "备份", docs, "模拟声明", "研发预算与备份完全无关"))

    def test_multiple_documents_with_the_same_title_are_ambiguous(self):
        docs = [{"title": "模拟声明", "nodes": [{"text": "研发预算与备份完全无关。"}]}] * 2
        self.assertFalse(MODULE.has_literal_witness("备份保留多久？", "研发预算", "备份", docs, "模拟声明", "研发预算与备份完全无关。"))

    def test_witness_is_not_a_complete_conflict_decision(self):
        docs = [{"title": "模拟声明", "nodes": [{"text": "研发预算与备份完全无关。"}]},
                {"title": "相反说明", "nodes": [{"text": "研发预算不足时备份保留期缩短。"}]}]
        self.assertTrue(MODULE.has_literal_witness("备份保留多久？", "研发预算", "备份", docs, "模拟声明", "研发预算与备份完全无关。"))
        # A literal witness may coexist with contradictory evidence; it must not be used alone to authorize an answer.


if __name__ == "__main__":
    unittest.main()
