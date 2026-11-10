"""exe 아이콘 리소스 만들기 — ui/icon.png 로 rsrc_windows_amd64.syso 를 만들어요.

Go 는 같은 폴더의 .syso(COFF 오브젝트)를 윈도우 빌드에 자동으로 넣어요. 외부 도구 없이 직접 씀:
.rsrc 섹션 = 리소스 디렉터리(RT_ICON · RT_GROUP_ICON) + 아이콘 그림(PNG) + 주소 고침(ADDR32NB).

    python tools/make_icon_syso.py
"""
import io
import os
import struct

from PIL import Image

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIZES = [64, 48, 32, 24, 16]
RT_ICON, RT_GROUP_ICON, LANG = 3, 14, 0x0409


def main():
    src = Image.open(os.path.join(HERE, 'ui', 'icon.png')).convert('RGBA')
    pngs = []
    for s in SIZES:
        b = io.BytesIO()
        src.resize((s, s), Image.LANCZOS).save(b, 'PNG')
        pngs.append(b.getvalue())
    grp = struct.pack('<HHH', 0, 1, len(pngs))
    for i, (s, p) in enumerate(zip(SIZES, pngs)):
        grp += struct.pack('<BBBBHHIH', s % 256, s % 256, 0, 0, 1, 32, len(p), i + 1)

    # 리소스: (종류, id, 데이터)
    res = [(RT_ICON, i + 1, p) for i, p in enumerate(pngs)] + [(RT_GROUP_ICON, 1, grp)]
    types = sorted({t for t, _, _ in res})

    def d_hdr(n):  # IMAGE_RESOURCE_DIRECTORY (id 항목 n개)
        return struct.pack('<IIHHHH', 0, 0, 0, 0, 0, n)

    # 배치: 루트 → 종류별 디렉터리 → id별 언어 디렉터리 → 데이터 항목 → 데이터
    root_size = 16 + 8 * len(types)
    off = root_size
    type_off, name_off = {}, {}
    for t in types:
        items = [r for r in res if r[0] == t]
        type_off[t] = off
        off += 16 + 8 * len(items)
    for t, i, _ in res:
        name_off[(t, i)] = off
        off += 16 + 8
    entry_off = {}
    for t, i, _ in res:
        entry_off[(t, i)] = off
        off += 16
    data_off = {}
    for t, i, d in res:
        off = (off + 7) & ~7
        data_off[(t, i)] = off
        off += len(d)
    sec = bytearray(off)
    sec[0:16] = d_hdr(len(types))
    for k, t in enumerate(types):
        struct.pack_into('<II', sec, 16 + 8 * k, t, 0x80000000 | type_off[t])
        items = sorted(r for r in res if r[0] == t)
        sec[type_off[t]:type_off[t] + 16] = d_hdr(len(items))
        for j, (_, i, _) in enumerate(items):
            struct.pack_into('<II', sec, type_off[t] + 16 + 8 * j, i, 0x80000000 | name_off[(t, i)])
    relocs = []
    for t, i, d in res:
        no = name_off[(t, i)]
        sec[no:no + 16] = d_hdr(1)
        struct.pack_into('<II', sec, no + 16, LANG, entry_off[(t, i)])
        eo = entry_off[(t, i)]
        struct.pack_into('<IIII', sec, eo, data_off[(t, i)], len(d), 0, 0)
        relocs.append(eo)  # 데이터 주소 = 섹션 시작 + 이 값 (링커가 고침)
        sec[data_off[(t, i)]:data_off[(t, i)] + len(d)] = d

    hdr_size, sh_size = 20, 40
    raw_ptr = hdr_size + sh_size
    rel_ptr = raw_ptr + len(sec)
    sym_ptr = rel_ptr + 10 * len(relocs)
    out = bytearray()
    out += struct.pack('<HHIIIHH', 0x8664, 1, 0, sym_ptr, 1, 0, 0)
    out += struct.pack('<8sIIIIIIHHI', b'.rsrc', 0, 0, len(sec), raw_ptr, rel_ptr, 0, len(relocs), 0, 0x40000040)
    out += sec
    for r in relocs:
        out += struct.pack('<IIH', r, 0, 3)  # IMAGE_REL_AMD64_ADDR32NB
    out += struct.pack('<8sIhHBB', b'.rsrc', 0, 1, 0, 3, 0)
    out += struct.pack('<I', 4)  # 문자열 표 (비어 있음)
    dst = os.path.join(HERE, 'rsrc_windows_amd64.syso')
    open(dst, 'wb').write(out)
    print('아이콘 리소스:', dst, len(out), '바이트')


if __name__ == '__main__':
    main()
