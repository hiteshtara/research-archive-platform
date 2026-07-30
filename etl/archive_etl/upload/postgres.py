from __future__ import annotations

from sqlalchemy import create_engine
from sqlalchemy.engine import URL, Engine

from archive_etl.config.settings import require_postgres_environment


def create_postgres_engine() -> Engine:
    values = require_postgres_environment()

    connection_url = URL.create(
        drivername="postgresql+psycopg",
        username=values["POSTGRES_USER"],
        password=values["POSTGRES_PASSWORD"],
        host=values["POSTGRES_HOST"],
        port=int(values["POSTGRES_PORT"]),
        database=values["POSTGRES_DB"],
        query={"sslmode": values["POSTGRES_SSLMODE"]},
    )

    return create_engine(
        connection_url,
        pool_pre_ping=True,
        future=True,
    )
