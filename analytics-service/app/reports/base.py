"""Exporter abstraction.

Every report is first converted to a format-neutral :class:`TabularReport` (a list of named tables).
Exporters turn that into bytes. New formats (e.g. Excel, one sheet per section) only need a new
:class:`ReportExporter` registered in :mod:`app.reports.registry`.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any


@dataclass
class Section:
    name: str
    columns: list[str]
    rows: list[list[Any]] = field(default_factory=list)


@dataclass
class TabularReport:
    title: str
    sections: list[Section]


@dataclass(frozen=True)
class ExportedFile:
    content: bytes
    media_type: str
    extension: str


class ReportExporter(ABC):
    format: str
    media_type: str
    extension: str

    @abstractmethod
    def export(self, report: TabularReport) -> ExportedFile: ...
