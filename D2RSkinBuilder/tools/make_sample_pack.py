"""예제 기능 묶음(pack/sample) 만들기 — 공개 저장소에서도 빌드·실행해 볼 수 있게.

실제 기능 묶음(private/features.json)에서 '다른 제작자의 파일을 쓰지 않는' 기능만 골라 옮겨요.
(파일을 통째로 넣는 연산 f/n 이 있는 기능, farming 화면 글을 쓰는 기능은 빼요)

    python tools/make_sample_pack.py private/features.json
"""
import json
import os
import shutil
import sys

KEEP = ['monster_mods', 'lightning_glow', 'exit_band', 'title_text']
# 파일 연산만 빼고 고치기 연산은 남길 기능 (세계석 성채 방 파일은 빠지고 출구 방 고치기만 남음)
STRIP_FILES = ['exit_band']
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def file_free(ops):
    for op in ops:
        if isinstance(op, list):
            return False
        if op.get('b') is not None or not file_free(op.get('else', [])):
            return False
    return True


def main(src):
    pack = json.load(open(src, encoding='utf-8'))
    feats = []
    for f in pack['features']:
        if f['id'] not in KEEP:
            continue
        f = dict(f)
        if f['id'] in STRIP_FILES:
            # 세계석 성채 방(baallair)에 넣는 표시는 기준 스킨(yupgoolg) 출구 표시를 옮겨 온 것이라 뺌
            f['options'] = [dict(o, ops=[op for op in o['ops'] if isinstance(op, dict) and file_free([op])
                                         and '\\baallair\\' not in op.get('p', '')]) for o in f['options']]
            f['easy'] = [r for r in f['easy'] if r[0] != '세계석 성채']
        f['options'] = [o for o in f['options'] if o['ops'] and file_free(o['ops'])]
        for k in ('shots', 'sounds', 'fonts'):
            f.pop(k, None)
        if f['options']:
            feats.append(f)
    groups = []
    for f in feats:
        if f['group'] not in groups:
            groups.append(f['group'])
    preset = {'name': '예제 추천', 'choices': {f['id']: f['options'][0]['key'] for f in feats}, 'params': {}}
    out = {'version': 2, 'groups': groups, 'features': feats, 'blobs': [], 'sha': [], 'presets': [preset]}
    dst = os.path.join(HERE, 'pack', 'sample')
    os.makedirs(os.path.join(dst, 'img'), exist_ok=True)
    json.dump(out, open(os.path.join(dst, 'features.json'), 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
    open(os.path.join(dst, 'blobs.bin'), 'wb').close()
    shutil.copy(os.path.join(HERE, 'ui', 'icon.png'), os.path.join(dst, 'img', 'icon.png'))
    print('예제 기능', len(feats), '개:', ', '.join(f['id'] for f in feats))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, 'private', 'features.json'))
