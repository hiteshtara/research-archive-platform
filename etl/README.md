# Research Archive Platform — ETL

Python pipeline that extracts approved data from BU's legacy Oracle/Kuali
sources, validates it, and loads it into the archive's PostgreSQL database.
See the [repository root README](../README.md) for the overall data flow.

## Layout

- `extract/`, `transform/`, `load/` (via `load_*_from_csv.py`, `load_from_s3.py`,
  `load_composite_from_s3.py`) — per-domain extraction and loading scripts
  (Protocols, Awards, Proposals, Negotiations, Subawards).
- `archive_etl/`, `config/`, `validate/`, `upload/` — shared pipeline code.
- `run_export.py`, `run_composite_export.py` — export entry points.
- `run_protocol_reconciliation.py`, `run_protocol_personnel_reconciliation.py`,
  `analyze_protocol_parent_resolution.py` — data-quality/reconciliation checks.
- `archive_attachments.py`, `archive_subaward_attachments.py` — document/attachment
  archival.
- `tests/` — pytest test suite.

## Development

This project uses [uv](https://github.com/astral-sh/uv) (see `pyproject.toml` /
`uv.lock`). Typical workflow:

```
uv sync
uv run pytest
```

Extraction requires the BU VPN; loading targets the archive's PostgreSQL
database (see repository root `.envrc` / Terraform for connection details).
