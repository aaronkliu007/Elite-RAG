import unittest
from scripts.compare_answer_spans import compare


class AnswerSpanComparisonTests(unittest.TestCase):
    def test_matches_one_expected_source_without_joining_separate_excerpts(self):
        refs = [{"编号": "Q01", "期望行为": "可回答", "参考答案要点": "备份保留30天。", "参考来源": "制度.docx"}]
        before = [{"编号": "Q01", "证据": [{"title": "制度", "excerpt": "备份保留"},
                    {"title": "制度", "excerpt": "30天"}, {"title": "其他", "excerpt": "备份保留30天"}]}]
        after = [{"编号": "Q01", "证据": [{"title": "制度", "excerpt": "制度规定备份保留30天。"}]}]
        report = compare(refs, before, after)
        self.assertEqual((report["eligible"], report["baseline_matches"], report["candidate_matches"]), (1, 0, 1))
        self.assertEqual(report["improved"], ["Q01"])

    def test_does_not_ignore_numeric_sign_or_decimal(self):
        refs = [{"编号": "Q01", "期望行为": "可回答", "参考答案要点": "亏损-1.5万元", "参考来源": "制度.docx"}]
        before = [{"编号": "Q01", "证据": [{"title": "制度", "excerpt": "亏损15万元"}]}]
        after = [{"编号": "Q01", "证据": [{"title": "制度", "excerpt": "亏损-1.5万元"}]}]
        self.assertEqual(compare(refs, before, after)["improved"], ["Q01"])

    def test_excludes_conflict_refusal_cases_from_answer_recall(self):
        refs = [{"编号": "Q37", "期望行为": "应拒答或提示冲突", "参考答案要点": "不确定", "参考来源": "甲.docx"}]
        row = {"编号": "Q37", "证据": []}
        self.assertEqual(compare(refs, [row], [row])["eligible"], 0)

    def test_does_not_join_separate_numbers_or_discard_spaced_signs(self):
        refs = [{"编号": "Q01", "期望行为": "可回答", "参考答案要点": "数值12", "参考来源": "制度.docx"}]
        baseline = [{"编号": "Q01", "证据": [{"title": "制度", "excerpt": "数值1 2"}]}]
        self.assertEqual(compare(refs, baseline, baseline)["baseline_matches"], 0)
        refs[0]["参考答案要点"] = "亏损1万元"
        baseline[0]["证据"][0]["excerpt"] = "亏损 - 1万元"
        self.assertEqual(compare(refs, baseline, baseline)["baseline_matches"], 0)

    def test_rejects_duplicate_and_missing_case_ids(self):
        refs = [{"编号": "Q01", "期望行为": "可回答", "参考答案要点": "30天", "参考来源": "制度.docx"}]
        row = {"编号": "Q01", "证据": []}
        with self.assertRaises(ValueError):
            compare(refs, [row, row], [row])
        with self.assertRaises(ValueError):
            compare(refs, [], [row])


if __name__ == "__main__":
    unittest.main()
