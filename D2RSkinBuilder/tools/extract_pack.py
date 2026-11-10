"""완성된 스킨 빌더 exe 안의 기능 묶음을 꺼내 private/ 에 되살리기.

원본 파일을 잃어버렸을 때 씀 (실제로 3.2 원본을 이렇게 되살렸어요).
Go 의 embed 는 파일 내용을 실행 파일 안에 그대로 두므로, PE 헤더로 주소를 계산해
embed.FS 의 파일 표(이름·내용 문자열 쌍)를 읽고, features.json 과 blobs.bin 을 찾아 꺼내요.

    pip install pefile
    python tools/extract_pack.py D2R스킨빌더.exe
"""
import json
import os
import struct
import sys
import zlib
import hashlib

import pefile

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main(exe):
    d = open(exe, 'rb').read()
    pe = pefile.PE(data=d, fast_load=True)
    base = pe.OPTIONAL_HEADER.ImageBase
    secs = [(s.VirtualAddress + base, max(s.Misc_VirtualSize, s.SizeOfRawData), s.PointerToRawData, s.SizeOfRawData) for s in pe.sections]

    def va2off(va):
        for v, sz, p, rs in secs:
            if v <= va < v + sz:
                return va - v + p

    def off2va(o):
        for v, sz, p, rs in secs:
            if p <= o < p + rs:
                return o - p + v

    out = os.path.join(HERE, 'private')
    os.makedirs(os.path.join(out, 'img'), exist_ok=True)

    # 1. 기능 목록 (features.json) — JSON 시작 표시로 찾아 한 덩어리 읽기
    i = d.find(b'{"version":2,"groups"')
    if i < 0:
        sys.exit('기능 목록을 찾지 못했어요')
    txt = d[i:i + 8_000_000].decode('utf-8', 'ignore')
    pack, end = json.JSONDecoder().raw_decode(txt)
    open(os.path.join(out, 'features.json'), 'w', encoding='utf-8').write(txt[:end])
    print('features.json: 기능', len(pack['features']), '개')

    # 2. 기능 데이터 (blobs.bin) — 0번 데이터의 조각 표 모양으로 시작 위치 찾기
    o0, c0, u0 = pack['blobs'][0]
    n = (u0 + 16383) // 16384
    pat = struct.pack('<I', c0)
    j = 0
    start = None
    while True:
        j = d.find(pat, j + 1)
        if j < 0:
            break
        st = j - 4 * n
        if st >= 0 and struct.unpack_from('<I', d, st)[0] == 4 * (n + 1):
            start = st
            break
    if start is None:
        sys.exit('기능 데이터를 찾지 못했어요')
    total = pack['blobs'][-1][0] + pack['blobs'][-1][1]
    blob = d[start:start + total]
    open(os.path.join(out, 'blobs.bin'), 'wb').write(blob)
    # 확인: 처음·끝 데이터를 풀어 sha 비교
    for k in (0, len(pack['blobs']) - 1):
        o, c, u = pack['blobs'][k]
        part, raw = blob[o:o + c], b''
        for s in range((u + 16383) // 16384):
            a, b = struct.unpack_from('<II', part, 4 * s)
            piece = part[a:b]
            raw += piece if len(piece) == min(16384, u - s * 16384) else zlib.decompress(piece[1:])
        assert hashlib.sha1(raw).hexdigest() == pack['sha'][k], '데이터 확인 실패'
    print('blobs.bin:', round(total / 1e6, 1), 'MB (확인 완료)')

    # 3. 미리보기 그림 (embed.FS 파일 표: 이름 문자열·내용 문자열·해시 = 48바이트씩)
    hit = d.find(b'ui/index.html')
    va = off2va(hit)
    k = d.find(struct.pack('<QQ', va, len('ui/index.html')))
    while True:
        nptr, nlen = struct.unpack_from('<QQ', d, k - 48)
        if not (0 < nlen < 300) or va2off(nptr) is None:
            break
        k -= 48
    cnt = 0
    while True:
        nptr, nlen, dptr, dlen = struct.unpack_from('<QQQQ', d, k)
        if not (0 < nlen < 300) or va2off(nptr) is None:
            break
        name = d[va2off(nptr):va2off(nptr) + nlen].decode('utf-8', 'replace')
        if name.startswith('assets/img/') and not name.endswith('/') and dlen:
            p = os.path.join(out, 'img', name[len('assets/img/'):])
            os.makedirs(os.path.dirname(p), exist_ok=True)
            open(p, 'wb').write(d[va2off(dptr):va2off(dptr) + dlen])
            cnt += 1
        k += 48
    print('img: 그림', cnt, '개')


if __name__ == '__main__':
    main(sys.argv[1])
