"""Writes small, valid, FICTIONAL PDF files for the lab's synthetic attachments."""
import sys
from pathlib import Path

NAMES = ["SYNTHETIC-award-A.pdf", "SYNTHETIC-award-B.pdf", "SYNTHETIC-award-A-seq1.pdf",
         "SYNTHETIC-award-A-child.pdf", "SYNTHETIC-award-I-seq2.pdf"]


def pdf(text):
    stream = f"BT /F1 18 Tf 72 720 Td ({text}) Tj ET".encode()
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>",
               b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
               b"/Resources << /Font << /F1 5 0 R >> >> >>",
               b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
               b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    out, offsets = bytearray(b"%PDF-1.4\n"), []
    for i, body in enumerate(objects, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + body + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    out += b"".join(f"{o:010d} 00000 n \n".encode() for o in offsets)
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)


target = Path(sys.argv[1]) / "synthetic"
target.mkdir(parents=True, exist_ok=True)
for name in NAMES:
    (target / name).write_bytes(pdf(name.removesuffix(".pdf") + " - FICTIONAL attachment"))
print(f"{len(NAMES)} synthetic PDFs in {target}")
