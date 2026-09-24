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
