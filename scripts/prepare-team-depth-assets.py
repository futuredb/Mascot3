#!/usr/bin/env python3
"""Stage a three-hero asset set without changing the application's original assets.

The generated Gradle init script switches only :app main assets and build output
to a new, unique directory. No input, existing backup or app data is overwritten.
"""
import argparse
import hashlib
import json
import math
import re
import shutil
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
ORIGINAL = ROOT / 'android/app/src/main/assets'
HEROES = {
    '716cdb43-703b-4d7e-8a79-27f03d92b99c': 'Уна',
    'cfbb8855-b0ab-4bdb-a4cc-d2360e16f174': 'Милош',
    'f9cac344-a786-466e-823c-a0951743d538': 'Леонко',
}
HEX = re.compile(r'^[a-f0-9]{64}$')
STYLES = {'neutral', 'direct', 'friendly', 'concise', 'expert', 'socratic', 'strategic',
          'mentor', 'skeptic', 'humorous', 'executive', 'intellectual', 'request_solutions',
          'proactive_solutions', 'request_guidance', 'proactive_guidance'}
INTERACTIONS = {'request_solutions', 'proactive_solutions', 'request_guidance', 'proactive_guidance'}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def video_hash(file):
    digest = hashlib.sha256()
    with file.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def valid_image_map(root, base):
    try:
        active = (root / 'active').read_text().strip()
        if not HEX.fullmatch(active): return False
        manifest_bytes = (root / active / 'manifest.json').read_bytes()
        manifest = json.loads(manifest_bytes)
        if sha(manifest_bytes) != active or manifest['source_sha256'] != video_hash(base): return False
        return all((root / active / v['file']).is_file()
                   and (root / active / v['file']).stat().st_size == v['size']
                   and video_hash(root / active / v['file']) == v['sha256']
                   for v in manifest['assets'].values())
    except (OSError, ValueError, KeyError, TypeError):
        return False


