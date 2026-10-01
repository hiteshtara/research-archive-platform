"""Simulated user-pool storage (its own Postgres database, lab_pool)."""
import json
import os

import psycopg
from psycopg.rows import dict_row

DSN = os.environ["LAB_POOL_DATABASE_URL"]


def _conn():
    return psycopg.connect(DSN, autocommit=True, row_factory=dict_row)


def cognito_exec(sql, params=()):
    with _conn() as c:
        c.execute(sql, params)


def cognito_one(sql, params=()):
    with _conn() as c:
        return c.execute(sql, params).fetchone()


def cognito_all(sql, params=()):
    with _conn() as c:
        return c.execute(sql, params).fetchall()


def setting(key, default):
    row = cognito_one("SELECT value FROM setting WHERE key = %s", (key,))
    return row["value"] if row else default


def event(kind, username, detail):
    cognito_exec("INSERT INTO pool_event (kind, username, detail) VALUES (%s, %s, %s)",
                 (kind, username, json.dumps(detail)))
