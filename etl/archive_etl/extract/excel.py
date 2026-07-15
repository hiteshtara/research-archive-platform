from pathlib import Path

import pandas as pd


def read_excel_file(file_path: str | Path, sheet_name: str | int = 0) -> pd.DataFrame:
    path = Path(file_path)

    if not path.exists():
        raise FileNotFoundError(f"Input file not found: {path}")

    if path.suffix.lower() not in {".xlsx", ".xls"}:
        raise ValueError(f"Expected an Excel file, received: {path.suffix}")

    return pd.read_excel(path, sheet_name=sheet_name)
