"""전체판을 배포할 때 그 기준을 저장해 두기 — 나중에 패치(차이만)를 만들 때 --old 로 씀.

    python tools/snapshot.py 3.2          → release/3.2/ (features.json, blobs_len, version, ui/, img/)
"""
import os
import shutil
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ver = sys.argv[1]
pack = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, 'private')
dst = os.path.join(HERE, 'release', ver)
shutil.rmtree(dst, ignore_errors=True)
os.makedirs(dst)
shutil.copy(os.path.join(pack, 'features.json'), dst)
open(os.path.join(dst, 'blobs_len'), 'w').write(str(os.path.getsize(os.path.join(pack, 'blobs.bin'))))
open(os.path.join(dst, 'version'), 'w').write(ver)
shutil.copytree(os.path.join(HERE, 'ui'), os.path.join(dst, 'ui'))
shutil.copytree(os.path.join(pack, 'img'), os.path.join(dst, 'img'))
print('스냅숏:', dst)
