"""Check imported car models for texture indices Filament cannot load."""
import json
from pathlib import Path
import struct
import unittest


class ModelCompatibilityTest(unittest.TestCase):
    def test_texture_coordinate_indices_are_non_negative(self):
        models = Path(__file__).resolve().parents[2] / 'app/src/main/assets/models'
        paths = list(models.glob('*.glb'))
        self.assertTrue(paths, 'No car models found')

        def check(value, path):
            if isinstance(value, dict):
                if 'texCoord' in value:
                    self.assertGreaterEqual(value['texCoord'], 0, str(path))
                for child in value.values():
                    check(child, path)
            elif isinstance(value, list):
                for child in value:
                    check(child, path)

        for path in paths:
            with self.subTest(model=path.name), path.open('rb') as f:
                header = f.read(20)
                magic, version, _, size, chunk_type = struct.unpack('<IIIII', header)
                self.assertEqual((magic, version, chunk_type), (0x46546C67, 2, 0x4E4F534A))
                doc = json.loads(f.read(size))
                check(doc.get('materials', []), path)


if __name__ == '__main__':
    unittest.main()
