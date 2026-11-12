#!/usr/bin/env python3
"""Audit ELF LOAD segment and stored ZIP entry alignment; does not prove runtime correctness."""
import argparse
import json
import struct
import zipfile


def elf_load_alignments(data):
    if data[:6] != b'\x7fELF\x02\x01':
        raise ValueError('Expected a little-endian ELF64 library')
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    result = []
    for index in range(count):
        kind, flags, file_offset, virtual, physical, file_size, memory_size, alignment = struct.unpack_from('<IIQQQQQQ', data, offset + index * size)
        if kind == 1:
            result.append(dict(alignment=alignment, congruent16k=(file_offset % 16384 == virtual % 16384)))
    if not result:
        raise ValueError('No LOAD segments')
    return result


def audit(apk):
    report = []
    with zipfile.ZipFile(apk) as archive, open(apk, 'rb') as raw:
        for entry in archive.infolist():
            if not entry.filename.startswith('lib/arm64-v8a/') or not entry.filename.endswith('.so'):
                continue
            loads = elf_load_alignments(archive.read(entry))
            raw.seek(entry.header_offset)
            header = raw.read(30)
            name_length, extra_length = struct.unpack_from('<HH', header, 26)
            data_offset = entry.header_offset + 30 + name_length + extra_length
            zipped_ok = entry.compress_type != zipfile.ZIP_STORED or data_offset % 16384 == 0
            elf_ok = all(load['alignment'] >= 16384 and load['congruent16k'] for load in loads)
            report.append(dict(library=entry.filename, loadSegments=loads, zipAligned16k=zipped_ok,
                               elfAligned16k=elf_ok, compatibleAlignment=elf_ok and zipped_ok))
    return dict(apk=str(apk), libraries=report, allAligned16k=bool(report) and all(item['compatibleAlignment'] for item in report))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    args = parser.parse_args()
    result = audit(args.apk)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['allAligned16k'] else 1)
