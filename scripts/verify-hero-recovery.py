#!/usr/bin/env python3
"""Verify an exported recovery archive on the Mac without printing private contents."""
import argparse
import hashlib
import json
from pathlib import PurePosixPath
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('archive')
args = parser.parse_args()
with zipfile.ZipFile(args.archive) as archive:
    names = archive.namelist()
    if len(names) != len(set(names)): raise SystemExit('Duplicate ZIP entries')
    if any(PurePosixPath(n).is_absolute() or '..' in PurePosixPath(n).parts for n in names):
        raise SystemExit('Unsafe ZIP path')
    manifest = json.loads(archive.read('recovery/manifest.json'))
    if manifest.get('format') != 'mascot-recovery-v1': raise SystemExit('Unknown archive format')
    checksums = manifest['sha256']
    if set(names) != set(checksums) | {'recovery/manifest.json'}: raise SystemExit('Archive inventory mismatch')
    for name, expected in checksums.items():
        digest = hashlib.sha256()
        with archive.open(name) as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''): digest.update(chunk)
        if digest.hexdigest() != expected: raise SystemExit('Hash mismatch: ' + name)
    for hero in manifest['heroes']:
        for file in ('base.png', 'name.txt'):
            if f'recovery/hero/{hero}/{file}' not in checksums and file == 'base.png':
                raise SystemExit(f'Missing required hero file: {hero}/{file}')
    print(json.dumps({'status': 'verified', 'heroes': len(manifest['heroes']), 'files': len(checksums),
                      'missing_images': len(manifest.get('missingImages', [])),
                      'chat_animations': sum('/chat-animations/' in n and n.endswith(('.mp4','.webm')) for n in names),
                      'video_map_files': sum(n.startswith('recovery/lighting/video/') for n in names)}, ensure_ascii=False))