def valid_video_map(folder, source, digest):
    try:
        manifest = json.loads((folder / 'manifest.json').read_text())
        if manifest['source_sha256'] != digest or manifest['source_bytes'] != source.stat().st_size: return False
        pages = manifest['pages']; hashes = manifest['page_sha256']
        if not pages or len(pages) != len(hashes): return False
        return all(re.fullmatch(r'normals-\d{3}\.png', name) and
                   video_hash(folder / name) == expected for name, expected in zip(pages, hashes))
    except (OSError, ValueError, KeyError, TypeError):
        return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--recovery', type=Path, help='Private recovery ZIP; only selected hero settings are read')
    parser.add_argument('--only', choices=['una'], help='Include only the selected bundled hero')
    args = parser.parse_args()
    if args.only == 'una':
        global HEROES
        HEROES = {'716cdb43-703b-4d7e-8a79-27f03d92b99c': 'Уна'}
    archive = args.archive.resolve(strict=True)
    saved_settings = {}
    if args.recovery:
        with zipfile.ZipFile(args.recovery.resolve(strict=True)) as recovery:
            manifest = json.loads(recovery.read('recovery/manifest.json'))
            if manifest.get('format') != 'mascot-recovery-v1': raise SystemExit('Unsupported recovery archive')
            saved_settings = manifest.get('settings', {})
    parent = ROOT / 'output/team-depth-stage'
    parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix='three-heroes-', dir=parent))
    assets = stage / 'assets'
    assets.mkdir()
    for item in ORIGINAL.iterdir():
        if item.name in {'bundled-heroes', 'video-lighting'}: continue
        if item.is_dir(): shutil.copytree(item, assets / item.name)
        elif item.is_file(): shutil.copy2(item, assets / item.name)
    with zipfile.ZipFile(archive) as src:
        for info in src.infolist():
            name = PurePosixPath(info.filename)
            if name.is_absolute() or '..' in name.parts or not name.parts or info.is_dir(): continue
            if name.parts[0] == 'bundled-heroes':
                if len(name.parts) < 3 or name.parts[1] not in HEROES: continue
            elif name.parts[0] == 'video-lighting':
                if len(name.parts) != 3 or not HEX.fullmatch(name.parts[1]): continue
                if name.parts[2] != 'manifest.json' and not re.fullmatch(r'normals-\d{3}\.png', name.parts[2]): continue
            else: continue
            target = stage / 'archive-extract' / Path(*name.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with src.open(info) as source, target.open('xb') as output: shutil.copyfileobj(source, output)

    inventory = {'archive': str(archive), 'heroes': []}
    for hero_id, display_name in HEROES.items():
        source = stage / 'archive-extract/bundled-heroes' / hero_id
        if not (source / 'base.png').is_file(): raise SystemExit(f'Missing base.png for {display_name}; stage left at {stage}')
        destination = assets / 'bundled-heroes' / hero_id
        shutil.copytree(source, destination)
        # Only non-private, validated settings for the three selected heroes are
        # copied; photos, chat history and absolute file paths never enter APK.
        saved = saved_settings.get(hero_id, {})
        choice = saved.get('choice', {})
        clean = {}
        if choice.get('type') in {'person', 'creature', 'animal'} and choice.get('style') in STYLES:
            clean['choice'] = {'type': choice['type'], 'style': choice['style']}
            if all(isinstance(choice.get(key), (float, int)) and not isinstance(choice[key], bool)
                   and math.isfinite(choice[key]) and 0 <= choice[key] <= 1 for key in ('x', 'y')):
                clean['choice'].update({key: choice[key] for key in ('x', 'y')})
        if saved.get('interaction') in INTERACTIONS: clean['interaction'] = saved['interaction']
        if clean:
            with (destination / 'team-settings.json').open('x') as output:
                json.dump(clean, output, ensure_ascii=False, indent=2)
        physics = destination / 'physics'
        valid = valid_image_map(physics, destination / 'base.png')
        # Keep archive map as-is even if invalid, but report it; never replace it
        # silently. The final APK must not be claimed as complete if validation fails.
        if not valid and not physics.exists():
            fallback = ORIGINAL / 'bundled-heroes' / hero_id / 'physics'
            if valid_image_map(fallback, destination / 'base.png'):
                shutil.copytree(fallback, physics)
                valid = True
        entry = {'name': display_name, 'id': hero_id, 'static_depth_ready': valid,
                 'choice_included': 'choice' in clean, 'interaction_included': 'interaction' in clean, 'videos': []}
        videos = sorted((destination / 'videos').glob('*.mp4'))
        if (destination / 'performance.mp4').is_file(): videos.append(destination / 'performance.mp4')
        for video in videos:
            digest = video_hash(video)
            candidates = [stage / 'archive-extract/video-lighting' / digest, ORIGINAL / 'video-lighting' / digest]
            source_map = next((candidate for candidate in candidates if valid_video_map(candidate, video, digest)), None)
            if source_map:
                target = assets / 'video-lighting' / digest
                if not target.exists(): shutil.copytree(source_map, target)
            entry['videos'].append({'file': str(video.relative_to(destination)), 'sha256': digest,
                                    'lighting_ready': source_map is not None})
        inventory['heroes'].append(entry)
    inventory['missing_static'] = sum(not h['static_depth_ready'] for h in inventory['heroes'])
    inventory['missing_video'] = sum(not v['lighting_ready'] for h in inventory['heroes'] for v in h['videos'])
    (stage / 'inventory.json').write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + '\n')
    # Applied when AGP is first attached, before variants consume asset dirs.
    init = """gradle.beforeProject { project ->
    if (project.path == ':app') {
        project.layout.buildDirectory.set(new File(%s))
        project.plugins.withId('com.android.application') {
            project.extensions.getByName('android').sourceSets.getByName('main').assets.setSrcDirs([new File(%s)])
        }
    }
}
""" % (json.dumps(str(stage / 'build')), json.dumps(str(assets)))
    (stage / 'team-assets-init.gradle').write_text(init)
    print(json.dumps({'stage': str(stage), 'init': str(stage / 'team-assets-init.gradle'),
                      'inventory': str(stage / 'inventory.json'), 'missing_static': inventory['missing_static'],
                      'missing_video': inventory['missing_video']}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
