import os
import sys
import tempfile
import unittest
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.excel.pivot_cache import extract_shipment_share_cache


class PivotCacheTest(unittest.TestCase):
    def test_extracts_shared_and_numeric_values_from_required_cache(self):
        namespace = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        relationship_namespace = "http://schemas.openxmlformats.org/package/2006/relationships"
        fields = [
            ("Maker", '<sharedItems><s v="Tianma"/></sharedItems>'),
            ("Year", ""), ("Quarter", '<sharedItems><s v="1Q"/></sharedItems>'),
            ("Original specification", '<sharedItems><s v="Automobile monitor"/></sharedItems>'),
            ("Application", '<sharedItems><s v="Control panel"/></sharedItems>'),
            ("Technology", '<sharedItems><s v="LTPS"/></sharedItems>'),
            ("Size", ""), ("Shipments (000s)", ""),
        ]
        definition = (
            f'<pivotCacheDefinition xmlns="{namespace}" recordCount="1"><cacheFields count="8">'
            + "".join(f'<cacheField name="{name}">{shared}</cacheField>' for name, shared in fields)
            + "</cacheFields></pivotCacheDefinition>"
        )
        records = (
            f'<pivotCacheRecords xmlns="{namespace}" count="1"><r>'
            '<x v="0"/><n v="2025"/><x v="0"/><x v="0"/><x v="0"/>'
            '<x v="0"/><n v="7.9"/><n v="120"/></r></pivotCacheRecords>'
        )
        relationships = (
            f'<Relationships xmlns="{relationship_namespace}">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/pivotCacheRecords" '
            'Target="pivotCacheRecords1.xml"/></Relationships>'
        )
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "pivot.xlsx")
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("xl/pivotCache/pivotCacheDefinition1.xml", definition)
                archive.writestr("xl/pivotCache/_rels/pivotCacheDefinition1.xml.rels", relationships)
                archive.writestr("xl/pivotCache/pivotCacheRecords1.xml", records)
            result = extract_shipment_share_cache(path)

        self.assertIsNotNone(result)
        self.assertEqual(1, len(result["records"]))
        self.assertEqual("Tianma", result["records"][0]["Maker"])
        self.assertEqual(7.9, result["records"][0]["Size"])
        self.assertEqual(120, result["records"][0]["Shipments (000s)"])


if __name__ == "__main__":
    unittest.main()
