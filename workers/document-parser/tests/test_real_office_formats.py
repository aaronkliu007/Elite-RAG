"""用虚构文档验证真实 Office 解析链，不依赖云模型或业务资料。"""

import asyncio
from pathlib import Path

from docx import Document
from openpyxl import Workbook
from pptx import Presentation

from parser_worker.docling_adapter import DoclingConverter


def test_real_docling_extracts_word_paragraph_and_table(tmp_path: Path) -> None:
    path = tmp_path / "需求说明.docx"
    document = Document()
    document.add_heading("虚构项目需求", level=1)
    document.add_paragraph("建设目标：支持知识检索。")
    table = document.add_table(rows=2, cols=2)
    table.cell(0, 0).text, table.cell(0, 1).text = "项目", "负责人"
    table.cell(1, 0).text, table.cell(1, 1).text = "知识库", "张三"
    document.add_paragraph("交付验收完成。")
    document.save(path)

    result = asyncio.run(DoclingConverter().convert(
        path, path.name, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ))
    text = "\n".join(node.text for node in result.nodes)
    assert "建设目标" in text
    assert "知识库" in text
    assert any(node.kind == "TABLE" and node.tableCells for node in result.nodes)
    assert text.index("建设目标") < text.index("知识库") < text.index("交付验收完成")
    heading = next(node for node in result.nodes if node.text == "虚构项目需求")
    paragraph = next(node for node in result.nodes if "建设目标" in node.text)
    assert heading.kind == "HEADING"
    assert paragraph.parentNodeId == heading.nodeId


def test_real_docling_extracts_excel_sheet_cells(tmp_path: Path) -> None:
    path = tmp_path / "项目清单.xlsx"
    workbook = Workbook()
    sheet = workbook.active
    assert sheet is not None
    sheet.title = "建设清单"
    sheet.append(["项目", "负责人"])
    sheet.append(["知识库", "张三"])
    workbook.save(path)

    result = asyncio.run(DoclingConverter().convert(
        path, path.name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    ))
    text = "\n".join(node.text for node in result.nodes)
    assert "知识库" in text
    assert "张三" in text
    assert any(node.kind == "TABLE" and node.tableCells for node in result.nodes)


def test_real_docling_extracts_powerpoint_slide_content(tmp_path: Path) -> None:
    path = tmp_path / "方案汇报.pptx"
    presentation = Presentation()
    slide = presentation.slides.add_slide(presentation.slide_layouts[1])
    assert slide.shapes.title is not None
    slide.shapes.title.text = "虚构建设方案"
    slide.placeholders[1].text = "首期实现知识检索"
    presentation.save(path)

    result = asyncio.run(DoclingConverter().convert(
        path, path.name, "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    ))
    text = "\n".join(node.text for node in result.nodes)
    assert "虚构建设方案" in text
    assert "首期实现知识检索" in text
    assert any(node.page == 1 for node in result.nodes)
