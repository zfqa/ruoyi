"""Rule-based semantic chunking with page/slide provenance kept as metadata."""
from __future__ import annotations

import re
import logging
from collections import defaultdict
from typing import Literal

from data_service.models.schemas import ChartKnowledge, DocumentChunk, SemanticChunk, SemanticSource, TableData

logger = logging.getLogger(__name__)


class SemanticChunker:
    """Split documents into independently indexable sections, events, narratives and tables."""

    MIN_INDEXABLE_CHARS = 20
    MAX_CHUNK_CHARS = 2000
    _SUBJECT_ALIASES = {
        "理想": "理想汽车", "小米": "小米汽车", "吉利": "吉利汽车",
        "深天马": "天马微电子", "天马": "天马微电子",
    }
    _EVENT_SIGNAL = re.compile(
        r"发布|宣布|任命|出任|加盟|调整|合作|签署|投资|收购|购买|并购|起诉|召回|进入|进军|"
        r"上市|IPO|投产|扩产|推出|交付|布局|出售|建立|升级|暂停|延后|私有化|"
        r"加征|关税|推迟|联合|增长|下降|下跌|侵权|获得订单"
    )

    @staticmethod
    def _page_number(line: str) -> bool:
        return bool(re.fullmatch(r"\d{1,3}", line.strip()))

    @classmethod
    def _normalized_table_rows(cls, table: TableData) -> tuple[list[str], list[dict[str, str]], bool]:
        """Normalize dict/list rows without accepting malformed table structure."""
        raw_columns = list(getattr(table, "columns", []) or [])
        columns = [cls._clean_table_text(column) for column in raw_columns]
        if not columns:
            logger.warning("[TABLE_CHUNK] table has no columns; title=%s", getattr(table, "title", ""))
            return [], [], False

        normalized_rows: list[dict[str, str]] = []
        for row in list(getattr(table, "rows", []) or []):
            if isinstance(row, dict):
                normalized_rows.append({
                    columns[index]: cls._clean_table_text(row.get(raw_column, row.get(columns[index], "")))
                    for index, raw_column in enumerate(raw_columns)
                })
                continue
            if isinstance(row, (list, tuple)):
                if len(row) != len(columns):
                    logger.warning(
                        "[TABLE_CHUNK] row length mismatch; title=%s expected=%s actual=%s",
                        getattr(table, "title", ""), len(columns), len(row),
                    )
                    return columns, [], False
                normalized_rows.append({
                    columns[index]: cls._clean_table_text(value)
                    for index, value in enumerate(row)
                })
                continue
            logger.warning(
                "[TABLE_CHUNK] unsupported row type; title=%s row_type=%s",
                getattr(table, "title", ""), type(row).__name__,
            )
            return columns, [], False
        return columns, normalized_rows, True

    @classmethod
    def _table_content(cls, table: TableData) -> str:
        """Render tables with explicit row-subject/column relationships for the LLM."""
        columns, rows, valid = cls._normalized_table_rows(table)
        if not valid:
            return f"表标题: {table.title}"
        explicit_group_columns = set(table.metadata.get("group_columns", []))
        group_columns = {
            column for column in columns
            if re.search(r"前[五十]\s*家|TOP\d+", column, re.IGNORECASE)
        } | explicit_group_columns
        # Explicit normalized hierarchy columns stay on every data row. This
        # preserves the group-to-company relation instead of reducing it to a
        # detached note such as “前十家84.3%”.
        data_columns = columns if explicit_group_columns else [column for column in columns if column not in group_columns]
        group_notes = [
            value for row in rows for column, value in row.items()
            if column in group_columns and value
        ]

        lines = [f"标题: {table.title}"]
        for row in rows:
            row_label = cls._row_label(data_columns, row)
            if not row_label:
                continue
            for column in data_columns:
                raw = row.get(column, "")
                if not raw or raw == row_label or not cls._is_numberish(raw):
                    continue
                header = column if cls._meaningful_label(column) else "数值"
                value = cls._value_with_column_unit(column, raw)
                lines.append(f"行: {row_label} | 列: {header} | 值: {value}")
        if group_notes and not explicit_group_columns:
            lines.append(f"分组信息: {'；'.join(dict.fromkeys(group_notes))}")
        return "\n".join(lines).strip()

    _PLACEHOLDER_COLUMN = re.compile(r"^column_\d+$", re.IGNORECASE)

    @classmethod
    def _meaningful_label(cls, value: str) -> bool:
        text = cls._clean_table_text(value)
        if not text or cls._PLACEHOLDER_COLUMN.match(text):
            return False
        if cls._is_numberish(text):
            return False
        return True

    @classmethod
    def _is_numberish(cls, value: str) -> bool:
        text = cls._clean_table_text(value).replace(",", "")
        return bool(re.fullmatch(r"[+-]?\d+(?:\.\d+)?%?", text))

    @classmethod
    def _row_label(cls, columns: list[str], row: dict[str, str]) -> str:
        for column in columns:
            cell = cls._clean_table_text(row.get(column, ""))
            if cls._meaningful_label(cell):
                return cell
        return ""

    @staticmethod
    def _clean_table_text(value: object) -> str:
        return re.sub(r"[\u200b\ufeff]", "", str(value or "")).strip()

    @staticmethod
    def _value_with_column_unit(column: str, value: str) -> str:
        """Restore a unit declared in the source header when a cell holds only a number."""
        unit_match = re.search(r"[（(]([^（）()]+)[）)]", column)
        unit = unit_match.group(1).strip() if unit_match else ""
        if not unit or not re.fullmatch(r"[+-]?\d+(?:\.\d+)?", value.strip()):
            return value
        return value if value.endswith(unit) else f"{value}{unit}"

    @classmethod
    def _table_row_subjects(cls, table: TableData) -> list[str]:
        """Find values from the explicit business-subject column, not group labels."""
        columns, rows, valid = cls._normalized_table_rows(table)
        if not valid:
            return []
        subject_index = next(
            (
                index for index, column in enumerate(columns)
                if re.search(r"车企|企业|品牌|厂商|公司|车型|指标", column)
                and not re.search(r"前[五十]\s*家", column)
            ),
            0,
        )
        subject_column = columns[subject_index]
        return [row.get(subject_column, "") for row in rows if row.get(subject_column, "")]

    @staticmethod
    def _lines(content: str) -> list[str]:
        return [line.strip() for line in content.splitlines() if line.strip() and not SemanticChunker._page_number(line)]

    @classmethod
    def _subject(cls, value: str | None) -> str | None:
        if not value:
            return None
        return cls._SUBJECT_ALIASES.get(value.strip(), value.strip())

    @classmethod
    def _is_label(cls, line: str) -> bool:
        return bool(line and len(line) <= 30 and not re.search(r"[。！？；，,:：]", line))

    @classmethod
    def _is_event_start(cls, lines: list[str], index: int) -> bool:
        if not cls._is_label(lines[index]) or index + 1 >= len(lines):
            return False
        # A short event headline (for example "极氪进军德国市场") is
        # not a new subject label even if the following body repeats a trigger.
        if cls._EVENT_SIGNAL.search(lines[index]):
            return False
        return bool(cls._EVENT_SIGNAL.search(lines[index + 1]))

    @staticmethod
    def _is_section(lines: list[str], tables: list[TableData]) -> bool:
        if tables or len(lines) != 1:
            return False
        title = lines[0]
        return bool(re.match(r"^\d+(?:\.\d+)?[.、．]", title) or re.match(r"^[一二三四五六七八九十]+[、.]", title))

    @classmethod
    def _is_toc(cls, lines: list[str], tables: list[TableData]) -> bool:
        """Recognize navigation/contents pages without treating normal short events as TOC."""
        if tables or len(lines) < 5:
            return False
        short_count = sum(len(line) <= 24 for line in lines)
        has_long_paragraph = any(len(line) >= 80 for line in lines)
        has_statement = any(re.search(r"[。！？；]", line) for line in lines)
        # Navigation labels may contain words such as “调整” or “布局”; only a
        # substantive event sentence should disqualify a contents-page match.
        has_event_body = any(len(line) > 30 and cls._EVENT_SIGNAL.search(line) for line in lines)
        has_fact = any(re.search(r"\d{1,2}月\d{1,2}日|同比|\d+(?:\.\d+)?(?:万|辆|%)", line) for line in lines)
        navigation_terms = sum(any(term in line for term in ("目录", "CONTENTS", "行业全景", "企业行动", "产业链", "竞争追踪", "市场观察", "宏观政策")) for line in lines)
        return short_count / len(lines) >= 0.75 and not has_long_paragraph and not has_statement and not has_event_body and not has_fact and navigation_terms >= 2

    @staticmethod
    def _title(content: str, fallback: str) -> str:
        first = next((line.strip() for line in content.splitlines() if line.strip()), fallback)
        return first[:120]

    @staticmethod
    def _raw_fragment(content: str, lines: list[str]) -> str:
        """Return the exact original substring spanning the selected source lines."""
        if not lines:
            return ""
        start = content.find(lines[0])
        end_start = content.find(lines[-1], start if start >= 0 else 0)
        if start >= 0 and end_start >= start:
            return content[start : end_start + len(lines[-1])].strip()
        return "\n".join(lines).strip()

    @staticmethod
    def _source(chunk: DocumentChunk, source_text: str) -> SemanticSource:
        return SemanticSource(
            file=chunk.source.file,
            page_start=chunk.source.page,
            page_end=chunk.source.page,
            slide_start=chunk.source.slide,
            slide_end=chunk.source.slide,
            source_text=source_text,
        )

    def _build(self, chunk: DocumentChunk, *, title: str, chunk_type: Literal["section", "event", "narrative", "table", "chart"], content: str, subject: str | None = None, metadata: dict | None = None, source_text: str | None = None) -> SemanticChunk:
        clean_content = content.strip()
        indexable = chunk_type != "section" and (chunk_type in {"table", "chart"} or len(clean_content) >= self.MIN_INDEXABLE_CHARS)
        # An explicit empty source means a parser could not prove table
        # provenance. Do not silently replace it with reconstructed content.
        effective_source_text = clean_content if source_text is None else source_text.strip()
        return SemanticChunk(
            title=title,
            chunk_type=chunk_type,
            subject=subject,
            content=clean_content,
            source=self._source(chunk, effective_source_text),
            metadata={"char_count": len(clean_content), "file_type": chunk.metadata.get("file_type", "unknown"), "indexable": indexable, **(metadata or {})},
        )

    def _split_long(self, chunk: SemanticChunk) -> list[SemanticChunk]:
        if len(chunk.content) <= self.MAX_CHUNK_CHARS:
            return [chunk]
        parts = re.split(r"(?<=[。！？；])\s*|\n+", chunk.content)
        output: list[SemanticChunk] = []
        current: list[str] = []
        size = 0
        for part in (item.strip() for item in parts if item.strip()):
            if current and size + len(part) > self.MAX_CHUNK_CHARS:
                text = "\n".join(current)
                output.append(chunk.model_copy(update={"content": text, "source": chunk.source.model_copy(update={"source_text": text}), "metadata": {**chunk.metadata, "char_count": len(text)}}))
                current, size = [], 0
            current.append(part)
            size += len(part)
        if current:
            text = "\n".join(current)
            output.append(chunk.model_copy(update={"content": text, "source": chunk.source.model_copy(update={"source_text": text}), "metadata": {**chunk.metadata, "char_count": len(text)}}))
        return output

    def _event_or_narrative(
        self,
        chunk: DocumentChunk,
        lines: list[str],
        fallback_title: str,
        *,
        allow_event_split: bool = True,
    ) -> list[SemanticChunk]:
        if not lines:
            return []
        if self._is_section(lines, []):
            return [self._build(chunk, title=lines[0], chunk_type="section", content=lines[0])]

        # A slide containing a native table also exposes every table cell as
        # slide text.  Even after table-row removal, compact metric labels can
        # remain and look like event headings.  Preserve that surrounding text
        # as one narrative chunk; the table itself is emitted separately.
        starts = (
            [index for index in range(len(lines)) if self._is_event_start(lines, index)]
            if allow_event_split
            else []
        )
        if not starts:
            content = "\n".join(lines)
            return self._split_long(self._build(chunk, title=self._title(content, fallback_title), chunk_type="narrative", content=content, source_text=self._raw_fragment(chunk.content, lines)))

        output: list[SemanticChunk] = []
        for position, start in enumerate(starts):
            end = starts[position + 1] if position + 1 < len(starts) else len(lines)
            source_text = "\n".join(lines[start:end]).strip()
            if not source_text:
                continue
            title = lines[start + 1] if start + 1 < len(lines) else lines[start]
            event_chunk = self._build(
                chunk,
                title=title[:120],
                subject=self._subject(lines[start]),
                chunk_type="event",
                content=source_text,
                source_text=self._raw_fragment(chunk.content, lines[start:end]),
            )
            output.extend(self._split_long(event_chunk))
        return output

    def _raw_table_source(self, chunk: DocumentChunk, table: TableData, table_index: int) -> str:
        """Return only original DocumentChunk table text; never a reconstructed table."""
        # PDF tables carry text extracted from their exact bounding box. This
        # is stronger provenance than trying to rediscover table rows in the
        # full-page text and allows the citation UI to locate the right page.
        table_source = str(table.metadata.get("source_text") or "").strip()
        if table_source:
            return table_source
        table_texts = chunk.metadata.get("table_texts")
        if isinstance(table_texts, list) and len(table_texts) >= table_index:
            source_text = str(table_texts[table_index - 1] or "").strip()
            if source_text:
                return source_text

        # PDF/TXT chunks do not carry native-table metadata. Retain the exact
        # original substring only when all parsed headers and row values can
        # be located in it; otherwise source provenance is unavailable.
        raw_columns, normalized_rows, valid = self._normalized_table_rows(table)
        if not valid:
            return ""
        raw_lines = self._lines(chunk.content)
        expected = {" | ".join(table.columns)}
        expected.update(" | ".join(row.get(column, "") for column in raw_columns) for row in normalized_rows)
        matched = [line for line in raw_lines if line in expected]
        source_text = self._raw_fragment(chunk.content, matched) if matched else ""
        expected_values = [
            self._clean_table_text(value)
            for row in normalized_rows for value in row.values()
            if self._clean_table_text(value)
        ]
        return source_text if source_text and all(value in source_text for value in expected_values) else ""

    def _table_chunks(self, chunk: DocumentChunk, tables: list[TableData], title_candidates: list[str]) -> list[SemanticChunk]:
        output: list[SemanticChunk] = []
        for index, table in enumerate(tables, start=1):
            title = table.title
            if title.startswith("Slide ") or title.startswith("第"):
                candidate = next((item for item in title_candidates if re.search(r"TOP\d+", item, re.IGNORECASE) and "|" not in item), None)
                candidate = candidate or next((item for item in title_candidates if re.match(r"^\d+\.\d+", item) and "|" not in item), None)
                candidate = candidate or next((item for item in title_candidates if re.search(r"销量|整车厂|预测", item, re.IGNORECASE) and "|" not in item and not item.startswith("（同比")), None)
                title = candidate or f"表格 {index}"
                if candidate in title_candidates:
                    title_candidates.remove(candidate)
            rendered_table = table.model_copy(update={"title": title})
            columns, normalized_rows, table_valid = self._normalized_table_rows(table)
            source_text = self._raw_table_source(chunk, table, index)
            source_available = bool(source_text) and table_valid
            table_text = self._table_content(rendered_table) if source_available else f"表标题: {title}"
            row_count = len(normalized_rows) if source_available else 0
            columns = columns if source_available else []
            row_type = type(table.rows[0]).__name__ if getattr(table, "rows", None) else "none"
            logger.info(
                "[TABLE_DEBUG] table_index=%s columns=%s row_type=%s row_count=%s",
                index,
                columns,
                row_type,
                row_count,
            )
            logger.info(
                "[TABLE_CHUNK] file=%s page=%s slide=%s table_index=%s columns=%s rows=%s",
                chunk.source.file,
                chunk.source.page,
                chunk.source.slide,
                index,
                columns,
                row_count,
            )
            output.append(self._build(
                chunk,
                title=title,
                chunk_type="table",
                content=table_text,
                source_text=source_text,
                metadata={
                    "table_index": index,
                    "columns": columns,
                    "row_count": row_count,
                    "row_subjects": self._table_row_subjects(table) if source_available else [],
                    "table_metadata": table.metadata,
                    "source_available": source_available,
                    "indexable": source_available,
                },
            ))
        return output

    @staticmethod
    def _chart_content(chart: ChartKnowledge) -> str:
        """Render native chart data without adding visual or business inference."""
        lines = [f"图表标题: {chart.title}", f"图表类型: {chart.chart_type}"]
        if chart.categories:
            lines.append(f"分类: {' | '.join(chart.categories)}")
        lines.append("系列数据:")
        for series in chart.series:
            if series.points:
                points = [
                    f"x={point.x if point.x is not None else ''} | y={point.y if point.y is not None else ''} | 气泡大小={point.bubble_size if point.bubble_size is not None else ''}"
                    for point in series.points
                ]
                lines.append(f"{series.name}: {' ; '.join(points)}")
                continue
            values = ["" if value is None else str(value) for value in series.values]
            if chart.categories:
                points = [
                    f"{category}: {values[index] if index < len(values) else ''}"
                    for index, category in enumerate(chart.categories)
                ]
                if len(values) > len(chart.categories):
                    points.extend(f"分类{index + 1}: {value}" for index, value in enumerate(values[len(chart.categories):], start=len(chart.categories)))
                lines.append(f"{series.name}: {' | '.join(points)}")
            else:
                lines.append(f"{series.name}: {' | '.join(values)}")
        return "\n".join(lines).strip()

    def _chart_chunks(self, chunk: DocumentChunk, charts: list[ChartKnowledge]) -> list[SemanticChunk]:
        output: list[SemanticChunk] = []
        for chart in charts:
            content = self._chart_content(chart)
            chart_index = chart.metadata.get("chart_index")
            logger.info(
                "[CHART_CHUNK] file=%s slide=%s chart_index=%s type=%s categories=%s series=%s",
                chunk.source.file,
                chunk.source.slide,
                chart_index,
                chart.chart_type,
                len(chart.categories),
                len(chart.series),
            )
            output.append(
                self._build(
                    chunk,
                    title=chart.title,
                    chunk_type="chart",
                    content=content,
                    # This is programmatically rendered only from the native
                    # chart object, never produced by an LLM.
                    source_text=content,
                    metadata={
                        "chart_index": chart_index,
                        "chart_type": chart.chart_type,
                        "categories": chart.categories,
                        "series_names": [series.name for series in chart.series],
                        "indexable": bool(chart.series),
                    },
                )
            )
        return output

    def _without_table_rows(self, lines: list[str], tables: list[TableData]) -> list[str]:
        table_rows = {
            " | ".join(str(cell or "").strip() for cell in row.values()).strip()
            for table in tables for row in table.rows
        }
        headers = {" | ".join(table.columns).strip() for table in tables}
        return [line for line in lines if line not in table_rows and line not in headers]

    def _chunk_pptx(self, chunks: list[DocumentChunk], tables: list[TableData], charts: list[ChartKnowledge]) -> list[SemanticChunk]:
        tables_by_slide: dict[int, list[TableData]] = defaultdict(list)
        for table in tables:
            if table.source and table.source.slide is not None:
                tables_by_slide[table.source.slide].append(table)
        charts_by_slide: dict[int, list[ChartKnowledge]] = defaultdict(list)
        for chart in charts:
            if chart.source.slide is not None:
                charts_by_slide[chart.source.slide].append(chart)
        output: list[SemanticChunk] = []
        for chunk in chunks:
            slide_tables = tables_by_slide.get(chunk.source.slide or chunk.slide or -1, [])
            slide_charts = charts_by_slide.get(chunk.source.slide or chunk.slide or -1, [])
            lines = self._lines(chunk.content)
            text_lines = self._without_table_rows(lines, slide_tables)
            if self._is_section(text_lines, slide_tables) or self._is_toc(text_lines, slide_tables):
                content = "\n".join(text_lines)
                output.append(self._build(chunk, title=text_lines[0], chunk_type="section", content=content, source_text=self._raw_fragment(chunk.content, text_lines)))
                output.extend(self._chart_chunks(chunk, slide_charts))
                continue
            output.extend(
                self._event_or_narrative(
                    chunk,
                    text_lines,
                    str(chunk.metadata.get("title") or "行业文档"),
                    allow_event_split=not bool(slide_tables),
                )
            )
            output.extend(self._table_chunks(chunk, slide_tables, text_lines.copy()))
            output.extend(self._chart_chunks(chunk, slide_charts))
        return output

    def _chunk_pdf_or_txt(self, chunks: list[DocumentChunk], tables: list[TableData]) -> list[SemanticChunk]:
        tables_by_page: dict[int, list[TableData]] = defaultdict(list)
        for table in tables:
            if table.source and table.source.page is not None:
                tables_by_page[table.source.page].append(table)
        output: list[SemanticChunk] = []
        for chunk in chunks:
            page_tables = tables_by_page.get(chunk.source.page or chunk.page or -1, [])
            lines = self._without_table_rows(self._lines(chunk.content), page_tables)
            if self._is_section(lines, page_tables) or self._is_toc(lines, page_tables):
                content = "\n".join(lines)
                output.append(self._build(chunk, title=lines[0], chunk_type="section", content=content, source_text=self._raw_fragment(chunk.content, lines)))
            else:
                output.extend(self._event_or_narrative(chunk, lines, "汽车行业文档知识"))
            output.extend(self._table_chunks(chunk, page_tables, lines.copy()))
        return output

    def chunk(self, chunks: list[DocumentChunk], tables: list[TableData] | None = None, charts: list[ChartKnowledge] | None = None) -> list[SemanticChunk]:
        if not chunks:
            return []
        table_list = tables or []
        chart_list = charts or []
        if all(chunk.metadata.get("file_type") == "pptx" for chunk in chunks):
            return self._chunk_pptx(chunks, table_list, chart_list)
        return self._chunk_pdf_or_txt(chunks, table_list)
