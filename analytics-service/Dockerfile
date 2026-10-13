# syntax=docker/dockerfile:1

# ---- build stage: resolve and install dependencies into an isolated virtualenv ----
FROM python:3.14-slim AS builder

ENV PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_DEFAULT_TIMEOUT=120 \
    PIP_RETRIES=5 \
    PYTHONDONTWRITEBYTECODE=1

WORKDIR /build
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY requirements.txt .
# All dependencies ship manylinux wheels (psycopg[binary] bundles libpq), so no compiler is needed.
RUN pip install --only-binary=:all: -r requirements.txt

# ---- runtime stage ----
FROM python:3.14-slim AS runtime

LABEL org.opencontainers.image.title="pgms-analytics" \
      org.opencontainers.image.description="Read-only analytics service for the PG management platform"

ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    # Trust X-Forwarded-* from the reverse proxy; restrict to the proxy's address in production if known.
    FORWARDED_ALLOW_IPS="*"

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

COPY --from=builder /opt/venv /opt/venv
WORKDIR /app
COPY --chown=app:app app ./app

USER app
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD ["python", "-c", "import sys, urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health/live', timeout=4).status == 200 else 1)"]

CMD ["uvicorn", "app.main:create_app", "--factory", \
     "--host", "0.0.0.0", "--port", "8000", \
     "--proxy-headers", "--no-access-log", "--no-server-header"]
