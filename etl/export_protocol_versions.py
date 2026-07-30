import csv
from pathlib import Path

import oracledb

from archive_etl.config.settings import require_oracle_environment

PROJECT_ROOT = Path(__file__).resolve().parents[1]
ETL_ROOT = Path(__file__).resolve().parent
SQL_FILE = PROJECT_ROOT / "oracle" / "protocol" / "export_protocol_versions.sql"
OUTPUT_FILE = ETL_ROOT / "data" / "protocol_versions.csv"
FETCH_SIZE = 5000


def main() -> None:
    sql = SQL_FILE.read_text(encoding="utf-8").strip()

    # Oracle drivers execute SQL without the SQL*Plus trailing terminator.
    if sql.endswith(";"):
        sql = sql[:-1].rstrip()

    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)

    credentials = require_oracle_environment()

    with oracledb.connect(
        user=credentials["ORACLE_USER"],
        password=credentials["ORACLE_PASSWORD"],
        dsn=credentials["ORACLE_DSN"],
    ) as connection:
        print("Connected to Oracle")

        with connection.cursor() as cursor:
            cursor.arraysize = FETCH_SIZE
            cursor.execute(sql)

            columns = [column[0].lower() for column in cursor.description]
            row_count = 0

            with OUTPUT_FILE.open("w", newline="", encoding="utf-8") as csv_file:
                writer = csv.writer(csv_file)
                writer.writerow(columns)

                while True:
                    rows = cursor.fetchmany(FETCH_SIZE)
                    if not rows:
                        break

                    writer.writerows(rows)
                    row_count += len(rows)
                    print(f"Exported {row_count:,} rows...", end="\r")

    print(f"\nExported {row_count:,} rows")
    print(f"CSV written to: {OUTPUT_FILE}")


if __name__ == "__main__":
    main()
