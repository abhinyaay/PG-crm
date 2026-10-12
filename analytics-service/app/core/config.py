"""Application settings, loaded exclusively from environment variables."""

from __future__ import annotations

from functools import lru_cache
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration.

    Every value can be overridden with an environment variable of the same name (case-insensitive).
    Defaults are safe for local development and contain no secrets.
    """

    model_config = SettingsConfigDict(env_file=None, extra="ignore", case_sensitive=False)

    app_name: str = "pgms-analytics"
    database_url: str = "postgresql+psycopg://analytics_ro@localhost:5432/pgms"
    redis_url: str = "redis://localhost:6379/0"

    jwks_url: str = "http://backend:8080/api/v1/auth/jwks"
    jwt_issuer: str = "pgms-auth"
    jwt_audience: str = "pgms-api"
    jwt_leeway_seconds: int = Field(default=30, ge=0, le=300)
    jwks_cache_seconds: int = Field(default=300, ge=10)
    jwks_timeout_seconds: float = Field(default=5.0, gt=0)
    jwks_min_refresh_seconds: float = Field(default=10.0, ge=0)

    business_timezone: str = "Asia/Kolkata"
    cache_ttl_seconds: int = Field(default=300, ge=0)
    log_level: str = "INFO"
    cors_origins: str = ""

    db_statement_timeout_ms: int = Field(default=10_000, ge=100)
    db_connect_timeout_seconds: int = Field(default=5, ge=1)
    db_pool_size: int = Field(default=5, ge=1)
    db_max_overflow: int = Field(default=5, ge=0)

    redis_socket_timeout_seconds: float = Field(default=0.3, gt=0)
    redis_retry_cooldown_seconds: float = Field(default=5.0, ge=0)

    max_report_months: int = Field(default=60, ge=1)

    @field_validator("business_timezone")
    @classmethod
    def _valid_timezone(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValueError(f"Unknown timezone: {value}") from exc
        return value

    @field_validator("log_level")
    @classmethod
    def _upper_level(cls, value: str) -> str:
        return value.upper()

    @property
    def cors_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
