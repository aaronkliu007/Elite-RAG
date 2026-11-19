"""受控评测辅助工具：只输出案例编号与计数，不输出问题、答案或证据正文。"""
import argparse
import csv
import json
import re
import unicodedata
from pathlib import Path


def _normalize(value):
    value = unicodedata.normalize("NFKC", value).casefold()
    out = []
    for index, char in enumerate(value):
        if char.isalnum():
            out.append(char)
        elif char.isspace():
            before = value[:index].rstrip()
            after = value[index + 1:].lstrip()
            if before and after and before[-1].isdigit() and after[0].isdigit() and (not out or out[-1] != " "):
                out.append(" ")
        elif unicodedata.category(char).startswith(("P", "S")):
            previous_digit = index > 0 and value[index - 1].isdigit()
            next_digit = index + 1 < len(value) and value[index + 1].isdigit()
            if previous_digit or next_digit or char in "%‰+-−":
                out.append(char)
    return "".join(out)


def _index(rows):
    if not isinstance(rows, list):
        raise ValueError("INVALID_ROWS")
    result = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("INVALID_ROW")
        case_id = row.get("编号")
        if not isinstance(case_id, str) or not re.fullmatch(r"Q[0-9]{2,4}", case_id) or case_id in result:
            raise ValueError("INVALID_CASE_ID")
        result[case_id] = row
    return result


def _matched(reference, result):
    answer = reference.get("参考答案要点")
    sources = reference.get("参考来源")
    evidence = result.get("证据")
    if not isinstance(answer, str) or not isinstance(sources, str) or not isinstance(evidence, list):
        raise ValueError("INVALID_CASE_DATA")
    answer = _normalize(answer)
    titles = {Path(name.strip()).stem for name in re.split(r"[;；]", sources) if name.strip()}
    if not answer or not titles or "无" in titles:
        raise ValueError("MISSING_REFERENCE")
    for item in evidence:
        if not isinstance(item, dict) or not isinstance(item.get("title"), str) or not isinstance(item.get("excerpt"), str):
            raise ValueError("INVALID_EVIDENCE")
        if item["title"] in titles and answer in _normalize(item["excerpt"]):
            return True
    return False


def compare(reference_rows, baseline_rows, candidate_rows):
    reference, baseline, candidate = map(_index, (reference_rows, baseline_rows, candidate_rows))
    if not reference or reference.keys() != baseline.keys() or reference.keys() != candidate.keys():
        raise ValueError("CASE_SET_MISMATCH")
    report = {"eligible": 0, "baseline_matches": 0, "candidate_matches": 0, "improved": [], "regressed": []}
    for case_id in sorted(reference):
        expected = reference[case_id].get("期望行为")
        if expected not in ("可回答", "应拒答", "应拒答或提示冲突"):
            raise ValueError("INVALID_EXPECTED_BEHAVIOR")
        if expected != "可回答":
            continue
        previous = _matched(reference[case_id], baseline[case_id])
        current = _matched(reference[case_id], candidate[case_id])
        report["eligible"] += 1
        report["baseline_matches"] += previous
        report["candidate_matches"] += current
        if current and not previous:
            report["improved"].append(case_id)
        elif previous and not current:
            report["regressed"].append(case_id)
    return report


def main():
    parser = argparse.ArgumentParser(description="对比指定来源单片段的字面包含情况，不衡量语义质量。")
    parser.add_argument("--references", required=True)
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--candidate", required=True)
    args = parser.parse_args()
    with open(args.references, encoding="utf-8-sig", newline="") as source:
        refs = list(csv.DictReader(source))
    with open(args.baseline, encoding="utf-8-sig") as source:
        baseline = json.load(source)["results"]
    with open(args.candidate, encoding="utf-8-sig") as source:
        candidate = json.load(source)["results"]
    print(json.dumps(compare(refs, baseline, candidate), ensure_ascii=False))


if __name__ == "__main__":
    main()
