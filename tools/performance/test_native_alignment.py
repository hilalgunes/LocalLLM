import struct
import unittest
from check_native_alignment import elf_load_alignments

class AlignmentTests(unittest.TestCase):
    def elf(self, alignment, offset=0, virtual=0):
        data = bytearray(120)
        data[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<Q', data, 32, 64)
        struct.pack_into('<HH', data, 54, 56, 1)
        struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, offset, virtual, 0, 1, 1, alignment)
        return data
    def test_reads_load_alignment(self):
        self.assertEqual(elf_load_alignments(self.elf(4096))[0]['alignment'], 4096)
        self.assertEqual(elf_load_alignments(self.elf(16384))[0]['alignment'], 16384)
    def test_detects_noncongruent_addresses(self):
        self.assertFalse(elf_load_alignments(self.elf(16384, 4096, 0))[0]['congruent16k'])
    def test_rejects_non_elf(self):
        with self.assertRaises(ValueError): elf_load_alignments(b'bad')

if __name__ == '__main__': unittest.main()
