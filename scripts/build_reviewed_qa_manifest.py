"""将人工审核结果转换为不含问题、声明和引文原文的问答审核清单。"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import sys
import unicodedata
import uuid
from pathlib import Path


def _digest(key, value):
    return hmac.new(key, value.encode("utf-8"), hashlib.sha256).hexdigest()


def _uuid(value):
    if not isinstance(value, str):
        raise ValueError("invalid identifier")
    return str(uuid.UUID(value))


def _question(value):
    if not isinstance(value, str) or not value.strip() or len(value) > 2000:
        raise ValueError("invalid question")
    if any(ch.isspace() and ch not in " \t\r\n" and unicodedata.category(ch) != "Zs" for ch in value):
        raise ValueError("unsupported whitespace")
    semantic = "".join(ch for ch in value if not ch.isspace() and not unicodedata.category(ch).startswith("P"))
    if unicodedata.normalize("NFKC", semantic) != semantic:
        raise ValueError("ambiguous question")
    normalized = " ".join(unicodedata.normalize("NFKC", value).split())
    if not normalized or len(normalized) > 2000:
        raise ValueError("invalid normalized question")
    return normalized


def build_manifest(reviewed, key):
    if not isinstance(key, bytes) or len(key) < 32 or not isinstance(reviewed, dict):
        raise ValueError("invalid input")
    questions = reviewed.get("questions")
    if not isinstance(questions, list) or not 1 <= len(questions) <= 64:
        raise ValueError("invalid questions")
    output = []
    for item in questions:
        if not isinstance(item, dict) or item.get("allDocumentsReviewed") is not True:
            raise ValueError("review not approved")
        question = _question(item.get("question"))
        aliases = item.get("questionAliases", [])
        if not isinstance(aliases, list) or len(aliases) > 63:
            raise ValueError("invalid question aliases")
        variants = [(question, _uuid(item["approvalId"]))]
        for alias in aliases:
            if not isinstance(alias, dict) or alias.get("reviewed") is not True:
                raise ValueError("alias not approved")
            variants.append((_question(alias.get("question")), _uuid(alias.get("approvalId"))))
        if len(output) + len(variants) > 64:
            raise ValueError("too many reviewed questions")
        docs, claims = item.get("documents"), item.get("claims")
        if not isinstance(docs, list) or not 1 <= len(docs) <= 128 or not isinstance(claims, list) or not 1 <= len(claims) <= 128:
            raise ValueError("invalid review")
        prepared, roles = [], {}
        for document in docs:
            doc_id = _uuid(document["documentId"])
            fingerprint = document["contextFingerprint"]
            role = document["relation"]
            if doc_id in roles or role not in ("RELEVANT", "UNRELATED") or not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
                raise ValueError("invalid document")
            roles[doc_id] = role
            prepared.append({"documentId": doc_id, "contextFingerprint": fingerprint, "relation": role})
        approved = []
        for claim in claims:
            doc_id = _uuid(claim["documentId"])
            node, text, quote = claim["nodeId"], claim["text"], claim["quote"]
            if (roles.get(doc_id) != "RELEVANT" or not isinstance(node, str) or not node.strip() or len(node) > 200
                    or not isinstance(text, str) or not text.strip() or len(text) > 2000
                    or not isinstance(quote, str) or not quote.strip() or len(quote) > 1200 or text not in quote):
                raise ValueError("invalid claim")
            approved.append({"documentId": doc_id, "versionId": _uuid(claim["versionId"]), "nodeId": node,
                             "claimDigest": _digest(key, "claim:" + text), "quoteDigest": _digest(key, "quote:" + quote)})
        if len({(c["documentId"], c["versionId"], c["nodeId"], c["claimDigest"], c["quoteDigest"]) for c in approved}) != len(approved):
            raise ValueError("duplicate claim")
        for variant, approval_id in variants:
            output.append({"questionDigest": _digest(key, "question:" + variant), "approvalId": approval_id,
                           "allDocumentsReviewed": True, "documents": prepared, "claims": approved})
    if len({item["questionDigest"] for item in output}) != len(output):
        raise ValueError("duplicate question")
    return {"questions": output}


def _unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


def seal_manifest(manifest, key):
    payload = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(payload) > 262_144:
        raise ValueError("output too large")
    encoded = base64.b64encode(payload).decode("ascii")
    return {"payload": encoded, "signature": _digest(key, "manifest:" + encoded)}


def main():
    parser = argparse.ArgumentParser(description="生成不含问答原文的人工审核清单")
    parser.add_argument("--input", required=True, help="人工审核输入 JSON 的绝对路径")
    parser.add_argument("--output", required=True, help="新建输出 JSON 的绝对路径")
    args = parser.parse_args()
    try:
        source, destination = Path(args.input), Path(args.output)
        if not source.is_absolute() or not destination.is_absolute() or source.resolve() == destination.resolve() or source.stat().st_size > 1_048_576:
            raise ValueError("invalid path")
        key = os.environ.get("RAG_QA_QUESTION_HMAC_KEY", "").encode("utf-8")
        reviewed = json.loads(source.read_text(encoding="utf-8-sig"), object_pairs_hook=_unique_pairs)
        manifest = build_manifest(reviewed, key)
        rendered = json.dumps(seal_manifest(manifest, key), ensure_ascii=False, indent=2) + "\n"
        if len(rendered.encode("utf-8")) > 524_288:
            raise ValueError("output too large")
        with destination.open("x", encoding="utf-8", newline="\n") as output:
            output.write(rendered)
        print("已生成审核清单；原始审核资料请继续保存在受控位置。")
    except (OSError, ValueError, KeyError, TypeError, UnicodeError):
        print("无法生成清单：请检查审批状态、字段、密钥和路径。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
