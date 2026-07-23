import os
from pathlib import Path

import psycopg

sql = Path("../sql/verify/protocol_personnel_reconciliation.sql").read_text(
    encoding="utf-8"
)

with psycopg.connect(
    host=os.environ["POSTGRES_HOST"],
    port=os.environ["POSTGRES_PORT"],
    dbname=os.environ["POSTGRES_DB"],
    user=os.environ["POSTGRES_USER"],
    password=os.environ["POSTGRES_PASSWORD"],
) as conn:
    with conn.cursor() as cur:
        cur.execute(sql)

        while True:
            if cur.description:
                print(" | ".join(col.name for col in cur.description))
                for row in cur.fetchall():
                    print(" | ".join("" if value is None else str(value) for value in row))

            if not cur.nextset():
                break
