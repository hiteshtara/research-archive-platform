from pathlib import Path

import psycopg

from archive_etl.config.settings import require_postgres_environment

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SQL_FILE = PROJECT_ROOT / "sql" / "verify" / "protocol_core_reconciliation.sql"


def main() -> None:
    sql = SQL_FILE.read_text(encoding="utf-8")
    postgres = require_postgres_environment()

    with psycopg.connect(
        host=postgres["POSTGRES_HOST"],
        port=int(postgres["POSTGRES_PORT"]),
        dbname=postgres["POSTGRES_DB"],
        user=postgres["POSTGRES_USER"],
        password=postgres["POSTGRES_PASSWORD"],
        sslmode=postgres["POSTGRES_SSLMODE"],
    ) as conn:
        with conn.cursor() as cur:
            cur.execute(sql)

            while True:
                if cur.description:
                    columns = [col.name for col in cur.description]
                    rows = cur.fetchall()

                    print("\n" + " | ".join(columns))
                    print("-" * 100)

                    for row in rows:
                        print(" | ".join("" if value is None else str(value) for value in row))

                if not cur.nextset():
                    break

if __name__ == "__main__":
    main()
