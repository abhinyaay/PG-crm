"""Exporter registry keyed by the ``format`` query parameter."""

from __future__ import annotations

from app.reports.base import ReportExporter
from app.reports.csv_exporter import CsvExporter


class ExporterRegistry:
    def __init__(self, exporters: list[ReportExporter] | None = None) -> None:
        self._exporters: dict[str, ReportExporter] = {}
        for exporter in exporters or []:
            self.register(exporter)

    def register(self, exporter: ReportExporter) -> None:
        self._exporters[exporter.format] = exporter

    def get(self, fmt: str) -> ReportExporter:
        try:
            return self._exporters[fmt]
        except KeyError as exc:
            raise KeyError(f"No exporter registered for format '{fmt}'") from exc

    def formats(self) -> list[str]:
        return sorted(self._exporters)


default_registry = ExporterRegistry([CsvExporter()])
