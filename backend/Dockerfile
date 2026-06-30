# syntax=docker/dockerfile:1
FROM python:3.12-slim

# Pull uv in from the official image so we do not depend on pip at build time.
COPY --from=ghcr.io/astral-sh/uv:0.10.6 /uv /uvx /usr/local/bin/

WORKDIR /app

# Install dependencies first so this layer is cached when only the app
# source changes. README.md is required by hatchling even for `uv sync`
# because pyproject.toml declares it as the readme path.
COPY pyproject.toml uv.lock README.md ./
RUN uv sync --frozen --no-dev

# Copy the application source.
COPY app ./app

# Run as a non-root user.
RUN useradd --create-home --shell /bin/bash backend \
    && chown -R backend:backend /app
USER backend

EXPOSE 8000

CMD ["uv", "run", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
