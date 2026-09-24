import asyncio
from pathlib import Path
from typing import Any

import pytest

from parser_worker.docling_adapter import DoclingConverter


class BlockingProcess:
    def __init__(self) -> None:
        self.returncode: int | None = None
        self.killed = False
        self._finished = asyncio.Event()

    async def wait(self) -> int:
        await self._finished.wait()
        return self.returncode or 0

    def kill(self) -> None:
        self.killed = True
        self.returncode = -9
        self._finished.set()


def test_cancelled_conversion_terminates_parser_subprocess(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    async def scenario() -> None:
        process = BlockingProcess()
        spawned = asyncio.Event()

        async def fake_spawn(*args: Any, **kwargs: Any) -> BlockingProcess:
            spawned.set()
            return process

        monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_spawn)

        task = asyncio.create_task(
            DoclingConverter().convert(Path("sample.md"), "sample.md", "text/markdown")
        )
        await asyncio.wait_for(spawned.wait(), timeout=1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

        assert process.killed is True

    asyncio.run(scenario())


def test_real_docling_converts_locked_markdown_sample() -> None:
    sample = Path(__file__).parent / "samples" / "complex-table.md"

    result = asyncio.run(
        DoclingConverter().convert(sample, sample.name, "text/markdown")
    )

    assert result.page_count >= 1
    text = "\n".join(node.text for node in result.nodes)
    assert "项目计划" in text
    assert "知识库" in text


def test_real_docling_converts_representative_pdf(tmp_path: Path) -> None:
    sample = tmp_path / "foundation.pdf"
    sample.write_bytes(_minimal_pdf("Knowledge Foundation Acceptance"))

    result = asyncio.run(
        DoclingConverter().convert(sample, sample.name, "application/pdf")
    )

    assert result.page_count == 1
    assert "Knowledge Foundation Acceptance" in "\n".join(
        node.text for node in result.nodes
    )


def _minimal_pdf(text: str) -> bytes:
    content = f"BT /F1 12 Tf 72 720 Td ({text}) Tj ET".encode("ascii")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        (
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"
        ),
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length " + str(len(content)).encode("ascii") + b" >>\nstream\n"
        + content
        + b"\nendstream",
    ]
    payload = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for number, body in enumerate(objects, start=1):
        offsets.append(len(payload))
        payload.extend(f"{number} 0 obj\n".encode("ascii"))
        payload.extend(body)
        payload.extend(b"\nendobj\n")
    xref_offset = len(payload)
    payload.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    payload.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        payload.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    payload.extend(
        (
            f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
            f"startxref\n{xref_offset}\n%%EOF\n"
        ).encode("ascii")
    )
    return bytes(payload)
