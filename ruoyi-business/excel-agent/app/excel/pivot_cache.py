"""Read the embedded OOXML Pivot Cache used by the Shipment share sheet."""
from __future__ import annotations

import posixpath
from pathlib import PurePosixPath
import xml.etree.ElementTree as ET
import zipfile
from typing import Any


MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
REQUIRED_FIELDS = {
    "Maker", "Year", "Quarter", "Original specification", "Application",
    "Technology", "Size", "Shipments (000s)",
}
SUPPLY_CHAIN_REQUIRED_FIELDS = {
    "Year", "Quarter", "Panel maker", "Client", "Original specification",
    "Product", "Technology", "Qty (000)",
}


def extract_shipment_share_cache(file_path: str) -> dict[str, Any] | None:
    """Return fields and all cached records needed by Shipment share."""
    if not zipfile.is_zipfile(file_path):
        return None
    with zipfile.ZipFile(file_path) as archive:
        selected = _select_definition(archive, REQUIRED_FIELDS)
        if selected is None:
            return None
        definition_path, fields, shared_values = selected
        records_path = _records_path(archive, definition_path)
        if records_path is None:
            return None
        records = list(_read_records(archive, records_path, fields, shared_values))
        return {
            "sheet": "Shipment share",
            "table": "Shipment share Pivot Cache",
            "range": f"pivot-cache:{PurePosixPath(records_path).name}",
            "fields": fields,
            "records": records,
        }


def extract_supply_chain_cache(file_path: str) -> dict[str, Any] | None:
    """Return the raw records behind Panel maker to client pivot."""
    if not file_path or not zipfile.is_zipfile(file_path):
        return None
    with zipfile.ZipFile(file_path) as archive:
        selected = _select_definition(archive, SUPPLY_CHAIN_REQUIRED_FIELDS)
        if selected is None:
            return None
        definition_path, fields, shared_values = selected
        records_path = _records_path(archive, definition_path)
        if records_path is None:
            return None
        records = list(_read_records(archive, records_path, fields, shared_values))
        return {
            "sheet": "Panel maker to client pivot",
            "table": "Panel maker to client Pivot Cache",
            "range": f"pivot-cache:{PurePosixPath(records_path).name}",
            "fields": fields,
            "records": records,
        }


def _select_definition(archive: zipfile.ZipFile, required_fields):
    for name in sorted(item for item in archive.namelist() if item.startswith("xl/pivotCache/pivotCacheDefinition") and item.endswith(".xml")):
        root = ET.fromstring(archive.read(name))
        cache_fields = root.find(f"{{{MAIN_NS}}}cacheFields")
        if cache_fields is None:
            continue
        fields = [item.attrib.get("name", "") for item in cache_fields]
        if not required_fields.issubset(fields):
            continue
        shared = [_shared_values(item) for item in cache_fields]
        return name, fields, shared
    return None


def _shared_values(cache_field) -> list[Any]:
    container = cache_field.find(f"{{{MAIN_NS}}}sharedItems")
    return [] if container is None else [_xml_value(item) for item in container]


def _records_path(archive: zipfile.ZipFile, definition_path: str) -> str | None:
    definition = PurePosixPath(definition_path)
    rel_path = str(definition.parent / "_rels" / f"{definition.name}.rels")
    if rel_path not in archive.namelist():
        return None
    root = ET.fromstring(archive.read(rel_path))
    for relationship in root.findall(f"{{{REL_NS}}}Relationship"):
        if relationship.attrib.get("Type", "").endswith("/pivotCacheRecords"):
            return posixpath.normpath(posixpath.join(str(definition.parent), relationship.attrib["Target"]))
    return None


def _read_records(archive, records_path, fields, shared_values):
    with archive.open(records_path) as stream:
        for _, element in ET.iterparse(stream, events=("end",)):
            if _local_name(element.tag) != "r":
                continue
            row = {}
            for index, item in enumerate(element):
                if index >= len(fields):
                    break
                if _local_name(item.tag) == "x":
                    shared_index = int(item.attrib.get("v", "-1"))
                    value = shared_values[index][shared_index] if 0 <= shared_index < len(shared_values[index]) else None
                else:
                    value = _xml_value(item)
                row[fields[index]] = value
            yield row
            element.clear()


def _xml_value(element):
    kind = _local_name(element.tag)
    value = element.attrib.get("v")
    if kind == "m":
        return None
    if kind == "n":
        try:
            number = float(value)
            return int(number) if number.is_integer() else number
        except (TypeError, ValueError):
            return None
    if kind == "b":
        return value == "1"
    return value


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]
