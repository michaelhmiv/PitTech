"""Read actual native OOXML with an independent standard-library reader in Actions."""
import csv
import io
import unittest
import zipfile
from collections import defaultdict
from decimal import Decimal
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
X = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'
R = '{http://schemas.openxmlformats.org/officeDocument/2006/relationships}'
P = '{http://schemas.openxmlformats.org/package/2006/relationships}'


def read_workbook(path):
    with zipfile.ZipFile(path) as archive:
        assert len(archive.namelist()) == len(set(archive.namelist()))
        relations = {node.attrib['Id']: node.attrib['Target'] for node in ET.fromstring(archive.read('xl/_rels/workbook.xml.rels')).findall(P + 'Relationship')}
        result = {}
        for sheet in ET.fromstring(archive.read('xl/workbook.xml')).find(X + 'sheets'):
            target = relations[sheet.attrib[R + 'id']]
            assert not target.startswith('/') and '..' not in target.split('/')
            root = ET.fromstring(archive.read('xl/' + target))
            assert root.find(X + 'autoFilter') is not None
            assert root.find(X + 'sheetViews') is not None
            cells = {}
            for cell in root.iter(X + 'c'):
                assert cell.find(X + 'f') is None, 'User text must not become formulas'
                if cell.attrib.get('t') == 'inlineStr':
                    value = ''.join(part.text or '' for part in cell.iter(X + 't'))
                    assert len(value.encode('utf-16-le')) // 2 <= 32767
                else:
                    value = Decimal(cell.findtext(X + 'v'))
                cells[cell.attrib['r']] = value
            result[sheet.attrib['name']] = cells
        return result


class NativeWorkbookFixtureTests(unittest.TestCase):
    def test_actual_native_numeric_celsius_matches_csv_without_losing_precision(self):
        path = ROOT / 'contracts/fixtures/tabular/native-v1.xlsx'
        workbook = read_workbook(path)
        self.assertEqual(len(workbook), 19)
        with (path.parent / 'native-v1.csv').open(newline='', encoding='utf-8') as source:
            rows = list(csv.DictReader(source))
        self.assertEqual(len(rows), 1)
        self.assertEqual(workbook['Readings']['F2'], Decimal(rows[0]['value']))
        self.assertEqual(workbook['Readings']['F2'], Decimal('74.125'))
        self.assertEqual(workbook['Readings']['G2'], rows[0]['unit'])
        self.assertEqual(rows[0]['unit'], '°C')
        self.assertEqual(workbook['Readings']['H2'], rows[0]['measured_at_utc'])
        self.assertEqual(workbook['Readings']['J2'], rows[0]['time_zone'])
        self.assertEqual(workbook['Readings']['N2'], Decimal(rows[0]['sampling_interval_millis']))

    def test_actual_native_long_values_reconstruct_unicode_and_keep_photo_relationships(self):
        workbook = read_workbook(ROOT / 'contracts/fixtures/tabular/native-v1.xlsx')
        chunks = workbook['Long Values']; grouped = defaultdict(list)
        for row in range(2, 4):
            grouped[(chunks[f'A{row}'], chunks[f'B{row}'])].append((chunks[f'C{row}'], chunks[f'D{row}']))
        self.assertEqual(set(grouped), {('Cook Summary', 'S2')})
        parts = grouped[('Cook Summary', 'S2')]
        self.assertEqual(''.join(text for _, text in sorted(parts)), '🌶' * 17000)
        self.assertIn('Long Values', workbook['Cook Summary']['S2'])
        self.assertEqual(workbook['Photos']['B2'], workbook['Cook Summary']['A2'])
        self.assertIsInstance(workbook['Derived Metrics']['D2'], Decimal)
        self.assertEqual(workbook['Derived Metrics']['I2'], 'PitTech summary v2')

    def test_actual_android_numeric_celsius_matches_csv_and_preserves_relationships(self):
        path = ROOT / 'contracts/fixtures/tabular/android-v1.xlsx'
        workbook = read_workbook(path)
        self.assertEqual(len(workbook), 19)
        with (path.parent / 'android-v1.csv').open(newline='', encoding='utf-8') as source:
            rows = list(csv.DictReader(source))
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertEqual(workbook['Readings']['A2'], row['reading_id'])
        self.assertEqual(workbook['Readings']['B2'], row['cook_id'])
        self.assertEqual(workbook['Readings']['C2'], row['dish_id'])
        self.assertEqual(workbook['Readings']['F2'], Decimal(row['value']))
        self.assertEqual(workbook['Readings']['F2'], Decimal('85'))
        self.assertEqual(workbook['Readings']['G2'], row['unit'])
        self.assertEqual(row['unit'], '°C')
        self.assertEqual(workbook['Readings']['H2'], row['measured_at_utc'])
        self.assertEqual(workbook['Readings']['J2'], row['time_zone'])
        self.assertEqual(workbook['Readings']['N2'], Decimal(row['sampling_interval_millis']))
        self.assertEqual(workbook['Photos']['B2'], row['cook_id'])
        self.assertEqual(workbook['Photos']['C2'], row['dish_id'])
        self.assertEqual(workbook['Derived Metrics']['I2'], 'PitTech summary v2')

    def test_actual_android_long_values_keep_every_unicode_character(self):
        workbook = read_workbook(ROOT / 'contracts/fixtures/tabular/android-v1.xlsx')
        chunks = workbook['Long Values']
        self.assertEqual([(chunks[f'A{r}'], chunks[f'B{r}']) for r in (2, 3)], [('Cook Summary', 'S3')] * 2)
        self.assertEqual([chunks[f'C{r}'] for r in (2, 3)], [Decimal(1), Decimal(2)])
        self.assertEqual(chunks['D2'] + chunks['D3'], '🌶' * 17000)
        self.assertIn('Long Values', workbook['Cook Summary']['S3'])


if __name__ == '__main__':
    unittest.main()
