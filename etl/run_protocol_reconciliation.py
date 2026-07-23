import os
from pathlib import Path

import psycopg

SQL_FILE = Path("sql/verify/protocol_core_reconciliation.sql")

def main() -> None:
    sql = SQL_FILE.read_text(encoding="utf-8")

    with psycopg.connect(
        host=os.environ.get("POSTGRES_HOST", "localhost"),
        port=int(os.environ.get("POSTGRES_PORT", "15432")),
        dbname=os.environ["POSTGRES_DB"],
        user=os.environ["POSTGRES_USER"],
        password=os.environ["POSTGRES_PASSWORD"],
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
