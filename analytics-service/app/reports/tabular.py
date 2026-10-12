"""Generic conversion of a response model into named tables.

* every scalar reachable through nested objects goes into a ``summary`` table (``metric, value``)
  using dotted paths, e.g. ``snapshot.occupancyRatePct``;
* every list of objects becomes its own table named after the field path, e.g. ``monthlyTrend``.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel

from app.reports.base import Section, TabularReport


def _flatten(prefix: str, value: Any, out: dict[str, Any]) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            _flatten(f"{prefix}.{key}" if prefix else str(key), nested, out)
    elif isinstance(value, list):
        out[prefix] = "; ".join(str(v) for v in value)
    else:
        out[prefix] = value


def _table(name: str, items: list[dict[str, Any]]) -> Section:
    flat_rows: list[dict[str, Any]] = []
    columns: list[str] = []
    for item in items:
        flat: dict[str, Any] = {}
        _flatten("", item, flat)
        for col in flat:
            if col not in columns:
                columns.append(col)
        flat_rows.append(flat)
    return Section(name=name, columns=columns, rows=[[row.get(c) for c in columns] for row in flat_rows])


def model_to_tabular(title: str, model: BaseModel) -> TabularReport:
    data = model.model_dump(mode="json", by_alias=True)
    summary: list[list[Any]] = []
    tables: list[Section] = []

    def walk(prefix: str, value: Any) -> None:
        if isinstance(value, dict):
            for key, nested in value.items():
                walk(f"{prefix}.{key}" if prefix else str(key), nested)
        elif isinstance(value, list) and (not value or all(isinstance(v, dict) for v in value)):
            tables.append(_table(prefix, value))
        elif isinstance(value, list):
            summary.append([prefix, "; ".join(str(v) for v in value)])
        else:
            summary.append([prefix, value])

    walk("", data)
    return TabularReport(title=title, sections=[Section("summary", ["metric", "value"], summary), *tables])
