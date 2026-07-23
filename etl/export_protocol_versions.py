import csv
import os
from pathlib import Path

import oracledb

SQL_FILE = Path("oracle/protocol/export_protocol_versions.sql")
OUTPUT_FILE = Path("etl/data/protocol_versions.csv")
FETCH_SIZE = 5000


def main() -> None:
    sql = SQL_FILE.read_text(encoding="utf-8").strip()

    # Oracle drivers execute SQL without the SQL*Plus trailing terminator.
    if sql.endswith(";"):
        sql = sql[:-1].rstrip()

    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)

    with oracledb.connect(
        user=os.environ["ORACLE_USER"],
        password=os.environ["ORACLE_PASSWORD"],
        dsn=os.environ["ORACLE_DSN"],
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
