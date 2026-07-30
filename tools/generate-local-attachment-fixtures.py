#!/usr/bin/env python3
"""Generate synthetic Subaward attachment fixtures for local dev only.

Writes 3 small, harmless placeholder files into local-data/attachments/
(gitignored - never committed, see .gitignore). These are used together
with scripts/seed-local-subaward-attachments.sql to exercise the
attachment list/download endpoints and the UI's attachment section
without any real BU data, Oracle access, or AWS access.

Uses only the Python standard library (no project dependencies) so it can
be run standalone: `python3 tools/generate-local-attachment-fixtures.py`

Prefer running the full local attachment setup (this script, the SQL
seed, and a verification count) via ./scripts/setup-local.sh instead.
"""

from __future__ import annotations

import zipfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
OUTPUT_DIR = PROJECT_ROOT / "local-data" / "attachments"


def write_sample_note(directory: Path) -> None:
    (directory / "sample-note.txt").write_text(
        "This is a synthetic sample attachment used only for local "
        "UI/API development.\n\n"
        "It does not contain any real Boston University data, Kuali "
        "data, or content extracted from Oracle or AWS. See "
        "scripts/seed-local-subaward-attachments.sql for how this file "
        "is wired up.\n",
        encoding="utf-8",
    )


def write_sample_agreement_pdf(directory: Path) -> None:
    objects = [
        "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
        "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
        (
            "3 0 obj\n<< /Type /Page /Parent 2 0 R "
            "/MediaBox [0 0 300 150] /Contents 4 0 R "
            "/Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n"
        ),
    ]
    content = (
        "BT /F1 12 Tf 20 100 Td "
        "(Synthetic sample agreement - not a real BU document.) "
        "Tj ET"
    )
    objects.append(
        f"4 0 obj\n<< /Length {len(content)} >>\nstream\n"
        f"{content}\nendstream\nendobj\n"
    )
    objects.append(
        "5 0 obj\n<< /Type /Font /Subtype /Type1 "
        "/BaseFont /Helvetica >>\nendobj\n"
    )

    body = "%PDF-1.4\n"
    offsets = [0]
    for obj in objects:
        offsets.append(len(body))
        body += obj

    xref_offset = len(body)
    xref = f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n"
    for offset in offsets[1:]:
        xref += f"{offset:010d} 00000 n \n"

    trailer = (
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
        f"startxref\n{xref_offset}\n%%EOF"
    )

    pdf_bytes = (body + xref + trailer).encode("latin-1")
    (directory / "sample-agreement.pdf").write_bytes(pdf_bytes)


def write_sample_budget_xlsx(directory: Path) -> None:
    # Hand-built minimal OOXML workbook (stdlib zipfile only, no
    # openpyxl dependency) - one sheet, a handful of placeholder rows.
    content_types = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        "</Types>"
    )
    root_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
        "</Relationships>"
    )
    workbook_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        '<sheets><sheet name="Budget" sheetId="1" r:id="rId1"/></sheets>'
        "</workbook>"
    )
    workbook_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
        "</Relationships>"
    )

    def row(*cells: str) -> str:
        cell_xml = "".join(
            f'<c t="inlineStr"><is><t>{value}</t></is></c>'
            for value in cells
        )
        return f"<row>{cell_xml}</row>"

    sheet_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        "<sheetData>"
        + row("Category", "Amount")
        + row("Synthetic sample budget - not a real BU document", "")
        + row("Personnel", "0")
        + row("Supplies", "0")
        + row("Total", "0")
        + "</sheetData></worksheet>"
    )

    path = directory / "sample-budget.xlsx"
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", content_types)
        archive.writestr("_rels/.rels", root_rels)
        archive.writestr("xl/workbook.xml", workbook_xml)
        archive.writestr("xl/_rels/workbook.xml.rels", workbook_rels)
        archive.writestr("xl/worksheets/sheet1.xml", sheet_xml)


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    write_sample_note(OUTPUT_DIR)
    write_sample_agreement_pdf(OUTPUT_DIR)
    write_sample_budget_xlsx(OUTPUT_DIR)
    for name in ("sample-note.txt", "sample-agreement.pdf", "sample-budget.xlsx"):
        size = (OUTPUT_DIR / name).stat().st_size
        print(f"wrote {OUTPUT_DIR / name} ({size} bytes)")


if __name__ == "__main__":
    main()
