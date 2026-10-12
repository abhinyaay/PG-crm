from __future__ import annotations

import csv
import io
from typing import Any

from app.reports.base import ExportedFile, ReportExporter, TabularReport

_FORMULA_PREFIXES = ("=", "+", "-", "@", "\t", "\r")


def _cell(value: Any) -> Any:
    """Neutralise spreadsheet formula injection in text cells (numbers are left untouched)."""
    if value is None:
        return ""
    if isinstance(value, str) and value.startswith(_FORMULA_PREFIXES):
        return "'" + value
    return value


class CsvExporter(ReportExporter):
    """Multi-section CSV: each section starts with a ``# <name>`` line, then a header row and data
    rows; sections are separated by a blank line."""

    format = "csv"
    media_type = "text/csv; charset=utf-8"
    extension = "csv"

    def export(self, report: TabularReport) -> ExportedFile:
        buffer = io.StringIO()
        writer = csv.writer(buffer, lineterminator="\n")
        for index, section in enumerate(report.sections):
            if index:
                writer.writerow([])
            writer.writerow([f"# {section.name}"])
            if section.columns:
                writer.writerow(section.columns)
            for row in section.rows:
                writer.writerow([_cell(c) for c in row])
        return ExportedFile(buffer.getvalue().encode("utf-8"), self.media_type, self.extension)
