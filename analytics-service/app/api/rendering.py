"""Turns a computed report into the requested representation (JSON model or exported file)."""

from __future__ import annotations

from datetime import date

from fastapi import Response
from pydantic import BaseModel

from app.reports.registry import ExporterRegistry
from app.reports.tabular import model_to_tabular

EXPORT_RESPONSES: dict[int | str, dict[str, object]] = {
    200: {
        "description": "Report as JSON (default) or as a multi-section CSV file when `format=csv`.",
        "content": {"text/csv": {"schema": {"type": "string"}}},
    }
}


def render(
    result: BaseModel, fmt: str, report_name: str, exporters: ExporterRegistry, today: date
) -> BaseModel | Response:
    if fmt == "json":
        return result
    exporter = exporters.get(fmt)
    exported = exporter.export(model_to_tabular(report_name, result))
    filename = f"{report_name}-{today.isoformat()}.{exported.extension}"
    return Response(
        content=exported.content,
        media_type=exported.media_type,
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )
