#!/usr/bin/env python3
"""스킨 빌더 패치 파일 만들기
  python3 tools/make_patch.py --old release/3.2 --version 3.3 [--exe dist/slim.exe] [--notes "..."] -o 스킨빌더_패치_3.3.sbpatch
--old  : 사용자가 가진 전체판(기준)을 만들 때 tools/snapshot.py 로 저장해 둔 스냅숏 폴더 (features.json, blobs_len, version, ui/, img/)
--pack : 새 기능 묶음 폴더 (기본 private/ — features.json, blobs.bin, img/)
기준에 같은 데이터(sha)가 있으면 다시 넣지 않고, 새 데이터만 extra.bin 에.
만든 패치는 SB_EMBED_PATCH=패치파일 로 mpq 를 만들면 mpq 안에도 들어가요 (mpq 로 업그레이드).
"""
import argparse, json, os, zipfile, hashlib

ap = argparse.ArgumentParser()
ap.add_argument('--old', required=True)
ap.add_argument('--version', required=True)
ap.add_argument('--exe')
ap.add_argument('--notes', default='')
ap.add_argument('-o', required=True)
ap.add_argument('--root', default=os.path.join(os.path.dirname(__file__), '..'))
ap.add_argument('--pack', default=None)
a = ap.parse_args()
R = a.root
PK = a.pack or os.path.join(R, 'private')
old = json.load(open(os.path.join(a.old, 'features.json'), encoding='utf-8'))
base_ver = open(os.path.join(a.old, 'version')).read().strip()
base_len = int(open(os.path.join(a.old, 'blobs_len')).read())
new = json.load(open(os.path.join(PK, 'features.json'), encoding='utf-8'))
nb = open(os.path.join(PK, 'blobs.bin'), 'rb').read()

oldmap = {}
for (off, ln, us), sha in zip(old['blobs'], old['sha']):
    oldmap.setdefault(sha, [off, ln, us])
extra = bytearray(); blobs = []; reused = 0; extra_idx = {}
for (off, ln, us), sha in zip(new['blobs'], new['sha']):
    if sha in oldmap:
        blobs.append(oldmap[sha]); reused += 1
    elif sha in extra_idx:
        blobs.append(extra_idx[sha])
    else:
        e = [base_len + len(extra), ln, us]
        extra += nb[off:off + ln]
        extra_idx[sha] = e
        blobs.append(e)
new['blobs'] = blobs

def tree(d):
    out = {}
    for dp, _, fs in os.walk(d):
        for f in fs:
            p = os.path.join(dp, f)
            out[os.path.relpath(p, d).replace(os.sep, '/')] = hashlib.sha1(open(p, 'rb').read()).hexdigest()
    return out

with zipfile.ZipFile(a.o, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('manifest.json', json.dumps({'version': a.version, 'base': base_ver, 'notes': a.notes}, ensure_ascii=False))
    z.writestr('features.json', json.dumps(new, ensure_ascii=False, separators=(',', ':')))
    if extra:
        z.writestr('extra.bin', bytes(extra), compress_type=zipfile.ZIP_STORED)
    nui = 0
    for sub, src in (('ui', os.path.join(R, 'ui')), ('img', os.path.join(PK, 'img'))):
        ot = tree(os.path.join(a.old, sub)) if os.path.isdir(os.path.join(a.old, sub)) else {}
        for rel, h in tree(src).items():
            if ot.get(rel) != h:
                z.write(os.path.join(src, rel), sub + '/' + rel); nui += 1
    if a.exe:
        z.write(a.exe, 'program.exe')
print('patch', a.version, 'base', base_ver, 'blobs reused', reused, 'new', len(extra_idx), 'extra MB', round(len(extra) / 1e6, 2),
      'ui/img files', nui, 'exe', bool(a.exe), 'size MB', round(os.path.getsize(a.o) / 1e6, 2))

