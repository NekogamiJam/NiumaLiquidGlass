"""Verify every source-kit entry before optional fresh-directory extraction. No build/device access."""
from pathlib import Path, PurePosixPath, PureWindowsPath
import argparse
import hashlib
import json
import stat
import zipfile

MANIFEST = 'SOURCE-KIT-MANIFEST.sha256.json'

def verify(archive: Path, extract_to: Path | None = None) -> dict:
    with zipfile.ZipFile(archive) as z:
        infos = z.infolist()
        names = [i.filename for i in infos]
        if len(set(names)) != len(names): raise ValueError('Duplicate ZIP member')
        for info in infos:
            name = info.filename
            p = PurePosixPath(name)
            if (p.is_absolute() or PureWindowsPath(name).drive or '\\' in name or
                any(x in ('..', '') or ':' in x for x in p.parts) or stat.S_ISLNK(info.external_attr >> 16)):
                raise ValueError(f'Unsafe archive member: {name}')
        if z.testzip() is not None: raise ValueError('ZIP CRC check failed')
        doc = json.loads(z.read(MANIFEST))
        if doc.get('schemaVersion') != 1 or doc.get('hashAlgorithm') != 'SHA-256': raise ValueError('Unknown source manifest')
        rows = doc['files']
        paths = [r['path'] for r in rows]
        if len(set(paths)) != len(paths) or doc['fileCount'] != len(rows): raise ValueError('Manifest count or duplicate mismatch')
        if set(names) != set(paths) | {MANIFEST}: raise ValueError('ZIP members differ from manifest')
        for row in rows:
            content = z.read(row['path'])
            if len(content) != row['bytes'] or hashlib.sha256(content).hexdigest() != row['sha256']:
                raise ValueError(f'Archived bytes do not match SHA-256 manifest: {row["path"]}')
        if extract_to is not None:
            target = extract_to.resolve()
            if target.exists(): raise FileExistsError('Extraction destination must be entirely new; no replacement or deletion')
            target.mkdir(parents=True)
            for info in infos:
                dest = target.joinpath(*PurePosixPath(info.filename).parts)
                dest.parent.mkdir(parents=True, exist_ok=True)
                with dest.open('xb') as stream: stream.write(z.read(info))
    return {'archive': str(archive.resolve()), 'archive_sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
            'files_verified': len(rows), 'crc': 'passed', 'all_sha256': 'passed',
            'extracted_to': str(extract_to.resolve()) if extract_to is not None else None}

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('archive', type=Path)
    p.add_argument('--extract-to', type=Path)
    args = p.parse_args()
    print(json.dumps(verify(args.archive, args.extract_to), ensure_ascii=False, indent=2))
