"""Read-only database access (SQLAlchemy 2 Core + psycopg 3)."""

from __future__ import annotations

import logging
from collections.abc import Mapping
from typing import Any, Protocol

import pandas as pd
from sqlalchemy import Engine, create_engine, text
from sqlalchemy.exc import SQLAlchemyError

logger = logging.getLogger("pgms.analytics.db")


class Database(Protocol):
    def fetch_df(self, sql: str, params: Mapping[str, Any] | None = None) -> pd.DataFrame: ...
    def ping(self) -> bool: ...


class SqlDatabase:
    """Executes parameterised SELECTs and returns DataFrames.

    Safety nets in addition to the ``analytics_ro`` role (SELECT on ``analytics.*`` only):
    * every session is ``default_transaction_read_only``;
    * ``statement_timeout`` bounds every query;
    * the session ``TimeZone`` is UTC so timestamptz values arrive as UTC.
    """

    def __init__(
        self,
        url: str,
        *,
        statement_timeout_ms: int = 10_000,
        connect_timeout_seconds: int = 5,
        pool_size: int = 5,
        max_overflow: int = 5,
        application_name: str = "pgms-analytics",
    ) -> None:
        options = (
            f"-c statement_timeout={int(statement_timeout_ms)} "
            "-c default_transaction_read_only=on "
            "-c TimeZone=UTC"
        )
        self.engine: Engine = create_engine(
            url,
            pool_pre_ping=True,
            pool_size=pool_size,
            max_overflow=max_overflow,
            pool_timeout=connect_timeout_seconds,
            pool_recycle=1800,
            connect_args={
                "options": options,
                "connect_timeout": connect_timeout_seconds,
                "application_name": application_name,
            },
        )

    def fetch_df(self, sql: str, params: Mapping[str, Any] | None = None) -> pd.DataFrame:
        with self.engine.connect() as conn:
            return pd.read_sql_query(text(sql), conn, params=dict(params or {}))

    def ping(self) -> bool:
        try:
            with self.engine.connect() as conn:
                conn.execute(text("SELECT 1"))
            return True
        except SQLAlchemyError as exc:
            logger.warning("database ping failed", extra={"fields": {"error": type(exc).__name__}})
            return False

    def dispose(self) -> None:
        self.engine.dispose()
