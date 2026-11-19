"""仅供影子评测的字面依据检查；单条声明不证明其他资料没有矛盾。"""
import re


def has_literal_witness(question, conflict_term, question_term, documents, title, quote):
    """检查模型引用的整条原文是否明确声称两个指定事项独立；不作放行判定。"""
    if not all(isinstance(value, str) and value for value in (question, conflict_term, question_term, title, quote)):
        return False
    if question_term not in question or not isinstance(documents, list):
        return False
    matched = [doc for doc in documents if isinstance(doc, dict) and doc.get("title") == title]
    if len(matched) != 1 or not isinstance(matched[0].get("nodes"), list):
        return False
    if sum(1 for node in matched[0]["nodes"] if isinstance(node, dict) and node.get("text") == quote) != 1:
        return False
    first, second = re.escape(conflict_term), re.escape(question_term)
    pattern = rf"(?:{first}与{second}|{second}与{first})(?:完全无关|相互独立|互不影响)[。.]?"
    return re.fullmatch(pattern, quote) is not None
