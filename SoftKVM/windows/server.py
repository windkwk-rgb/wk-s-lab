"""
KVM 서버 (Windows, 메인 PC)
 - 화면 가장자리로 마우스를 밀면 연결된 맥/안드로이드로 마우스·키보드가 넘어갑니다.
 - 배치 화면에서 기기 상자를 끌어 위치를 정하면 바로 적용됩니다 (맞닿은 구간에서만 넘어감).
 - 트레이 아이콘: 더블클릭 = 배치 화면, 우클릭 = 종료
 - Scroll Lock 키: 언제든 강제로 윈도우로 복귀

설치:  pip install pynput pyperclip pystray pillow pywebview
실행:  python server.py      (콘솔 없이: pythonw server.py)
"""
import base64
import ctypes
import hashlib
import json
import os
import queue
import re
import socket
import sys
import subprocess
import threading
import time
import zipfile
import shutil
from html import escape as html_escape

import pyperclip
import pystray
from PIL import Image, ImageDraw
from pynput import keyboard, mouse

from kvm_common import (ProfileStore, current_ssid, discover, load_json, save_json,
                        SUB_CONFIG, SUB_PROFILES, MacroOverlay, overlay_text, overlay_pos)

if sys.platform != "win32":
    sys.exit("이 서버는 Windows 전용입니다.")

user32 = ctypes.windll.user32
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)  # 고해상도(배율) 화면에서 좌표 어긋남 방지
except Exception:
    user32.SetProcessDPIAware()

# ---------------------------------------------------------------- 설정
# exe로 빌드됐을 때는 exe 옆에, 아니면 이 파일 옆에 config.json 저장
HERE = os.path.dirname(sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")


class _Tee:
    """콘솔 출력을 kvm-server.log 파일에도 남김 (exe는 콘솔이 없어서 문제 확인용)"""
    def __init__(self, stream):
        self.stream = stream
        self.file = open(os.path.join(HERE, "kvm-server.log"), "a", encoding="utf-8", buffering=1)

    def write(self, s):
        if self.stream:
            try:
                self.stream.write(s)
            except Exception:
                pass
        self.file.write(s)

    def flush(self):
        if self.stream:
            try:
                self.stream.flush()
            except Exception:
                pass


sys.stdout = _Tee(sys.stdout)


def read_version():
    for d in (HERE, os.path.dirname(HERE)):
        try:
            with open(os.path.join(d, "VERSION.txt"), encoding="utf-8") as f:
                return f.read().strip()
        except OSError:
            pass
    return "?"


VERSION = read_version()
DEFAULT_CONFIG = {
    "port": 24800,
    "key": "change-me",          # 클라이언트와 같은 비밀번호
    "return_hotkey_vk": 0x91,    # Scroll Lock
    "show_layout_on_start": True,
    # 기기 상자 위치/크기 (윈도우 화면 왼쪽 위 = 0,0, 단위: 윈도우 픽셀). 배치 화면이 자동 저장
    "layout": {},
}


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, encoding="utf-8") as f:
            cfg.update(json.load(f))
    cfg["layout"] = dict(cfg.get("layout") or {})
    # 이전 버전 설정("screens": {"left": "mac"}) 변환
    for side, name in (cfg.pop("screens", None) or {}).items():
        if name not in cfg["layout"]:
            cfg["layout"][name] = {"side_hint": side}
    return cfg


def save_config(cfg):
    tmp = CONFIG_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)
    os.replace(tmp, CONFIG_PATH)


# ---------------------------------------------------------------- Win32 상수
WM_MOUSEMOVE, WM_LBUTTONDOWN, WM_LBUTTONUP = 0x200, 0x201, 0x202
WM_RBUTTONDOWN, WM_RBUTTONUP = 0x204, 0x205
WM_MBUTTONDOWN, WM_MBUTTONUP = 0x207, 0x208
WM_MOUSEWHEEL, WM_XBUTTONDOWN, WM_XBUTTONUP, WM_MOUSEHWHEEL = 0x20A, 0x20B, 0x20C, 0x20E
WM_KEYDOWN, WM_KEYUP, WM_SYSKEYDOWN, WM_SYSKEYUP = 0x100, 0x101, 0x104, 0x105
LLMHF_INJECTED = 0x01
LLKHF_INJECTED = 0x10

BTN_MSG = {
    WM_LBUTTONDOWN: ("l", True), WM_LBUTTONUP: ("l", False),
    WM_RBUTTONDOWN: ("r", True), WM_RBUTTONUP: ("r", False),
    WM_MBUTTONDOWN: ("m", True), WM_MBUTTONUP: ("m", False),
}


def virtual_screen():
    """모든 모니터를 합친 가상 화면 (left, top, width, height)"""
    return (user32.GetSystemMetrics(76), user32.GetSystemMetrics(77),
            user32.GetSystemMetrics(78), user32.GetSystemMetrics(79))


def primary_center():
    return user32.GetSystemMetrics(0) // 2, user32.GetSystemMetrics(1) // 2


def is_relative(c):
    """움직인 양만 보내는 기기 (아이폰, 진짜 마우스 모드가 켜진 태블릿)"""
    return getattr(c, "relative", False) or getattr(c, "hid_on", False)


def file_message(kind, path):
    """파일을 '{"t": kind, "b64": ...}' 한 줄로 만듦. 1MB씩 나눠 인코딩하고 사이사이 쉬어서
    마우스·키보드 훅이 멈추지 않게 함 (훅이 0.3초 이상 막히면 윈도우가 훅을 해제해 버림)"""
    parts = [b'{"t":"' + kind.encode() + b'","b64":"']
    with open(path, "rb") as f:
        while True:
            chunk = f.read(3 * 262144)          # 3의 배수 크기로 잘라야 이어 붙여도 올바른 base64
            if not chunk:
                break
            parts.append(base64.b64encode(chunk))
            time.sleep(0.002)
    parts.append(b'"}\n')
    return b"".join(parts)


def buttons_held():
    """실제 마우스 버튼이 눌려 있는지 (드래그 중에는 화면 전환 안 함)"""
    return any(user32.GetAsyncKeyState(vk) & 0x8000 for vk in (0x01, 0x02, 0x04))


def hiword_signed(v):
    return ctypes.c_short((v >> 16) & 0xFFFF).value


# ---------------------------------------------------------------- 매크로 (녹화 → 자동 실행)
MACRO_MAGIC = 0x4B564D21          # 매크로가 직접 넣은 입력 표시 (훅에서 다시 잡지 않게)
MACRO_KEYSETS = [(0x13, 0x91), (0x78, 0x79), (0x76, 0x77)]      # (녹화, 실행): Pause/ScrollLock, F9/F10, F7/F8
COMBO = 0x10000                   # 복귀 단축키 값에 더하면 Ctrl+Alt+키
VK_NAMES = {0x13: "Pause", 0x91: "Scroll Lock", 0x24: "Home", 0x23: "End", 0x2D: "Insert", 0x2E: "Delete",
            0x21: "Page Up", 0x22: "Page Down", 0x2C: "Print Screen", 0x14: "Caps Lock", 0x90: "Num Lock",
            0x09: "Tab", 0x0D: "Enter", 0x20: "Space", 0x08: "Backspace", 0x15: "한/영", 0x19: "한자",
            0x25: "←", 0x26: "↑", 0x27: "→", 0x28: "↓", 0x5D: "메뉴", 0xC0: "`", 0xBD: "-", 0xBB: "=",
            0xDB: "[", 0xDD: "]", 0xDC: "\\", 0xBA: ";", 0xDE: "'", 0xBC: ",", 0xBE: ".", 0xBF: "/",
            0x6A: "숫자패드 *", 0x6B: "숫자패드 +", 0x6D: "숫자패드 -", 0x6E: "숫자패드 .", 0x6F: "숫자패드 /",
            0x05: "마우스 뒤로", 0x06: "마우스 앞으로"}
VK_NAMES.update({0x70 + i: f"F{i + 1}" for i in range(24)})
VK_NAMES.update({0x41 + i: chr(0x41 + i) for i in range(26)})
VK_NAMES.update({0x30 + i: str(i) for i in range(10)})
VK_NAMES.update({0x60 + i: f"숫자패드 {i}" for i in range(10)})
VK_NAMES.update({0x10D: "숫자패드 Enter", 0x0C: "숫자패드 5"})
# Num Lock이 꺼진 숫자 키패드는 Home·End·방향키 등으로 들어오는데, '확장 키' 표시가 없어서 진짜 방향키와 구분됨
_NUMPAD_NAV = {0x2D: 0x60, 0x23: 0x61, 0x28: 0x62, 0x22: 0x63, 0x25: 0x64, 0x0C: 0x65,
               0x27: 0x66, 0x24: 0x67, 0x26: 0x68, 0x21: 0x69, 0x2E: 0x6E}


def hotkey_id(vk, flags):
    """단축키 비교용 키 번호 (숫자 키패드는 Num Lock과 상관없이 '숫자패드 N'으로)"""
    extended = flags & 0x01
    if not extended and vk in _NUMPAD_NAV:
        return _NUMPAD_NAV[vk]
    if extended and vk == 0x0D:
        return 0x10D
    return vk


MODIFIER_VKS = {0x10, 0xA0, 0xA1, 0x11, 0xA2, 0xA3, 0x12, 0xA4, 0xA5, 0x5B, 0x5C}


def hotkey_name(v):
    v = int(v)
    base = VK_NAMES.get(v & 0xFFFF, f"키 {v & 0xFFFF}")
    return ("Ctrl+Alt+" + base) if v & COMBO else base


class _MI(ctypes.Structure):
    _fields_ = [("dx", ctypes.c_long), ("dy", ctypes.c_long), ("mouseData", ctypes.c_uint32),
                ("dwFlags", ctypes.c_uint32), ("time", ctypes.c_uint32), ("dwExtraInfo", ctypes.c_size_t)]


class _KI(ctypes.Structure):
    _fields_ = [("wVk", ctypes.c_uint16), ("wScan", ctypes.c_uint16), ("dwFlags", ctypes.c_uint32),
                ("time", ctypes.c_uint32), ("dwExtraInfo", ctypes.c_size_t)]


class _HI(ctypes.Structure):
    _fields_ = [("uMsg", ctypes.c_uint32), ("wParamL", ctypes.c_uint16), ("wParamH", ctypes.c_uint16)]


class _IU(ctypes.Union):
    _fields_ = [("mi", _MI), ("ki", _KI), ("hi", _HI)]


class _INPUT(ctypes.Structure):
    _fields_ = [("type", ctypes.c_uint32), ("u", _IU)]


_EXTENDED = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x5B, 0x5C, 0x5D, 0x6F, 0x90, 0xA3, 0xA5}
_BTN_FLAGS = {"l": (0x2, 0x4, 0), "r": (0x8, 0x10, 0), "m": (0x20, 0x40, 0), "x1": (0x80, 0x100, 1), "x2": (0x80, 0x100, 2)}


def _send_input(inp):
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(_INPUT))


def local_move(x, y):
    l, t, vw, vh = virtual_screen()
    inp = _INPUT(type=0)
    inp.u.mi = _MI(int((x - l) * 65535 / max(vw - 1, 1)), int((y - t) * 65535 / max(vh - 1, 1)),
                   0, 0x0001 | 0x8000 | 0x4000, 0, MACRO_MAGIC)
    _send_input(inp)


def local_button(b, down):
    if b not in _BTN_FLAGS:
        return
    fd, fu, data = _BTN_FLAGS[b]
    inp = _INPUT(type=0)
    inp.u.mi = _MI(0, 0, data, fd if down else fu, 0, MACRO_MAGIC)
    _send_input(inp)


def local_wheel(dx, dy):
    for amount, flag in ((dy, 0x0800), (dx, 0x1000)):
        if amount:
            inp = _INPUT(type=0)
            inp.u.mi = _MI(0, 0, ctypes.c_uint32(int(amount)).value, flag, 0, MACRO_MAGIC)
            _send_input(inp)


def local_key(vk, down):
    inp = _INPUT(type=1)
    flags = (0 if down else 0x2) | (0x1 if vk in _EXTENDED else 0)
    inp.u.ki = _KI(vk, user32.MapVirtualKeyW(vk, 0) & 0xFF, flags, 0, MACRO_MAGIC)
    _send_input(inp)


MAIN_DEV = "메인 PC"


class Macro:
    """녹화 키: 녹화 시작/끝 · 실행 키: 녹화한 그대로 실행 (실행 중에 누르면 멈춤) · 전환 키: A↔B
    매크로는 기기별로 따로: 태블릿에서 녹화한 건 태블릿에, 서브 PC에서 녹화한 건 서브 PC에 저장
    (그 기기 앱이 직접 보관하고, 연결되면 메인 PC로 보내 줌. 보관 기능이 없는 기기(맥)는 메인 PC가 대신 보관)"""

    def __init__(self, server):
        self.s = server
        self.recording = False
        self.players = {}             # 기기 → {"stop": Event, "thread", "round"} (기기마다 동시에 실행 가능)
        self.events = []              # 녹화 중인 동작
        self.rec_dev = MAIN_DEV
        self.last_dev = MAIN_DEV      # 마지막으로 녹화·실행·불러온 기기
        self.t0 = 0.0
        self.capture = None
        self.capture_until = 0.0
        self.pos = {}                 # 실행 중 기기별 커서 위치 (사용자 마우스와 따로)
        self.click_logs = 0
        self.data = {}                # 기기 → {"library": {이름: {"events","saved"}}, "current": {"events","name"}, "slots": [A, B]}
        self.self_store = set()       # 매크로를 직접 보관하는 기기 (태블릿·서브 PC 앱)
        self.lock = threading.RLock()
        base = os.path.dirname(CONFIG_PATH)
        self.lib_path = os.path.join(base, "macros.json")
        self._load_main_file(os.path.join(base, "macro.json"))

    # --- 메인 PC 파일 (메인 PC 것 + 직접 보관 못 하는 기기 것 + 아직 기기로 못 보낸 것)
    def _load_main_file(self, old_current_path):
        try:
            with open(self.lib_path, encoding="utf-8") as f:
                d = json.load(f)
        except Exception:
            d = {}
        if d.get("v") == 2:
            self.data = d.get("devices", {})
            return
        # 예전 형식(모든 기기 것이 한 목록) → 녹화한 기기별로 나눔
        for name, item in d.items():
            self._blob(self._dev_of_events(item.get("events", [])))["library"][name] = item
        try:
            with open(old_current_path, encoding="utf-8") as f:
                cur = json.load(f)
            if cur.get("events"):
                self._blob(self._dev_of_events(cur["events"]))["current"] = {"events": cur["events"], "name": cur.get("name")}
        except Exception:
            pass
        for dev, sl in (self.s.cfg.pop("macro_slots", None) or {}).items():
            self._blob(dev)["slots"] = list(sl)[:2] + [None] * (2 - len(sl))
        self._write_main()

    def _write_main(self):
        with self.lock:
            out = {k: v for k, v in self.data.items() if k not in self.self_store}
        try:
            tmp = self.lib_path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump({"v": 2, "devices": out}, f, ensure_ascii=False)
            os.replace(tmp, self.lib_path)
        except OSError as e:
            print(f"[매크로] 저장 실패: {e}")

    def _blob(self, dev):
        b = self.data.setdefault(dev, {})
        b.setdefault("library", {})
        b.setdefault("current", {"events": [], "name": None})
        b.setdefault("slots", [None, None])
        return b

    def persist(self, dev):
        """바뀐 기기 것을 저장: 직접 보관하는 기기면 그 기기로 보내고, 아니면 메인 PC 파일에"""
        if dev in self.self_store:
            c = self.s.clients.get(dev)
            if c is not None:
                c.send({"t": "macro_store", "data": self._blob(dev)})
                return
        self._write_main()

    # --- 기기 앱과 주고받기
    def device_upload(self, client, data):
        """기기가 연결되며 자기 매크로를 보내 줌 → 메인 PC에 남아 있던 그 기기 것과 합쳐서 기기로 돌려보냄"""
        dev = client.name
        with self.lock:
            mine = self.data.get(dev)
            b = {"library": dict((data or {}).get("library") or {}),
                 "current": (data or {}).get("current") or {"events": [], "name": None},
                 "slots": list((data or {}).get("slots") or [None, None])}
            moved = 0
            if mine:
                for n, item in mine.get("library", {}).items():
                    if n not in b["library"]:
                        b["library"][n] = item
                        moved += 1
                if not b["current"].get("events") and mine.get("current", {}).get("events"):
                    b["current"] = mine["current"]
                if not any(b["slots"]) and any(mine.get("slots") or []):
                    b["slots"] = list(mine["slots"])
            self.data[dev] = b
            self.self_store.add(dev)
        print(f"[매크로] {dev}: 보관 중인 매크로 {len(b['library'])}개 · 지금 녹화 {'있음' if b['current'].get('events') else '없음'}")
        self._blob(dev)
        if mine:
            print(f"[매크로] {dev}: 메인 PC에 있던 매크로 {moved}개를 {dev}로 옮김")
            try:      # 혹시 모를 일에 대비해 옮기기 전 것을 남겨 둠
                bk = os.path.join(os.path.dirname(self.lib_path), "macros-backup.json")
                old = {}
                if os.path.exists(bk):
                    with open(bk, encoding="utf-8") as f:
                        old = json.load(f)
                old[f"{dev} {time.strftime('%Y-%m-%d %H:%M:%S')}"] = mine
                with open(bk, "w", encoding="utf-8") as f:
                    json.dump(old, f, ensure_ascii=False)
            except Exception:
                pass
            self._write_main()                # 메인 PC 파일에서는 빠짐
        client.send({"t": "macro_store", "data": self._blob(dev)})
        self.changed()

    def device_gone(self, dev):
        with self.lock:
            if dev in self.self_store:
                self.self_store.discard(dev)
                self.data.pop(dev, None)      # 그 기기 것은 그 기기에만
        if dev in self.players:
            self.stop_play(dev)
        self.changed()

    # --- 저장·불러오기 (모두 기기별)
    @staticmethod
    def _summary(events):
        actions = sum(1 for e in events if e[1] in ("btn", "key") and e[-1])
        return actions, (events[-1][0] if events else 0)

    @staticmethod
    def _dev_of_events(events):
        return next((e[2] for e in events if e[1] == "dev"), None) or MAIN_DEV

    def save_as(self, dev, name):
        name = str(name).strip()[:40]
        if not name:
            return "이름을 입력하세요"
        if self.recording:
            return "녹화를 먼저 끝내 주세요"
        b = self._blob(dev)
        if len(b["current"].get("events") or []) <= 1:
            return "저장할 녹화가 없어요"
        b["library"][name] = {"events": list(b["current"]["events"]), "saved": time.strftime("%Y-%m-%d %H:%M")}
        b["current"]["name"] = name
        self.persist(dev)
        print(f"[매크로] {dev}: '{name}' 저장")
        self.changed()
        return "ok"

    def load(self, dev, name):
        b = self._blob(dev)
        if name not in b["library"]:
            return "없는 매크로예요"
        if self.recording or dev in self.players:
            return "녹화·실행을 먼저 멈춰 주세요"
        b["current"] = {"events": list(b["library"][name]["events"]), "name": name}
        self.last_dev = dev
        self.persist(dev)
        n, dur = self._summary(b["current"]["events"])
        self.tell(f"'{name}' 불러옴 · 동작 {n}개 · {hotkey_name(self.keys()[1])} 키로 실행")
        self.changed()
        return "ok"

    def set_slot(self, dev, name, slot):
        b = self._blob(dev)
        if name not in b["library"] or slot not in ("A", "B"):
            return "없는 매크로예요"
        cur = b["slots"]
        i, j = (0, 1) if slot == "A" else (1, 0)
        cur[i] = None if cur[i] == name else name
        if cur[j] == name:
            cur[j] = None
        # A로 정하면 바로 실행 키에 연결 (불러오기를 따로 안 눌러도 됨)
        if slot == "A" and cur[0] == name and b["current"].get("name") != name and dev not in self.players \
                and not (self.recording and self.rec_dev == dev):
            b["current"] = {"events": list(b["library"][name]["events"]), "name": name}
            self.last_dev = dev
            print(f"[매크로] {dev}: '{name}'을(를) A로 정하고 바로 적용")
        self.persist(dev)
        self.changed()
        return "ok"

    def switch(self, dev=None):
        if self.recording:
            return self.tell("녹화 중에는 전환할 수 없어요")
        dev = dev or self.s.active or MAIN_DEV
        if dev in self.players:
            self.stop_play(dev)
        b = self._blob(dev)
        lib = b["library"]
        a, bb = [x if x in lib else None for x in b["slots"][:2]]
        name = b["current"].get("name")
        if a and bb:
            target = bb if name == a else a
        else:
            names = [n for n, _ in sorted(lib.items(), key=lambda kv: kv[1].get("saved", ""))]
            if not names:
                return self.tell("전환할 저장된 매크로가 없어요 · 먼저 녹화하고 저장하세요")
            target = names[(names.index(name) + 1) % len(names)] if name in names else names[0]
        slot = "A" if target == a else "B" if target == bb else ""
        b["current"] = {"events": list(lib[target]["events"]), "name": target}
        self.last_dev = dev
        self.persist(dev)
        n, dur = self._summary(b["current"]["events"])
        self.tell(f"{slot + ' · ' if slot else ''}'{target}'(으)로 전환 · 동작 {n}개")
        self.changed()

    def clear(self, dev):
        """지금 녹화(실행 키에 연결된 것) 지우기 · 저장한 목록은 그대로"""
        if self.recording or dev in self.players:
            return "녹화·실행을 먼저 멈춰 주세요"
        self._blob(dev)["current"] = {"events": [], "name": None}
        self.persist(dev)
        self.tell("지금 녹화를 지웠어요")
        self.changed()
        return "ok"

    def delete_all(self, dev):
        b = self._blob(dev)
        n = len(b["library"])
        b["library"] = {}
        b["slots"] = [None, None]
        b["current"]["name"] = None
        self.persist(dev)
        self.tell(f"저장한 매크로 {n}개를 삭제했어요")
        self.changed()
        return "ok"

    def delete(self, dev, name):
        b = self._blob(dev)
        if b["library"].pop(name, None) is None:
            return "없는 매크로예요"
        b["slots"] = [None if x == name else x for x in b["slots"]]
        if b["current"].get("name") == name:
            b["current"]["name"] = None
        self.persist(dev)
        self.changed()
        return "ok"

    def rename(self, dev, old, new):
        new = str(new).strip()[:40]
        b = self._blob(dev)
        if old not in b["library"] or not new:
            return "이름을 확인하세요"
        if new in b["library"] and new != old:
            return "같은 이름이 이미 있어요"
        b["library"][new] = b["library"].pop(old)
        b["slots"] = [new if x == old else x for x in b["slots"]]
        if b["current"].get("name") == old:
            b["current"]["name"] = new
        self.persist(dev)
        self.changed()
        return "ok"

    def set_folder(self, dev, name, folder):
        """저장한 매크로를 폴더에 넣기 (빈 이름이면 폴더에서 빼기)"""
        b = self._blob(dev)
        if name not in b["library"]:
            return "없는 매크로예요"
        folder = str(folder or "").strip()[:30]
        if folder:
            b["library"][name]["folder"] = folder
        else:
            b["library"][name].pop("folder", None)
        self.persist(dev)
        self.changed()
        return "ok"

    def rename_folder(self, dev, old, new):
        new = str(new or "").strip()[:30]
        for d in self._blob(dev)["library"].values():
            if d.get("folder") == old:
                if new:
                    d["folder"] = new
                else:
                    d.pop("folder", None)
        self.persist(dev)
        self.changed()
        return "ok"

    def entries(self, dev):
        b = self._blob(dev)
        sl = b["slots"]
        out = []
        for n, d in b["library"].items():
            c, dur = self._summary(d.get("events", []))
            out.append({"name": n, "count": c, "dur": round(dur, 1), "saved": d.get("saved", ""),
                        "dev": dev, "slot": "A" if sl[0] == n else "B" if sl[1] == n else None,
                        "folder": d.get("folder", "")})
        return sorted(out, key=lambda x: x["saved"], reverse=True)

    def devices(self):
        """매크로가 있거나 지금 연결된 기기 (메인 PC 설정 창의 기기 선택용)"""
        with self.s.lock:
            connected = list(self.s.clients)
        out = [MAIN_DEV] + [d for d in self.data if d != MAIN_DEV and (d in connected or d not in self.self_store)]
        return out + [d for d in connected if d not in out]

    # --- 단축키
    KEY_CFG = {"rec": "macro_rec_vk", "play": "macro_play_vk", "switch": "macro_switch_vk"}
    KEY_LABEL = {"rec": "녹화", "play": "실행", "switch": "전환"}

    def keys(self):
        return int(self.s.cfg.get("macro_rec_vk", 0x13)), int(self.s.cfg.get("macro_play_vk", 0x91))

    def switch_key(self):
        return int(self.s.cfg.get("macro_switch_vk", 0x2D))

    # 매크로 키 켜기/끄기 (끄면 숫자패드 등 매크로 키를 평소처럼 씀)
    def toggle_key(self):
        v = self.s.cfg.get("macro_toggle_vk")
        return int(v) if v else None

    @property
    def keys_on(self):
        return bool(self.s.cfg.get("macro_keys_on", True))

    def toggle_keys(self):
        self.s.cfg["macro_keys_on"] = not self.keys_on
        threading.Thread(target=self.s.save, daemon=True).start()
        self.tell("매크로 키 켜짐" if self.keys_on else "매크로 키 꺼짐 · 숫자패드 등을 평소처럼 써요")
        self.changed()

    # 기기 전용 키: 그 기기 매크로만 실행(멈춤) · 전환 (마우스가 어디 있든)
    def dev_keys(self):
        return self.s.cfg.setdefault("macro_dev_keys", {})

    def devkey_lookup(self, vk):
        for dev, k in self.dev_keys().items():
            for op in ("play", "switch"):
                if k.get(op) == vk:
                    return op, dev
        return None

    def clear_devkey(self, dev, op):
        k = self.dev_keys().get(dev) or {}
        k.pop(op, None)
        self.s.save()
        self.changed()
        return "ok"

    def run_devkey(self, op, dev):
        if op == "play":
            self.toggle_play(dev, explicit=True)
        else:
            self.switch(dev)

    # --- 단축키 바꾸기: 설정 창에서 '변경'을 누른 뒤 아무 키나 누르면 그 키로 (Esc = 취소)
    def start_capture(self, which):
        if which not in self.KEY_CFG and which != "toggle" and not str(which).startswith(("dplay:", "dswitch:")):
            return False
        self.capture = which
        self.capture_until = time.time() + 15
        self.changed()
        return True

    def captured(self, vk):
        """키 훅에서 불림. 처리했으면 True"""
        which = self.capture
        if not which or vk in MODIFIER_VKS:
            return False
        self.capture = None
        if vk == 0x1B or time.time() > self.capture_until:
            threading.Thread(target=self.tell, args=("단축키 변경 취소",), daemon=True).start()
            self.changed()
            return True
        cfg = self.s.cfg
        cur = {"macro_rec_vk": self.keys()[0], "macro_play_vk": self.keys()[1], "macro_switch_vk": self.switch_key()}
        if which == "toggle":                            # 매크로 키 켜기/끄기 키
            if vk in cur.values() or self.devkey_lookup(vk):
                threading.Thread(target=self.tell, args=(f"{hotkey_name(vk)}는 이미 매크로 키로 쓰고 있어요",), daemon=True).start()
            else:
                cfg["macro_toggle_vk"] = vk
                threading.Thread(target=self.s.save, daemon=True).start()
                threading.Thread(target=self.tell, args=(f"매크로 켜기/끄기 키를 {hotkey_name(vk)}(으)로 정했어요",), daemon=True).start()
            self.changed()
            return True
        if which.startswith(("dplay:", "dswitch:")):      # 기기 전용 키
            op, dev = which.split(":", 1)
            op = "play" if op == "dplay" else "switch"
            if vk in cur.values() or (int(cfg.get("return_hotkey_vk", 0)) & 0xFFFF) == vk and not (int(cfg.get("return_hotkey_vk", 0)) & COMBO):
                threading.Thread(target=self.tell, args=(f"{hotkey_name(vk)}는 이미 다른 단축키로 쓰고 있어요",), daemon=True).start()
                self.changed()
                return True
            for k in self.dev_keys().values():         # 다른 기기 키에서 같은 키는 빼기
                for o in ("play", "switch"):
                    if k.get(o) == vk:
                        k.pop(o, None)
            self.dev_keys().setdefault(dev, {})[op] = vk
            threading.Thread(target=self.s.save, daemon=True).start()
            threading.Thread(target=self.tell, args=(f"{dev} {'실행' if op == 'play' else '전환'} 키를 {hotkey_name(vk)}(으)로 정했어요",), daemon=True).start()
            self.changed()
            return True
        for k in self.dev_keys().values():             # 기기 전용 키와 겹치면 기기 키에서 빼기
            for o in ("play", "switch"):
                if k.get(o) == vk:
                    k.pop(o, None)
        mine = self.KEY_CFG[which]
        for k, v in cur.items():                        # 다른 매크로 키와 같으면 서로 바꿈
            if k != mine and v == vk:
                cfg[k] = cur[mine]
        cfg[mine] = vk
        used = {int(cfg.get(k, cur[k])) for k in cur}
        if int(cfg.get("return_hotkey_vk", 0)) in used:
            cfg["return_hotkey_vk"] = COMBO | 0x24
        threading.Thread(target=self.s.save, daemon=True).start()
        label = self.KEY_LABEL[which]
        threading.Thread(target=self.tell, args=(f"{label} 키를 {hotkey_name(vk)}(으)로 바꿨어요",), daemon=True).start()
        self.changed()
        return True

    def changed(self):
        """기기 앱(태블릿·서브 PC)의 매크로 화면 갱신"""
        threading.Thread(target=self.s.push_macro_state, daemon=True).start()

    def state(self, dev):
        b = self._blob(dev)
        cur = b["current"]
        n, dur = self._summary(cur.get("events") or [])
        rep = int(self.s.cfg.get("macro_repeat", 1))
        cname = cur.get("name")
        cur_slot = "A" if cname and b["slots"][0] == cname else "B" if cname and b["slots"][1] == cname else None
        pl = self.players.get(dev)
        dk = self.dev_keys().get(dev) or {}
        return {"recording": self.recording, "playing": pl is not None, "any_playing": bool(self.players),
                "rec_dev": self.rec_dev if self.recording else None, "play_dev": dev if pl else None,
                "count": n, "dur": round(dur, 1), "dev": dev,
                "round": pl["round"] if pl else 0, "repeat": rep, "capture": self.capture,
                "dev_play_name": hotkey_name(dk["play"]) if dk.get("play") else None,
                "keys_on": self.keys_on, "toggle_name": hotkey_name(self.toggle_key()) if self.toggle_key() else None,
                "dev_switch_name": hotkey_name(dk["switch"]) if dk.get("switch") else None,
                "rec_vk": self.keys()[0], "play_vk": self.keys()[1], "switch_vk": self.switch_key(),
                "switch_name": hotkey_name(self.switch_key()),
                "rec_name": hotkey_name(self.keys()[0]), "play_name": hotkey_name(self.keys()[1]),
                "name": cname, "cur_slot": cur_slot, "overlay": bool(self.s.cfg.get("macro_overlay", True)),
                "overlay_pos": self.s.cfg.get("overlay_pos", "tl"),
                "library": self.entries(dev)}

    def tell(self, text):
        print(f"[매크로] {text}")
        self.s.notify(text)

    # --- 녹화 (훅에서 server.lock을 잡은 채로 불림 → 가볍게)
    def rec(self, kind, *a):
        if self.recording:
            self.events.append([round(time.perf_counter() - self.t0, 4), kind, *a])

    def toggle_record(self, dev=None):
        if self.recording:
            return self.stop_record()
        self.stop_play()
        with self.s.lock:
            self.events = []
            self.t0 = time.perf_counter()
            active = self.s.active
            dev = dev or active or MAIN_DEV
            c = self.s.clients.get(dev) if dev != MAIN_DEV else None
            if c is None:
                dev = MAIN_DEV
                pt = POINT()
                user32.GetCursorPos(ctypes.byref(pt))
                self.events.append([0.0, "dev", None, pt.x, pt.y])
            else:
                if active == dev:
                    x, y = int(self.s.rx), int(self.s.ry)
                else:
                    x, y = self.pos.get(dev, [c.w // 2, c.h // 2])
                x, y = int(x), int(y)
                self.events.append([0.0, "dev", dev, x, y])
                if getattr(c, "hid_on", False):
                    c.send({"t": "warp", "x": x, "y": y})     # 위치를 맞춰 두고 시작 (실행할 때도 똑같이)
            self.rec_dev = dev
            self.recording = True
        self.tell(f"{'' if dev == MAIN_DEV else dev + ' · '}녹화 시작 · {hotkey_name(self.keys()[0])} 키를 다시 누르면 끝")
        self.changed()

    def stop_record(self, trim=0.0):
        if not self.recording:
            return
        with self.s.lock:
            self.recording = False
            if trim > 0:       # 기기 앱의 '녹화 끝' 버튼을 누르러 간 동작은 빼기
                cut = time.perf_counter() - self.t0 - trim
                self.events = [e for e in self.events if e[0] <= cut or e is self.events[0]]
            # 끝낼 때 누르고 있던 키·버튼은 떼는 것으로 기록
            t = round(time.perf_counter() - self.t0, 4)
            held = {}
            cur = None
            for e in self.events:
                if e[1] == "dev":
                    cur = e[2]
                elif e[1] in ("btn", "key"):
                    held[(cur, e[1], e[2])] = e[3]
            for (d, kind, k), down in held.items():
                if down and d == cur:
                    self.events.append([t, kind, k, False])
            dev = self.rec_dev
            kept = self.only_device(self.events, dev)
            if len(kept) < len(self.events):
                print(f"[매크로] 녹화 중 다른 기기로 넘어간 동작 {len(self.events) - len(kept)}개는 뺐어요")
            self._blob(dev)["current"] = {"events": kept, "name": None}
            self.events = []
        self.last_dev = dev
        self.persist(dev)
        n, dur = self.summary(dev)
        self.tell(f"녹화 끝 · 동작 {n}개 · {dur:.1f}초 · {hotkey_name(self.keys()[1])} 키로 실행")
        self.changed()

    def summary(self, dev):
        return self._summary(self._blob(dev)["current"].get("events") or [])

    # --- 실행 (기기마다 따로 · 동시에 여러 기기 가능)
    def toggle_play(self, dev=None, explicit=False):
        """explicit=False(실행 키): 실행 중인 게 있으면 모두 멈춤, 없으면 마우스가 있는 기기 것 실행
        explicit=True(기기 전용 키·앱 버튼): 그 기기만 실행/멈춤"""
        if self.recording:
            self.stop_record()
            time.sleep(0.3)
        if explicit and dev in self.players:
            return self.stop_play(dev)
        if not explicit and self.players:
            return self.stop_play()
        dev = dev or self.s.active or MAIN_DEV
        events = self._blob(dev)["current"].get("events") or []
        if len(events) <= 1:
            # 지금 연결된 녹화가 없으면 그 기기에 저장해 둔 것(A 지정 → 가장 최근 저장) 을 자동으로 불러옴
            b = self._blob(dev)
            lib = b["library"]
            pick = b["slots"][0] if b["slots"][0] in lib else None
            if not pick and lib:
                pick = max(lib, key=lambda n: lib[n].get("saved", ""))
            if pick:
                b["current"] = {"events": list(lib[pick]["events"]), "name": pick}
                self.persist(dev)
                events = b["current"]["events"]
                print(f"[매크로] {dev}: 저장해 둔 '{pick}'을(를) 불러와서 실행")
        if len(events) <= 1 and not explicit and self.last_dev != dev:   # 이 기기에 녹화가 없으면 마지막으로 쓴 기기 것
            dev = self.last_dev
            events = self._blob(dev)["current"].get("events") or []
        if len(events) <= 1:
            return self.tell(f"{'' if dev == MAIN_DEV else dev + ': '}녹화된 동작이 없어요 · 먼저 녹화하세요")
        if dev != MAIN_DEV and dev not in self.s.clients:
            return self.tell(f"{dev} 연결이 없어요")
        if dev in self.players:
            return
        events = self.only_device(events, dev)
        st = {"stop": threading.Event(), "round": 0}
        st["thread"] = threading.Thread(target=self._play, args=(events, dev, st), daemon=True)
        self.players[dev] = st
        self.last_dev = dev
        st["thread"].start()
        self.changed()

    @staticmethod
    def only_device(events, dev):
        """그 기기에서 한 동작만 남김. 녹화 중에 마우스가 메인 PC 등 다른 기기로 넘어갔던 부분은 뺌
        (예전엔 이것 때문에 서브 PC 매크로가 메인 PC 마우스를 움직이고 화면을 넘겨 버렸음)"""
        want = None if dev == MAIN_DEV else dev
        out, cur, first = [], "__none__", True
        for e in events:
            if e[1] == "dev":
                cur = e[2]
                if cur == want and (first or out):
                    out.append(e)
                first = False
                continue
            if cur == want:
                out.append(e)
        return out if len(out) > 1 else events if want is None else out

    @property
    def playing(self):
        return bool(self.players)

    def stop_play(self, dev=None):
        targets = [dev] if dev else list(self.players)
        for d in targets:
            st = self.players.get(d)
            if st:
                st["stop"].set()
        for d in targets:
            st = self.players.get(d)
            if st and st["thread"] is not threading.current_thread():
                st["thread"].join(timeout=2)

    def _play(self, events, dev, st):
        rep = int(self.s.cfg.get("macro_repeat", 1))
        held = {}
        stop = st["stop"]
        self.click_logs = 0
        who = "" if dev == MAIN_DEV else f"{dev} · "
        self.tell(who + "실행 시작" + (" · 멈출 때까지 반복" if rep <= 0 else (f" · {rep}번" if rep > 1 else "")))
        stopped = False
        try:
            while rep <= 0 or st["round"] < rep:
                st["round"] += 1
                start = time.perf_counter()
                cur = None
                for e in events:
                    while True:
                        left = start + e[0] - time.perf_counter()
                        if left <= 0:
                            break
                        if stop.wait(min(left, 0.02)):
                            stopped = True
                            return
                    if stop.is_set():
                        stopped = True
                        return
                    cur = self._do(e, cur, held)
                    if cur is False:
                        return
                if rep <= 0 or st["round"] < rep:
                    if stop.wait(0.3):
                        stopped = True
                        return
        except Exception as ex:
            print(f"[매크로] 실행 오류: {ex}")
        finally:
            for (d, kind, k), down in held.items():
                if down:
                    try:
                        self._send(d, kind, k, False)
                    except Exception:
                        pass
            self.players.pop(dev, None)
            with self.s.lock:          # 끝나면 사용자 마우스 위치를 매크로가 남긴 위치로 맞춤
                p = self.pos.get(dev)
                c = self._client(dev)
                if p and c is not None and self.s.active == dev:
                    self.s.rx, self.s.ry = min(max(p[0], 0), c.w - 1), min(max(p[1], 0), c.h - 1)
            self.tell(who + ("실행 멈춤" if stopped else "실행 끝"))
            self.changed()

    def from_app(self, client, msg):
        """기기 앱에서 온 매크로 명령 (그 기기 것만 다룸)"""
        op = msg.get("op")
        dev = client.name
        if op == "state":
            return self.s.push_macro_state(client)
        if op == "record":
            if self.recording:
                return self.stop_record(trim=float(msg.get("trim", 0)))
            time.sleep(float(msg.get("delay", 0)))      # 앱이 내려가고 이전 화면이 나올 시간
            return self.toggle_record(dev)
        if op == "play":
            if self.recording:
                self.stop_record(trim=float(msg.get("trim", 0)))
            if dev in self.players:
                return self.stop_play(dev)
            time.sleep(float(msg.get("delay", 0)))
            return self.toggle_play(dev, explicit=True)
        if op == "stop":
            if self.recording:
                self.stop_record(trim=float(msg.get("trim", 0)))
            return self.stop_play(dev)
        r = self.command(dev, op, msg)
        if r and r != "ok":
            client.send({"t": "toast", "text": r})

    def command(self, dev, op, msg):
        if op == "save":
            return self.save_as(dev, msg.get("name", ""))
        if op == "load":
            return self.load(dev, msg.get("name", ""))
        if op == "delete":
            return self.delete(dev, msg.get("name", ""))
        if op == "rename":
            return self.rename(dev, msg.get("name", ""), msg.get("new", ""))
        if op == "clear":
            return self.clear(dev)
        if op == "delete_all":
            return self.delete_all(dev)
        if op == "slot":
            return self.set_slot(dev, msg.get("name", ""), msg.get("slot", ""))
        if op == "switch":
            return self.switch(dev)
        if op == "capture":
            which = msg.get("which")
            if which in ("dplay", "dswitch"):           # 기기 앱에서 자기 전용 키 정하기
                which = f"{which}:{dev}"
            return self.start_capture(which) and "ok"
        if op == "folder":
            return self.set_folder(dev, msg.get("name", ""), msg.get("folder", ""))
        if op == "rename_folder":
            return self.rename_folder(dev, msg.get("name", ""), msg.get("new", ""))
        if op == "toggle_keys":
            return self.toggle_keys() or "ok"
        if op == "devkey_clear":
            return self.clear_devkey(dev, msg.get("which", "play"))
        if op == "repeat":
            self.s.cfg["macro_repeat"] = max(0, min(9999, int(msg.get("value", 1))))
            self.s.save()
            self.changed()
            return "ok"
        return None

    def _client(self, dev):
        return self.s.clients.get(dev) if dev else None

    def _send(self, dev, kind, k, d):
        if dev is None:
            (local_button if kind == "btn" else local_key)(k, d)
            return
        c = self._client(dev)
        if c is None:
            return
        if kind == "btn":
            c.send({"t": "btn", "b": k, "d": d})
        else:
            c.send({"t": "key", "vk": k, "d": d})

    def _do(self, e, cur, held):
        kind = e[1]
        s = self.s
        if kind == "dev":
            # 마우스를 그 기기로 데려가지 않고 그 기기에만 직접 보냄
            # → 실행 중에 마우스를 메인 PC(나 다른 기기)로 옮겨도 매크로는 원래 기기에서 계속 돌아감
            name, x, y = e[2], e[3], e[4]
            if name is None:
                if s.active is None:
                    local_move(x, y)
                return None
            c = s.clients.get(name)
            if c is None:
                self.tell(f"{name} 연결이 없어서 멈췄어요")
                return False
            self.pos[name] = [x, y]
            if getattr(c, "hid_on", False):
                c.send({"t": "warp", "x": x, "y": y})
            elif not getattr(c, "relative", False):
                c.send({"t": "move", "x": x, "y": y})
            return name
        if kind == "lm":
            if cur is None:
                local_move(e[2], e[3])
            return cur
        if kind in ("btn", "key"):
            held[(cur, kind, e[2])] = e[3]
            if kind == "btn" and e[3] and cur:
                c = self._client(cur)
                if c is not None and getattr(c, "hid_on", False):
                    # 진짜 마우스 모드: 누르기 직전에 커서를 녹화 때 위치로 다시 맞춤
                    # (실행 중에 마우스를 움직였거나 가속 때문에 조금씩 어긋나도 클릭 위치는 정확하게)
                    x, y = self.pos.get(cur, [c.w / 2, c.h / 2])
                    c.send({"t": "warp", "x": int(x), "y": int(y)})
                    if self.click_logs < 6:
                        self.click_logs += 1
                        print(f"[매크로] {cur} 클릭 위치 맞춤 ({int(x)}, {int(y)})")
            if kind == "btn" and cur:
                c = self._client(cur)
                if c is not None and not getattr(c, "hid_on", False) and not getattr(c, "relative", False):
                    # 서브 PC 등: 누르고 뗄 때마다 녹화 때 위치로 다시 옮김 (실행 중에 마우스를 움직여도 엉뚱한 곳을 누르지 않게)
                    x, y = self.pos.get(cur, [c.w / 2, c.h / 2])
                    c.send({"t": "move", "x": int(x), "y": int(y)})
            self._send(cur, kind, e[2], e[3])
            return cur
        if kind == "wh":
            if cur is None:
                local_wheel(e[2], e[3])
            elif self._client(cur):
                self._client(cur).send({"t": "wheel", "dx": e[2], "dy": e[3]})
            return cur
        c = self._client(cur)
        if c is None:
            return cur
        pos = self.pos.setdefault(cur, [c.w / 2, c.h / 2])
        if kind == "rm":
            pos[0] = min(max(pos[0] + e[2], 0), c.w - 1)
            pos[1] = min(max(pos[1] + e[3], 0), c.h - 1)
            c.send({"t": "rmove", "dx": e[2], "dy": e[3]})
        elif kind == "mv":
            pos[0], pos[1] = e[2], e[3]
            c.send({"t": "move", "x": e[2], "y": e[3]})
        # 실행 중에는 사용자 마우스 위치(s.rx)를 건드리지 않음 → 실행 중에 마우스를 움직여도 화면 끝을 넘거나 튀지 않음
        return cur


class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))  # 실제로 패킷은 보내지 않음
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


# ---------------------------------------------------------------- 배치 계산
def side_of(r, vw, vh):
    if r["x"] + r["w"] <= 0:
        return "left"
    if r["x"] >= vw:
        return "right"
    if r["y"] + r["h"] <= 0:
        return "top"
    if r["y"] >= vh:
        return "bottom"
    return None


def snap(r, vw, vh, prefer=None):
    """상자를 윈도우 화면의 가장 가까운 변에 붙이고, 최소 25%는 맞닿게 함"""
    w, h = r["w"], r["h"]
    my, mx = min(h, vh) * 0.25, min(w, vw) * 0.25

    def clamp(v, lo, hi):
        return max(lo, min(hi, v))

    def align(v, size, full):  # 위/아래(왼/오른) 끝이나 가운데에 가까우면 딱 맞춤
        for target in (0, full - size, (full - size) / 2):
            if abs(v - target) < full * 0.04:
                return target
        return v

    ys = align(clamp(r["y"], -h + my, vh - my), h, vh)
    xs = align(clamp(r["x"], -w + mx, vw - mx), w, vw)
    cands = {
        "left": (-w, ys), "right": (vw, ys),
        "top": (xs, -h), "bottom": (xs, vh),
    }
    if prefer in cands:
        x, y = cands[prefer]
    else:
        x, y = min(cands.values(), key=lambda p: (p[0] - r["x"]) ** 2 + (p[1] - r["y"]) ** 2)
    return {"x": round(x), "y": round(y), "w": round(w), "h": round(h)}


def fit_aspect(r, cw, ch):
    """기기 화면 비율에 맞게 상자 모양 조정 (긴 변 길이는 유지)"""
    L = max(r["w"], r["h"])
    if cw >= ch:
        return {**r, "w": L, "h": L * ch / cw}
    return {**r, "h": L, "w": L * cw / ch}


# ---------------------------------------------------------------- 클라이언트 연결
class Client:
    def __init__(self, sock, addr, name, w, h, server):
        self.sock, self.addr, self.name = sock, addr, name
        self.w, self.h = max(1, int(w)), max(1, int(h))
        self.server = server
        self.q = queue.Queue()
        self.mlock = threading.Lock()
        self.pending_move = None
        self.pending_rmove = None
        self.alive = True
        threading.Thread(target=self._sender, daemon=True).start()

    _MOVE = object()   # "최신 이동 좌표를 보내라" 표시
    _RMOVE = object()  # "쌓인 상대 이동량을 보내라" 표시

    def send(self, obj):
        if not self.alive:
            return
        if isinstance(obj, (bytes, bytearray)):
            self.q.put(obj)
            return
        if obj.get("t") == "rmove":
            with self.mlock:
                if self.pending_rmove is not None:
                    self.pending_rmove["dx"] += obj["dx"]
                    self.pending_rmove["dy"] += obj["dy"]
                    return
                self.pending_rmove = dict(obj)
            self.q.put(Client._RMOVE)
            return
        if obj.get("t") == "move":
            # 네트워크가 밀리면 중간 좌표는 버리고 가장 최근 위치만 보냄 → 지연이 쌓이지 않음
            with self.mlock:
                queued = self.pending_move is not None
                self.pending_move = obj
            if not queued:
                self.q.put(Client._MOVE)
        else:
            self.q.put(obj)

    def _sender(self):
        try:
            while self.alive:
                obj = self.q.get()
                if obj is None:
                    break
                if obj is Client._RMOVE:
                    with self.mlock:
                        obj, self.pending_rmove = self.pending_rmove, None
                    if obj is None:
                        continue
                elif obj is Client._MOVE:
                    with self.mlock:
                        obj, self.pending_move = self.pending_move, None
                    if obj is None:
                        continue
                if isinstance(obj, (bytes, bytearray)):
                    self.sock.sendall(obj)          # 미리 만들어 둔 큰 메시지 (앱·exe 파일)
                else:
                    self.sock.sendall((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))
        except OSError:
            pass
        self.close()

    def close(self):
        if not self.alive:
            return
        self.alive = False
        self.q.put(None)
        try:
            self.sock.close()
        except OSError:
            pass
        self.server.on_disconnect(self)


# ---------------------------------------------------------------- 서버 본체
class Server:
    def __init__(self, cfg):
        self.cfg = cfg
        self.layout = cfg["layout"]
        self.clients = {}
        self.lock = threading.RLock()
        self.active = None          # None = 윈도우 로컬, 아니면 클라이언트 이름
        self.rx = self.ry = 0       # 원격 화면에서의 커서 좌표
        self.local_buttons = set()
        self.key_logs = 0
        self.local_keys = set()     # 윈도우에서 눌린 채로 넘어간 키 (뗄 때는 윈도우로 통과)
        self.last_clip = None
        self.port_error = False
        self.adb = None                # AdbManager (설정 창과 함께 시작)
        self.last_hook = time.time()
        self.apk_status = ""
        self.mouse_listener = None
        self.key_listener = None
        self.mods_down = set()        # 눌린 Ctrl·Alt (복귀 단축키 조합 확인용)
        self.macro_keys_down = set()
        self.notify_cb = None         # 트레이 알림 (설정 창이 켜지면 연결)
        self.overlay = None           # 화면 위 매크로 이름 표시
        self.bat_overlay = None       # 화면 위 기기 배터리 표시
        cfg.setdefault("macro_repeat", 1)
        if "macro_rec_vk" not in cfg:
            i = int(cfg.get("macro_keys", 0))
            cfg["macro_rec_vk"], cfg["macro_play_vk"] = MACRO_KEYSETS[i if 0 <= i < len(MACRO_KEYSETS) else 0]
        if int(cfg.get("return_hotkey_vk", 0x91)) in (cfg["macro_rec_vk"], cfg["macro_play_vk"]):
            cfg["return_hotkey_vk"] = COMBO | 0x24     # 매크로 키와 겹치면 Ctrl+Alt+Home으로
            print("[설정] 복귀 단축키가 매크로 키와 겹쳐서 Ctrl+Alt+Home으로 바꿨어요")
        self.macro = Macro(self)

    # ---------- 연결 프로필 (집·회사 등에서 쓰던 배치·비밀번호를 저장했다가 불러오기)
    PROFILE_KEYS = ("key", "layout", "return_hotkey_vk", "mouse_speed", "clipboard")

    def profile_info(self):
        with self.lock:
            return json.loads(json.dumps({k: self.cfg[k] for k in self.PROFILE_KEYS if k in self.cfg}))

    def apply_profile(self, info):
        with self.lock:
            for k in self.PROFILE_KEYS:
                if k in info:
                    self.cfg[k] = json.loads(json.dumps(info[k]))
            self.layout = self.cfg["layout"] = dict(self.cfg.get("layout") or {})
            names = list(self.clients)
        self.save()
        for n in names:                    # 연결된 기기에 새 위치 알림
            with self.lock:
                c = self.clients.get(n)
            if c:
                c.send({"t": "layout", "side": self.side_name(n)})

    def on_battery(self, client, level, charging):
        """배터리 잔량 기록 · 20%·10% 아래로 내려가면 한 번씩 알림"""
        try:
            level = int(level)
        except (TypeError, ValueError):
            return
        prev = getattr(client, "battery", None)
        if prev and abs(prev[0] - level) >= 10:      # 30초 사이 10% 넘게 바뀌면 이상한 값 → 기록 (원인 확인용)
            print(f"[배터리] {client.name} ({client.addr[0]}) {prev[0]}% → {level}% · 충전 {'중' if charging else '아님'}")
        client.battery = (level, charging)
        if prev != client.battery:
            threading.Thread(target=self.update_battery_overlay, daemon=True).start()
        warned = getattr(client, "bat_warned", 100)
        if charging or level > 25:
            client.bat_warned = 100
            return
        for th in (20, 10):
            if level <= th < warned:
                client.bat_warned = th
                text = f"{client.name} 배터리 {level}% · 충전이 필요해요"
                print(f"[배터리] {text}")
                if self.notify_cb:
                    try:
                        self.notify_cb(text)
                    except Exception:
                        pass
                break

    def update_battery_overlay(self):
        """메인 PC 화면 위에 기기 배터리 표시 (설정에서 켜고 위치를 고름)"""
        with self.lock:
            bats = [(c.name, c.battery) for c in self.clients.values() if getattr(c, "battery", None)]
        pos = self.cfg.get("battery_pos", "tr")
        on = bool(self.cfg.get("battery_overlay", True)) and bool(bats)
        text = "   ".join(f"{n} {lv}%{'⚡' if chg else ''}" for n, (lv, chg) in sorted(bats))
        low = any(lv <= 20 and not chg for _, (lv, chg) in bats)
        same = self.cfg.get("macro_overlay", True) and self.cfg.get("overlay_pos", "tl") == pos
        if not on:
            if self.bat_overlay:
                self.bat_overlay.show("")
            return
        try:
            if self.bat_overlay is None:
                self.bat_overlay = MacroOverlay()
            self.bat_overlay.show("🔋  " + text, "rec" if low else "idle", pos, 36 if same else 0)
        except Exception as e:
            print(f"[배터리 표시] 오류: {e}")

    def display_name(self):
        return self.cfg.get("server_name") or socket.gethostname()

    def rename_device(self, old, new):
        """연결된 기기 이름 바꾸기: 그 기기에 새 이름을 알려 주고, 배치·전용 키는 새 이름으로 옮김"""
        new = str(new).strip()[:30]
        if not new or new == old:
            return "새 이름을 입력하세요"
        with self.lock:
            c = self.clients.get(old)
            if c is None:
                return "연결된 기기만 이름을 바꿀 수 있어요"
            if new in self.clients or new == MAIN_DEV:
                return "같은 이름의 기기가 이미 있어요"
            lay = self.cfg.setdefault("layout", {})
            if old in lay:
                lay[new] = lay.pop(old)
            dk = self.cfg.get("macro_dev_keys", {})
            if old in dk:
                dk[new] = dk.pop(old)
        self.save()
        print(f"[이름] {old} → {new}")
        c.send({"t": "rename", "name": new})      # 기기가 저장하고 새 이름으로 다시 연결
        return "ok"

    def push_macro_state(self, client=None):
        """매크로 상태를 기기 앱에 보냄 (그 기기에서 녹화한 것만 목록에)"""
        with self.lock:
            targets = [client] if client else [c for c in self.clients.values()
                                               if getattr(c, "platform", "") in ("android", "windows-exe", "windows-embedded")]
        try:      # 메인 PC 화면 위 표시 (메인 PC에서 녹화한 매크로)
            if self.overlay is None:
                self.overlay = MacroOverlay()
            ms = self.macro.state(MAIN_DEV)
            self.overlay.show(*overlay_text(ms), overlay_pos(ms))
        except Exception as e:
            print(f"[매크로 표시] 오류: {e}")
        for c in targets:
            m = self.macro.state(c.name)
            m["here"] = self.active == c.name
            c.send({"t": "macro_state", **m})

    def notify(self, text):
        """매크로 상태 안내: 마우스가 있는 기기 화면에 잠깐 표시"""
        with self.lock:
            c = self.clients.get(self.active) if self.active else None
        if c is not None:
            c.send({"t": "toast", "text": text})
        elif self.notify_cb:
            try:
                self.notify_cb(text)
            except Exception:
                pass

    # ---------- 배치
    def place_client(self, name, cw, ch):
        """처음 보는 기기는 빈 쪽에 자동 배치, 아는 기기는 화면 비율만 맞춤"""
        _, _, vw, vh = virtual_screen()
        with self.lock:
            r = self.layout.get(name) or {}
            if "w" not in r:
                hint = r.get("side_hint")
                used = {side_of(v, vw, vh) for v in self.layout.values() if "w" in v}
                if hint is None:
                    hint = next((s for s in ("right", "left", "top", "bottom") if s not in used), "right")
                h = vh if cw >= ch else vh * 0.6
                r = snap({"x": 0, "y": (vh - h) / 2, "w": h * cw / ch, "h": h}, vw, vh, prefer=hint)
            elif abs(r["w"] / r["h"] - cw / ch) > 0.05:
                r = snap(fit_aspect(r, cw, ch), vw, vh, prefer=side_of(r, vw, vh))
            else:
                return
            self.layout[name] = r
        self.save()

    def set_rect(self, name, r):
        with self.lock:
            self.layout[name] = r
            c = self.clients.get(name)
        self.save()
        if c:
            c.send({"t": "layout", "side": self.side_name(name)})

    def save(self):
        with self.lock:   # 훅을 오래 막지 않도록 복사만 잠금 안에서
            snapshot = json.loads(json.dumps(self.cfg))
        save_config(snapshot)

    def layout_snapshot(self):
        with self.lock:
            return {k: dict(v) for k, v in self.layout.items()}

    # ---------- 네트워크
    def discovery(self):
        """서브 PC·태블릿이 같은 와이파이에서 이 서버를 찾을 수 있게 응답"""
        try:
            u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            u.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            u.bind(("0.0.0.0", self.cfg["port"]))
        except OSError as e:
            print(f"[자동 찾기] 사용할 수 없음: {e}")
            return
        info = json.dumps({"kvm": 1, "host": self.display_name(), "port": self.cfg["port"],
                           "version": VERSION}).encode("utf-8")
        while True:
            try:
                data, addr = u.recvfrom(256)
                if data.startswith(b"KVM-DISCOVER"):
                    u.sendto(info, addr)
            except OSError:
                time.sleep(1)

    def serve(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        # 윈도우의 SO_REUSEADDR는 같은 포트를 여러 프로그램이 동시에 쓰게 해서
        # 예전 서버가 남아 있으면 연결을 가로챔 → 독점 모드로 열고, 이미 쓰는 중이면 알림
        srv.setsockopt(socket.SOL_SOCKET, getattr(socket, "SO_EXCLUSIVEADDRUSE", socket.SO_REUSEADDR), 1)
        for attempt in range(15):
            try:
                srv.bind(("0.0.0.0", self.cfg["port"]))
                break
            except OSError:
                time.sleep(1)
        else:
            print(f"[오류] {self.cfg['port']} 포트를 다른 프로그램이 쓰고 있습니다. "
                  "트레이에 남은 KVM 서버(KVM-Server.exe)를 종료한 뒤 다시 실행하세요.")
            self.port_error = True
            return
        srv.listen(8)
        print(f"\n[서버 v{VERSION}] {time.strftime('%Y-%m-%d %H:%M:%S')}  {lan_ip()}:{self.cfg['port']} 에서 대기 중 (이 IP를 맥/안드로이드에 입력)")
        while True:
            sock, addr = srv.accept()
            print(f"[접속] {addr[0]}")
            threading.Thread(target=self._handle, args=(sock, addr), daemon=True).start()

    def _handle(self, sock, addr):
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            sock.ioctl(socket.SIO_KEEPALIVE_VALS, (1, 3000, 1000))   # 3초 조용하면 1초 간격으로 생존 확인
        except (AttributeError, OSError):
            pass
        f = sock.makefile("r", encoding="utf-8")
        client = None
        try:
            hello = json.loads(f.readline() or "{}")
            name = str(hello.get("name", "")).strip()
            if hello.get("t") != "hello" or hello.get("key") != self.cfg["key"] or not name:
                sock.sendall('{"t":"error","msg":"비밀번호가 다릅니다"}\n'.encode("utf-8"))
                print(f"[거부] {addr[0]} '{name}' - 비밀번호 불일치 (받은 값 {len(str(hello.get('key','')))}자)")
                sock.close()
                return
            client = Client(sock, addr, name, hello.get("w", 1920), hello.get("h", 1080), self)
            client.platform = str(hello.get("platform", ""))
            client.version = str(hello.get("version", ""))
            client.relative = client.platform == "iphone"
            self.place_client(name, client.w, client.h)
            with self.lock:
                old = self.clients.get(name)
                self.clients[name] = client
            if old:
                old.close()
            print(f"[연결] {name} ({addr[0]}) {client.w}x{client.h}")
            self.send_welcome(client)
            self.broadcast_peers()
            if client.platform == "android":
                self.announce_apk(client)
            elif client.platform in CLIENT_FILES:
                self.announce_client_code(client)
            elif client.platform == "windows-exe":
                self.announce_client_exe(client)
            for line in f:
                msg = json.loads(line)
                t = msg.get("t")
                if t == "size":
                    client.w, client.h = max(1, int(msg["w"])), max(1, int(msg["h"]))
                    self.place_client(name, client.w, client.h)   # 폰 회전 시 상자도 회전
                elif t == "log":
                    print(f"[기기 기록] {client.name}: {str(msg.get('msg', ''))[:3000]}")
                elif t == "hid_state":
                    client.hid_on = bool(msg.get("on"))
                    print(f"[진짜 마우스] {client.name}: {'켜짐' if client.hid_on else '꺼짐'}")
                    if not client.hid_on and self.cfg.get("adb_paired") and self.adb:
                        self.adb.ensure_async(client)
                elif t == "pong":
                    try:
                        client.rtt = max(0.0, (time.perf_counter() - float(msg["ts"])) * 1000)
                    except (KeyError, ValueError):
                        pass
                    if "bat" in msg:
                        self.on_battery(client, msg.get("bat"), bool(msg.get("chg")))
                elif t == "exe_get":
                    threading.Thread(target=self.send_client_exe, args=(client,), daemon=True).start()
                elif t == "file_get":
                    self.send_client_code(client, msg.get("path", ""))
                elif t == "apk_get":
                    threading.Thread(target=self.send_apk, args=(client,), daemon=True).start()
                elif t == "macro_lib":       # 기기가 보관 중인 자기 매크로
                    threading.Thread(target=self.macro.device_upload, args=(client, msg.get("data")),
                                     daemon=True).start()
                elif t == "macro":
                    threading.Thread(target=self.macro.from_app, args=(client, msg), daemon=True).start()
                elif t == "clip":
                    text = msg.get("text", "")
                    if (self.cfg.get("clipboard", True) and text and text != self.last_clip
                            and len(text) < 1_000_000):
                        self.last_clip = text
                        pyperclip.copy(text)
        except (OSError, ValueError) as e:
            print(f"[끊김] {addr[0]} - {e}")
        except Exception as e:
            print(f"[오류] {addr[0]} - {type(e).__name__}: {e}")
        finally:
            if client:
                client.close()

    def on_disconnect(self, client):
        with self.lock:
            if self.clients.get(client.name) is client:
                del self.clients[client.name]
                print(f"[해제] {client.name}")
                self.broadcast_peers()
                if self.active == client.name:
                    self.go_local()
                gone = True
            else:
                gone = False
        if gone:
            self.macro.device_gone(client.name)     # 그 기기 매크로는 그 기기에만 남음
            threading.Thread(target=self.update_battery_overlay, daemon=True).start()

    def connected_names(self):
        with self.lock:
            return set(self.clients)

    # ---------- 기기 화면에 보여줄 연결 정보
    def side_name(self, name):
        r = self.layout.get(name)
        if not r or "w" not in r:
            return ""
        _, _, vw, vh = virtual_screen()
        return side_of(r, vw, vh) or ""

    def send_welcome(self, client):
        client.send({"t": "welcome", "server": self.display_name(), "version": VERSION,
                     "side": self.side_name(client.name)})

    def broadcast_peers(self):
        with self.lock:
            names = sorted(self.clients)
            targets = list(self.clients.values())
        for c in targets:
            c.send({"t": "peers", "names": [n for n in names if n != c.name]})

    # ---------- 맥·서브 PC 클라이언트 자동 업데이트 (파일 내용이 다르면 클라이언트가 받아 감)
    def announce_client_code(self, client):
        rel = CLIENT_FILES[client.platform]
        try:
            with open(os.path.join(ROOT, *rel.split("/")), "rb") as f:
                h = hashlib.sha256(f.read()).hexdigest()
        except OSError:
            return
        client.send({"t": "client_hash", "hash": h})

    def send_client_code(self, client, rel):
        if rel not in CLIENT_FILES.values():        # 정해진 파일만 보냄
            return
        with open(os.path.join(ROOT, *rel.split("/")), "rb") as f:
            data = base64.b64encode(f.read()).decode("ascii")
        print(f"[업데이트] {client.name} ← {rel}")
        client.send({"t": "file_data", "b64": data})

    def client_exe_version(self):
        exe, ver = os.path.join(ROOT, "KVM-Client.exe"), os.path.join(ROOT, "KVM-Client.version")
        if not (os.path.exists(exe) and os.path.exists(ver)):
            return None
        with open(ver, encoding="utf-8") as f:
            return f.read().strip()

    def announce_client_exe(self, client=None):
        v = self.client_exe_version()
        print(f"[업데이트 알림] 서브 PC용 exe v{v}")
        if not v:
            return
        with self.lock:
            targets = [client] if client else [c for c in self.clients.values()
                                               if getattr(c, "platform", "") == "windows-exe"]
        for c in targets:
            c.send({"t": "exe_info", "version": v})

    def send_client_exe(self, client):
        print(f"[업데이트 요청] {client.name}")
        msg = file_message("exe_data", os.path.join(ROOT, "KVM-Client.exe"))
        print(f"[업데이트] {client.name} ← KVM-Client.exe ({len(msg) * 3 // 4 // 1048576} MB)")
        client.send(msg)

    def announce_client_code_all(self):
        with self.lock:
            targets = [c for c in self.clients.values() if getattr(c, "platform", "") in CLIENT_FILES]
        for c in targets:
            self.announce_client_code(c)

    # ---------- 태블릿 앱 자동 업데이트
    def apk_paths(self):
        return os.path.join(ROOT, "KVM-Android.apk"), os.path.join(ROOT, "KVM-Android.version")

    def apk_version(self):
        apk, ver = self.apk_paths()
        if not (os.path.exists(apk) and os.path.exists(ver)):
            return None
        with open(ver, encoding="utf-8") as f:
            return f.read().strip()

    def announce_apk(self, only=None):
        v = self.apk_version()
        if not v:
            return
        t = vtuple(v) + (0, 0, 0)
        code = t[0] * 10000 + t[1] * 100 + t[2]
        with self.lock:
            targets = [only] if only else [c for c in self.clients.values() if getattr(c, "platform", "") == "android"]
        for c in targets:
            c.send({"t": "apk_info", "name": v, "code": code})
            # 무선 디버깅이 연결돼 있으면 태블릿에서 아무것도 누르지 않아도 바로 설치
            cv = getattr(c, "version", "")
            if cv and vtuple(cv) < vtuple(v) and getattr(self, "adb", None):
                threading.Thread(target=self.adb.install_apk, args=(v, c.addr[0]), daemon=True).start()

    def send_apk(self, client):
        apk, _ = self.apk_paths()
        try:
            msg = file_message("apk_data", apk)
            print(f"[앱 전송] {client.name} ← KVM-Android.apk ({len(msg) * 3 // 4 // 1024} KB)")
            client.send(msg)
        except OSError as e:
            client.send({"t": "apk_error", "msg": str(e)})

    def build_apk_async(self):
        """빌드 도구가 설치돼 있으면 백그라운드에서 apk를 다시 만들고, 끝나면 태블릿에 알림"""
        if self.apk_status.endswith("중…"):
            return False
        sdk = os.path.join(os.environ.get("LOCALAPPDATA", ""), "KVM-build", "sdk")
        if not os.path.isdir(sdk):
            self.apk_status = "빌드 도구가 없어요 · apk만들기.bat을 한 번 실행하세요"
            return False

        def work():
            self.apk_status = "태블릿 앱 만드는 중…"
            print("[앱 빌드] 시작")
            env = {k: v for k, v in os.environ.items() if not k.startswith(("_PYI", "_MEIPASS"))}
            ok = False
            for attempt in (1, 2):
                log = open(os.path.join(ROOT, "kvm-update.log"), "a", encoding="utf-8")
                log.write(f"\n[{time.strftime('%Y-%m-%d %H:%M:%S')}] 태블릿 앱 빌드 ({attempt}번째)\n")
                log.flush()
                p = subprocess.Popen(
                    ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                     os.path.join(ROOT, "tools", "build_apk.ps1"), "-Target", ROOT],
                    stdout=log, stderr=log, creationflags=0x08000000, env=env)
                try:
                    ok = p.wait(timeout=420) == 0         # 보통 1~2분. 7분이 넘으면 멈춘 것
                except subprocess.TimeoutExpired:
                    print(f"[앱 빌드] {attempt}번째 시도가 멈춰서 중단하고 다시 시도")
                    subprocess.run(["taskkill", "/T", "/F", "/PID", str(p.pid)], capture_output=True,
                                   creationflags=0x08000000)
                    self.apk_status = "태블릿 앱 만드는 중… (다시 시도)"
                log.close()
                if ok:
                    break

            class _R:
                returncode = 0 if ok else 1
            r = _R()
            if r.returncode == 0:
                self.cfg["apk_outdated"] = False
                self.save()
                self.apk_status = f"태블릿 앱 v{self.apk_version()} 준비됨 · 태블릿에 업데이트 알림을 보냈어요"
                print("[앱 빌드] 완료")
                self.announce_apk()
            else:
                self.apk_status = "태블릿 앱 빌드 실패 · kvm-update.log를 확인하세요"
                print("[앱 빌드] 실패")
        threading.Thread(target=work, daemon=True).start()
        return True

    def client_versions(self):
        with self.lock:
            return {n: getattr(c, "version", "") for n, c in self.clients.items()}

    def client_sizes(self):
        with self.lock:
            return {n: (c.w, c.h) for n, c in self.clients.items()}

    # ---------- 화면 전환
    def go_remote(self, name, rx, ry):
        c = self.clients[name]
        if getattr(c, "relative", False):
            rx, ry = c.w / 2, c.h / 2        # 위치를 모르는 기기는 화면 가운데에서 시작한 것으로 침
        print(f"[전환] → {name}")
        self.key_logs = 0
        self.active, self.rx, self.ry = name, rx, ry
        self.macro.rec("dev", name, int(rx), int(ry))
        user32.SetCursorPos(*primary_center())
        c.send({"t": "enter", "x": int(rx), "y": int(ry)})
        if self.cfg.get("clipboard", True):
            threading.Thread(target=self._push_clip, args=(c,), daemon=True).start()

    def _push_clip(self, c):
        try:
            text = pyperclip.paste()
        except Exception:
            return
        if text and text != self.last_clip and len(text) < 1_000_000:
            self.last_clip = text
            c.send({"t": "clip", "text": text})

    def go_local(self, x=None, y=None):
        name = self.active
        self.active = None
        c = self.clients.get(name) if name else None
        if c:
            # 매크로가 그 기기에서 실행 중이면 눌린 키·버튼을 떼지 않게 (매크로가 계속 쓰는 중)
            c.send({"t": "leave", "keep": name in self.macro.players})
        if x is None:
            x, y = primary_center()
        user32.SetCursorPos(int(x), int(y))
        if name:
            self.macro.rec("dev", None, int(x), int(y))

    def check_edge(self, x, y):
        """윈도우 화면 가장자리에서, 그 지점에 맞닿은 기기가 있으면 넘어감"""
        l, t, vw, vh = virtual_screen()
        px, py = x - l, y - t
        for name, c in self.clients.items():
            r = self.layout.get(name)
            if not r or "w" not in r:
                continue
            side = side_of(r, vw, vh)
            in_y = r["y"] <= py < r["y"] + r["h"]
            in_x = r["x"] <= px < r["x"] + r["w"]
            fy = (py - r["y"]) / r["h"] * c.h
            fx = (px - r["x"]) / r["w"] * c.w
            if side == "left" and px <= 0 and in_y:
                self.go_remote(name, c.w - 2, fy)
            elif side == "right" and px >= vw - 1 and in_y:
                self.go_remote(name, 1, fy)
            elif side == "top" and py <= 0 and in_x:
                self.go_remote(name, fx, c.h - 2)
            elif side == "bottom" and py >= vh - 1 and in_x:
                self.go_remote(name, fx, 1)
            else:
                continue
            return True
        return False

    def remote_move(self, c, dx, dy):
        """원격 화면에서 이동. 윈도우와 맞닿은 구간으로 나가면 복귀, 아니면 벽처럼 막힘"""
        l, t, vw, vh = virtual_screen()
        r = self.layout.get(c.name)
        # 배치 상자 1픽셀 = 기기 화면 몇 픽셀인지 → 고해상도 태블릿에서도 윈도우와 같은 체감 속도
        f = (c.w / r["w"]) if r and r.get("w") else 1.0
        f *= float(self.cfg.get("mouse_speed", 1.0))
        self.rx += dx * f
        self.ry += dy * f
        if is_relative(c):
            # 아이폰·진짜 마우스 모드 태블릿처럼 커서 위치를 직접 정할 수 없는 기기는 움직임을 전부 전달
            c.send({"t": "rmove", "dx": dx * f, "dy": dy * f})
            self.macro.rec("rm", round(dx * f, 2), round(dy * f, 2))
        if r and "w" in r:
            side = side_of(r, vw, vh)
            wy = r["y"] + self.ry / c.h * r["h"]   # 윈도우 좌표로 환산
            wx = r["x"] + self.rx / c.w * r["w"]
            if side == "left" and self.rx >= c.w and 0 <= wy < vh:
                return self.go_local(l + 2, t + wy)
            if side == "right" and self.rx < 0 and 0 <= wy < vh:
                return self.go_local(l + vw - 3, t + wy)
            if side == "top" and self.ry >= c.h and 0 <= wx < vw:
                return self.go_local(l + wx, t + 2)
            if side == "bottom" and self.ry < 0 and 0 <= wx < vw:
                return self.go_local(l + wx, t + vh - 3)
        self.rx = min(max(self.rx, 0), c.w - 1)
        self.ry = min(max(self.ry, 0), c.h - 1)
        if not is_relative(c):
            c.send({"t": "move", "x": int(self.rx), "y": int(self.ry)})
            self.macro.rec("mv", int(self.rx), int(self.ry))

    # ---------- 마우스 훅 (가볍게 유지해야 함)
    def mouse_filter(self, msg, data):
        self.last_hook = time.time()
        if getattr(data, "dwExtraInfo", 0) == MACRO_MAGIC:
            return                                   # 매크로가 넣은 입력은 그대로 통과
        # 노트북 터치패드 드라이버는 입력을 '주입된 이벤트'로 보내는 경우가 많아서 걸러내지 않음
        with self.lock:
            if self.active is None:
                m = self.macro
                if m.recording:
                    if msg == WM_MOUSEMOVE:
                        m.rec("lm", data.pt.x, data.pt.y)
                    elif msg in BTN_MSG:
                        m.rec("btn", *BTN_MSG[msg])
                    elif msg in (WM_XBUTTONDOWN, WM_XBUTTONUP):
                        m.rec("btn", "x1" if hiword_signed(data.mouseData) == 1 else "x2", msg == WM_XBUTTONDOWN)
                    elif msg == WM_MOUSEWHEEL:
                        m.rec("wh", 0, hiword_signed(data.mouseData))
                    elif msg == WM_MOUSEHWHEEL:
                        m.rec("wh", hiword_signed(data.mouseData), 0)
                if msg == WM_MOUSEMOVE and not buttons_held():
                    if self.check_edge(data.pt.x, data.pt.y):
                        self.mouse_listener.suppress_event()
                return

            c = self.clients.get(self.active)
            if c is None:
                self.go_local()
                return
            if msg == WM_MOUSEMOVE:
                cx, cy = primary_center()
                self.remote_move(c, data.pt.x - cx, data.pt.y - cy)
            elif msg in BTN_MSG:
                b, d = BTN_MSG[msg]
                c.send({"t": "btn", "b": b, "d": d})
                self.macro.rec("btn", b, d)
            elif msg in (WM_XBUTTONDOWN, WM_XBUTTONUP):
                which = "x1" if hiword_signed(data.mouseData) == 1 else "x2"
                c.send({"t": "btn", "b": which, "d": msg == WM_XBUTTONDOWN})
                self.macro.rec("btn", which, msg == WM_XBUTTONDOWN)
            elif msg == WM_MOUSEWHEEL:
                c.send({"t": "wheel", "dx": 0, "dy": hiword_signed(data.mouseData)})
                self.macro.rec("wh", 0, hiword_signed(data.mouseData))
            elif msg == WM_MOUSEHWHEEL:
                c.send({"t": "wheel", "dx": hiword_signed(data.mouseData), "dy": 0})
                self.macro.rec("wh", hiword_signed(data.mouseData), 0)
        self.mouse_listener.suppress_event()

    # ---------- 키보드 훅
    def key_filter(self, msg, data):
        # 키보드 프로그램(로지텍·레이저 등 매크로/재배치)이나 일부 키보드는 키를 '주입된 입력'으로 보냄
        # → 예전에는 이걸 걸러서 F키 말고는 다른 기기로 안 넘어가는 문제가 있었음. 이 서버는 키를 주입하지 않으므로 그대로 받음
        if getattr(data, "dwExtraInfo", 0) == MACRO_MAGIC:
            return                                   # 매크로가 넣은 입력은 그대로 통과
        vk = data.vkCode
        down = msg in (WM_KEYDOWN, WM_SYSKEYDOWN)
        if vk in (0x11, 0xA2, 0xA3, 0x12, 0xA4, 0xA5):
            (self.mods_down.add if down else self.mods_down.discard)(vk)
        # 단축키용 키 구분: 오른쪽 숫자 키패드는 Num Lock이 꺼져 있어도 위쪽 숫자·방향키와 다른 키로 봄
        hk_id = hotkey_id(vk, data.flags)
        # 단축키 바꾸는 중이면 이번 키를 새 단축키로
        if down and self.macro.capture and self.macro.captured(hk_id):
            self.macro_keys_down.add(hk_id)
            self.key_listener.suppress_event()
            return
        # 매크로 단축키 (어느 기기에 있든 동작, 키 자체는 어디에도 안 보냄)
        tk = self.macro.toggle_key()
        if tk and hk_id == tk:                           # 매크로 키 켜기/끄기
            if down and hk_id not in self.macro_keys_down:
                self.macro_keys_down.add(hk_id)
                threading.Thread(target=self.macro.toggle_keys, daemon=True).start()
            elif not down:
                self.macro_keys_down.discard(hk_id)
            self.key_listener.suppress_event()
            return
        rk, pk = self.macro.keys()
        sk = self.macro.switch_key()
        dk = self.macro.devkey_lookup(hk_id)
        if not self.macro.keys_on:                       # 꺼져 있으면 매크로 키도 평소 키로
            rk = pk = sk = None
            dk = None
        if hk_id in (rk, pk, sk) or dk:
            if down and hk_id not in self.macro_keys_down:
                self.macro_keys_down.add(hk_id)
                if dk:
                    threading.Thread(target=self.macro.run_devkey, args=dk, daemon=True).start()
                else:
                    fn = self.macro.toggle_record if hk_id == rk else self.macro.toggle_play if hk_id == pk else self.macro.switch
                    threading.Thread(target=fn, daemon=True).start()
            elif not down:
                self.macro_keys_down.discard(hk_id)
            self.key_listener.suppress_event()
            return
        if not down and hk_id in self.macro_keys_down:     # 단축키로 정한 키를 뗄 때
            self.macro_keys_down.discard(hk_id)
            self.key_listener.suppress_event()
            return
        if down and self.active is not None and self.key_logs < 8:
            self.key_logs += 1
            print(f"[키] → {self.active}: vk={vk}{' (주입됨)' if data.flags & LLKHF_INJECTED else ''}")
            ac = self.clients.get(self.active)
            if self.key_logs == 4 and ac is not None and getattr(ac, "hid_on", False) and getattr(self, "adb", None):
                threading.Thread(target=self.adb.diagnose_keys, daemon=True).start()
        with self.lock:
            hk = int(self.cfg["return_hotkey_vk"])
            combo_ok = not (hk & COMBO) or (self.mods_down & {0x11, 0xA2, 0xA3} and self.mods_down & {0x12, 0xA4, 0xA5})
            if vk == hk & 0xFFFF and combo_ok:
                if self.active is not None:
                    if down:
                        self.go_local()
                    self.key_listener.suppress_event()
                    return
                if not (hk & COMBO):
                    return
            self.macro.rec("key", vk, down)
            if self.active is None:
                (self.local_keys.add if down else self.local_keys.discard)(vk)
                return
            if not down and vk in self.local_keys:
                self.local_keys.discard(vk)   # 윈도우에서 누른 키는 윈도우에서 떼기
                return
            c = self.clients.get(self.active)
            if c:
                c.send({"t": "key", "vk": vk, "d": down})
        self.key_listener.suppress_event()

    def heartbeat(self):
        """2초마다 모든 기기에 신호를 보냄. 기기는 7초 동안 신호가 없으면 끊긴 것으로 보고 바로 다시 연결"""
        last_exe_v, tick = self.client_exe_version(), 0
        while True:
            time.sleep(2)
            tick += 1
            if tick % 10 == 0:          # 서브 PC용 exe가 나중에 완성되면 그때 알림
                try:
                    v = self.client_exe_version()
                    if v and v != last_exe_v:
                        last_exe_v = v
                        self.announce_client_exe()
                except Exception as e:
                    print(f"[업데이트] 서브 PC exe 확인 오류: {e}")
            with self.lock:
                targets = list(self.clients.values())
            for c in targets:
                c.send({"t": "ping", "ts": time.perf_counter()})

    def client_rtts(self):
        with self.lock:
            return {n: getattr(c, "rtt", None) for n, c in self.clients.items()}

    # ---------- 훅 감시: 윈도우가 훅을 몰래 해제하면 다시 설치
    def start_hooks(self):
        self.mouse_listener = mouse.Listener(win32_event_filter=self.mouse_filter)
        self.key_listener = keyboard.Listener(win32_event_filter=self.key_filter)
        self.mouse_listener.start()
        self.key_listener.start()

    def hook_watchdog(self):
        class POINT(ctypes.Structure):
            _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]
        pt, last_pos, last_restart = POINT(), None, 0.0
        while True:
            time.sleep(0.5)
            user32.GetCursorPos(ctypes.byref(pt))
            pos = (pt.x, pt.y)
            moved = last_pos is not None and pos != last_pos
            last_pos = pos
            # 커서가 움직였는데 1초 넘게 훅에 아무 신호가 없으면 훅이 해제된 것
            if moved and time.time() - self.last_hook > 1.0 and time.time() - last_restart > 5:
                last_restart = time.time()
                print("[복구] 입력 훅이 멈춰서 다시 설치합니다")
                with self.lock:
                    old_m, old_k = self.mouse_listener, self.key_listener
                    self.start_hooks()
                for l in (old_m, old_k):
                    try:
                        l.stop()
                    except Exception:
                        pass

    def start(self):
        threading.Timer(3, self.macro.changed).start()     # 화면 위 매크로 표시 처음 한 번
        # 다른 프로그램이 바빠도 입력 처리가 밀리지 않게 우선순위를 높임
        try:
            ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(), 0x80)
        except Exception:
            pass
        threading.Thread(target=self.serve, daemon=True).start()
        threading.Thread(target=self.discovery, daemon=True).start()
        threading.Thread(target=self.heartbeat, daemon=True).start()
        self.start_hooks()
        threading.Thread(target=self.hook_watchdog, daemon=True).start()


# ---------------------------------------------------------------- 태블릿 진짜 마우스 모드 (무선 디버깅)
class AdbManager:
    """무선 디버깅(adb)으로 태블릿에 '셸 권한 도우미'를 실행해서 가상 USB 마우스·키보드를 만들게 함.
    처음 한 번 페어링하면, 이후엔 태블릿이 연결될 때마다 자동으로 켬."""
    PKG = "com.example.kvm"

    def __init__(self, server):
        self.s = server
        self.adb = os.path.join(os.environ.get("LOCALAPPDATA", ""), "KVM-build", "sdk", "platform-tools", "adb.exe")
        self.token = os.urandom(8).hex()
        self.status = ""
        self.busy = False
        self.last_try = 0.0
        self.serial = None
        self._installed = set()
        threading.Thread(target=self._loop, daemon=True).start()

    def run(self, *args, timeout=15):
        try:
            r = subprocess.run([self.adb, *args], capture_output=True, text=True, timeout=timeout,
                               creationflags=0x08000000, encoding="utf-8", errors="replace")
            return r.returncode, (r.stdout or "") + (r.stderr or "")
        except Exception as e:
            return -1, str(e)

    def android_clients(self):
        with self.s.lock:
            return [c for c in self.s.clients.values() if getattr(c, "platform", "") == "android"]

    def _loop(self):
        """태블릿이 연결돼 있는데 진짜 마우스 모드가 꺼져 있으면 30초마다 다시 시도"""
        while True:
            time.sleep(5)
            for c in self.android_clients():
                if getattr(c, "hid_on", False):
                    self.status = "켜짐"
                elif self.s.cfg.get("adb_paired") and time.time() - self.last_try > 30:
                    self.ensure_async(c)

    def ensure_async(self, client):
        if self.busy or not os.path.exists(self.adb):
            if not os.path.exists(self.adb):
                self.status = "빌드 도구가 없어요 · apk만들기.bat을 한 번 실행하세요"
            return
        threading.Thread(target=self.ensure, args=(client,), daemon=True).start()

    def connect(self, ip):
        code, out = self.run("devices")
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device" and parts[0].startswith(ip + ":"):
                return parts[0]
        # 무선 디버깅 포트는 켤 때마다 바뀜 → 같은 와이파이에서 자동으로 찾기(mDNS)
        code, out = self.run("mdns", "services", timeout=8)
        ports = re.findall(r"_adb-tls-connect\._tcp\.?\s+" + re.escape(ip) + r":(\d+)", out)
        if self.s.cfg.get("adb_port"):
            ports.append(str(self.s.cfg["adb_port"]))
        for port in dict.fromkeys(ports):
            code, out = self.run("connect", f"{ip}:{port}", timeout=8)
            if "connected" in out and "cannot" not in out and "failed" not in out:
                return f"{ip}:{port}"
        return None

    def ensure(self, client):
        self.busy = True
        self.last_try = time.time()
        ip = client.addr[0]
        try:
            self.status = "태블릿에 연결 중…"
            serial = self.connect(ip)
            if not serial:
                self.status = ("태블릿에서 무선 디버깅을 켜 주세요" if self.s.cfg.get("adb_paired")
                               else "처음 한 번 페어링이 필요해요")
                return
            self.serial = serial
            code, out = self.run("-s", serial, "shell", "pm", "path", self.PKG)
            m = re.search(r"package:(\S+)", out)
            if not m:
                self.status = "태블릿 앱 경로를 찾지 못했어요"
                print(f"[진짜 마우스] pm path 실패: {out.strip()[:200]}")
                return
            apk = m.group(1)
            self.run("-s", serial, "shell", "pkill", "-f", "com.example.kvm.UhidHelper")
            cmd = (f"CLASSPATH={apk} setsid app_process / com.example.kvm.UhidHelper {self.token} "
                   f"</dev/null >/data/local/tmp/kvm-uhid.log 2>&1 &")
            self.run("-s", serial, "shell", cmd)
            time.sleep(1.5)
            code, log = self.run("-s", serial, "shell", "cat", "/data/local/tmp/kvm-uhid.log")
            if "ready" not in log:
                self.status = "가상 마우스를 만들지 못했어요"
                print(f"[진짜 마우스] 도우미 시작 실패: {log.strip()[:400]}")
                return
            self.s.cfg["adb_paired"] = True
            self.s.save()
            self.status = "도우미 실행됨 · 태블릿 연결 대기"
            print(f"[진짜 마우스] {serial} 도우미 실행")
            # 태블릿이 가상 장치를 어떻게 인식했는지 기록 (문제 확인용)
            code, dump = self.run("-s", serial, "shell", "dumpsys input | grep -A4 'KVM '", timeout=10)
            lines = [l.strip() for l in dump.splitlines() if l.strip()][:14]
            print("[진짜 마우스] 인식된 장치: " + " | ".join(lines)[:900])
            client.send({"t": "uhid_token", "token": self.token})
            # 마우스 가속을 끄면 같은 움직임 = 같은 거리 → 매크로 위치가 매번 똑같이 맞음
            code, out = self.run("-s", serial, "shell",
                                 "settings put system mouse_pointer_acceleration_enabled 0; "
                                 "settings list system | grep -iE 'pointer|mouse'", timeout=10)
            print("[진짜 마우스] 마우스 설정: " + " | ".join(l.strip() for l in out.splitlines() if l.strip())[:400])
            self.s.announce_apk(client)          # 앱이 예전 버전이면 여기서 바로 설치
        finally:
            self.busy = False

    def find_serial(self, ip=None):
        if self.serial:
            return self.serial
        if ip is None:
            cs = self.android_clients()
            ip = cs[0].addr[0] if cs else None
        if ip and os.path.exists(self.adb):
            self.serial = self.connect(ip)
        return self.serial

    def install_apk(self, version, ip=None):
        """태블릿 앱을 무선 디버깅으로 조용히 설치 (설치 확인 창 없음)"""
        if version in self._installed or not self.find_serial(ip):
            return
        self._installed.add(version)
        apk = os.path.join(ROOT, "KVM-Android.apk")
        time.sleep(2)
        print(f"[앱 설치] v{version} 무선 디버깅으로 설치 중…")
        code, out = self.run("-s", self.serial, "install", "-r", apk, timeout=180)
        if "Success" in out:
            print(f"[앱 설치] v{version} 설치 완료 (태블릿 앱이 잠깐 다시 켜져요)")
        else:
            print(f"[앱 설치] 실패 · 태블릿에서 업데이트 버튼을 눌러 주세요: {out.strip()[:300]}")

    def diagnose_keys(self):
        """태블릿이 가상 키보드 입력을 실제로 받았는지·어느 화면으로 보냈는지 기록 (문제 확인용)"""
        if getattr(self, "_diag_running", False) or not self.find_serial():
            return
        self._diag_running = True
        try:
            time.sleep(1.0)
            code, out = self.run("-s", self.serial, "shell",
                                 "dumpsys input | grep -iE 'FocusedWindow|KVM Keyboard|KeyLayoutFile|ESCAPE|LANGUAGE|HANGUL|KANA' | head -n 20; "
                                 "grep -E '^key (1|122|123|90|91) ' /system/usr/keylayout/Generic.kl",
                                 timeout=10)
            lines = [l.strip() for l in out.splitlines() if l.strip()]
            print("[키 확인] " + (" | ".join(lines)[:1500] if lines else "기록 없음"))
            code, out = self.run("-s", self.serial, "shell", "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 3", timeout=10)
            print("[키 확인] 화면: " + " | ".join(l.strip() for l in out.splitlines() if l.strip())[:400])
        except Exception as e:
            print(f"[키 확인] 오류: {e}")
        finally:
            self._diag_running = False

    def pair(self, ip, port, code):
        if not os.path.exists(self.adb):
            return "빌드 도구가 없어요 · apk만들기.bat을 한 번 실행하세요"
        rc, out = self.run("pair", f"{ip}:{int(port)}", str(code).strip(), timeout=20)
        print(f"[진짜 마우스] 페어링: {out.strip()[:200]}")
        if "Successfully paired" not in out:
            return "페어링 실패 · 코드와 포트를 다시 확인하세요 (코드는 1분 안에 입력)"
        self.s.cfg["adb_paired"] = True
        self.s.save()
        for c in self.android_clients():
            time.sleep(2)
            self.ensure(c)
        return "페어링 완료"


# ---------------------------------------------------------------- 자동 패치
# 다운로드 폴더에 KVM-patch-x.y.z.zip 이 생기면 자동으로 적용하고 서버를 다시 켬
ROOT = HERE if getattr(sys, "frozen", False) else os.path.dirname(HERE)   # KVM 폴더
KNOWN_TOP = {"windows", "mac", "android", "tools", "VERSION.txt", "README.md"}
PROTECTED = {"config.json", "client_config.json"}
_single_instance = None


def vtuple(v):
    try:
        return tuple(int(x) for x in str(v).strip().split("."))
    except ValueError:
        return (0,)


def patch_dirs():
    home = os.path.expanduser("~")
    dirs = [os.path.join(home, "Downloads"), os.path.join(home, "OneDrive", "다운로드"),
            os.path.join(home, "OneDrive", "Downloads"), ROOT]
    return [d for d in dirs if os.path.isdir(d)]


def _strip_prefix(names):
    """zip 안의 감싸는 폴더(KVM-patch-1.4.1/ 등)를 벗겨낼 접두어"""
    prefix = ""
    for _ in range(3):
        rest = [n[len(prefix):] for n in names if n.startswith(prefix) and n[len(prefix):]]
        tops = {r.split("/")[0] for r in rest}
        if len(tops) == 1 and not (tops & KNOWN_TOP) and any("/" in r for r in rest):
            prefix += tops.pop() + "/"
        else:
            break
    return prefix


def zip_version(path):
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            prefix = _strip_prefix(names)
            if prefix + "VERSION.txt" in names:
                return z.read(prefix + "VERSION.txt").decode("utf-8").strip()
    except (OSError, zipfile.BadZipFile, KeyError):
        pass
    return None


def apply_zip(path, root):
    """바뀐 파일만 덮어쓰고 이전 파일은 backup\\날짜 에 보관. 바뀐 상대경로 목록 반환"""
    changed = []
    stamp = time.strftime("%Y%m%d-%H%M%S")
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        prefix = _strip_prefix(names)
        rels = [n[len(prefix):] for n in names if n.startswith(prefix)]
        if not {r.split("/")[0] for r in rels if r} & KNOWN_TOP:
            raise ValueError("KVM 패치 파일이 아닙니다")
        for n in names:
            rel = n[len(prefix):]
            if not rel or rel.endswith("/") or ".." in rel.split("/"):
                continue
            if os.path.basename(rel) in PROTECTED or rel.startswith("backup/"):
                continue
            data = z.read(n)
            dest = os.path.join(root, *rel.split("/"))
            if os.path.exists(dest):
                with open(dest, "rb") as f:
                    if f.read() == data:
                        continue
                bk = os.path.join(root, "backup", stamp, *rel.split("/"))
                os.makedirs(os.path.dirname(bk), exist_ok=True)
                with open(dest, "rb") as src, open(bk, "wb") as dst:
                    dst.write(src.read())
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as f:
                f.write(data)
            changed.append(rel)
    return changed


CLIENT_FILES = {"mac": "mac/client.py", "windows": "windows/client.py"}



# ---------------------------------------------------------------- 기기 추가: 설치 파일 나눠 주기
SHARE_PAGE = """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>KVM 설치</title>
<style>
:root{color-scheme:dark}body{margin:0;background:#0f1115;color:#e9ebf0;font:15px/1.6 -apple-system,"Malgun Gothic",sans-serif}
.w{max-width:560px;margin:0 auto;padding:28px 18px 40px}h1{font-size:22px;margin:0 0 4px}p.l{color:#9aa1ad;margin:0 0 22px}
a.c{display:flex;gap:14px;align-items:center;background:#171a21;border:1px solid #262a33;border-radius:14px;padding:16px;margin-bottom:12px;color:inherit;text-decoration:none}
a.c:active{background:#1e222b}.i{width:42px;height:42px;border-radius:11px;display:grid;place-items:center;font-size:22px;background:#222634;flex:none}
.c b{display:block;font-size:16px}.c small{color:#9aa1ad;font-size:13px}.c.off{opacity:.4;pointer-events:none}
.n{background:#141820;border:1px solid #232733;border-radius:12px;padding:12px 14px;color:#9aa1ad;font-size:13px;margin-top:18px}
.n b{color:#e9ebf0}</style></head><body><div class="w">
<h1>KVM 설치</h1><p class="l">이 기기에 맞는 것을 누르면 설치 파일을 받아요 · 메인 PC: <b>%(server)s</b> (%(ip)s)</p>
<a class="c %(a_off)s" href="/android"><span class="i">🤖</span><div><b>안드로이드 태블릿·폰</b><small>앱(apk) 받기 → 열어서 설치 · %(a_ver)s</small></div></a>
<a class="c %(w_off)s" href="/windows"><span class="i">🪟</span><div><b>윈도우 PC (서브)</b><small>KVM-Client.exe 받기 → 실행하면 설치 · %(w_ver)s</small></div></a>
<a class="c %(m_off)s" href="/mac"><span class="i">🍎</span><div><b>맥북·맥</b><small>압축 파일 받기 → 풀고 'KVM 맥 실행.command' 실행 · %(m_ver)s</small></div></a>
<div class="n">설치한 뒤 연결할 때 <b>메인 PC 주소 %(ip)s</b>와 메인 PC 설정 창의 <b>비밀번호</b>를 입력하세요.<br>
한 번 설치하면 그다음부터는 메인 PC가 알아서 업데이트해 줘요.</div>
</div></body></html>"""

WAIT_PAGE = """<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="20">
<title>KVM 설치</title><style>:root{color-scheme:dark}body{margin:0;background:#0f1115;color:#e9ebf0;
font:15px/1.6 -apple-system,"Malgun Gothic",sans-serif}.w{max-width:520px;margin:0 auto;padding:40px 18px}
a{color:#7fb2ff}</style></head><body><div class="w"><h2>최신 버전(v%(ver)s) 만드는 중</h2>
<p>메인 PC가 방금 업데이트돼서 설치 파일을 새로 만들고 있어요. 이전 버전을 받지 않도록 잠시 기다려 주세요.<br>
몇 분 걸리고, 이 화면은 20초마다 새로 확인해요. 다 되면 <a href="/">처음 화면</a>에서 다시 눌러 주세요.</p></div></body></html>"""


class ShareServer:
    """같은 와이파이의 기기가 브라우저로 http://메인PC:8765 에 들어오면 설치 파일을 받을 수 있게"""
    PORT = 8765

    def __init__(self, server):
        self.s = server
        self.ok = False
        threading.Thread(target=self._serve, daemon=True).start()

    def _file(self, kind):
        if kind == "android":
            return os.path.join(ROOT, "KVM-Android.apk"), "KVM-Android.apk", "application/vnd.android.package-archive"
        if kind == "windows":
            return os.path.join(ROOT, "KVM-Client.exe"), "KVM-Client.exe", "application/octet-stream"
        return os.path.join(ROOT, "mac"), "KVM-mac.zip", "application/zip"

    def _raw_version(self, kind):
        """설치 파일이 몇 버전으로 만들어졌는지 (맥은 폴더를 그때그때 묶으므로 항상 지금 버전)"""
        f = {"android": "KVM-Android.version", "windows": "KVM-Client.version"}.get(kind)
        if not f:
            return VERSION
        try:
            with open(os.path.join(ROOT, f), encoding="utf-8") as fh:
                return fh.read().strip()
        except OSError:
            return ""

    def _version(self, kind):
        v = self._raw_version(kind)
        return f"v{v}" if v else ""

    def building(self, kind):
        """패치 뒤 새 설치 파일을 만드는 중인지"""
        if kind == "windows":
            return os.path.exists(os.path.join(ROOT, "rebuild.lock"))
        if kind == "android":
            return str(getattr(self.s, "apk_status", "")).endswith("중…")
        return False

    def state(self, kind):
        """latest 최신 · building 최신으로 만드는 중 · old 이전 버전(새로 만들지 못함)"""
        if self.building(kind):
            return "building"
        return "latest" if vtuple(self._raw_version(kind)) >= vtuple(VERSION) else "old"

    def exists(self, kind):
        path = self._file(kind)[0]
        return os.path.isfile(os.path.join(path, "client.py")) if kind == "mac" else os.path.isfile(path)

    def mac_zip(self):
        import io
        import zipfile
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
            for name in os.listdir(os.path.join(ROOT, "mac")):
                full = os.path.join(ROOT, "mac", name)
                if not os.path.isfile(full) or name.endswith(".json"):
                    continue
                info = zipfile.ZipInfo("KVM-mac/" + name, time.localtime(os.path.getmtime(full))[:6])
                info.external_attr = (0o100755 if name.endswith((".command", ".py")) else 0o100644) << 16
                info.flag_bits |= 0x800                              # 한글 파일 이름이 맥에서 깨지지 않게
                info.compress_type = zipfile.ZIP_DEFLATED
                with open(full, "rb") as fh:
                    z.writestr(info, fh.read())
        return buf.getvalue()

    def _serve(self):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        share = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                kind = self.path.strip("/").split("?")[0]
                try:
                    if kind == "":
                        data = (SHARE_PAGE % share.page_vars()).encode("utf-8")
                        ctype, fname = "text/html; charset=utf-8", None
                    elif kind in ("android", "windows", "mac") and share.building(kind):
                        data = (WAIT_PAGE % {"ver": VERSION}).encode("utf-8")      # 이전 버전을 주지 않음
                        ctype, fname = "text/html; charset=utf-8", None
                    elif kind in ("android", "windows", "mac") and share.exists(kind):
                        path, fname, ctype = share._file(kind)
                        if kind == "mac":
                            data = share.mac_zip()
                        else:
                            with open(path, "rb") as fh:
                                data = fh.read()
                        print(f"[기기 추가] {self.client_address[0]} ← {fname}")
                    else:
                        self.send_error(404)
                        return
                    self.send_response(200)
                    self.send_header("Content-Type", ctype)
                    self.send_header("Content-Length", str(len(data)))
                    if fname:
                        self.send_header("Content-Disposition", f'attachment; filename="{fname}"')
                    self.end_headers()
                    self.wfile.write(data)
                except (OSError, ConnectionError):
                    pass

        try:
            httpd = ThreadingHTTPServer(("", self.PORT), H)
            httpd.daemon_threads = True
            self.ok = True
            httpd.serve_forever()
        except OSError as e:
            print(f"[기기 추가] {self.PORT} 포트를 열 수 없어요: {e}")

    def page_vars(self):
        v = {"server": html_escape(self.s.display_name()), "ip": lan_ip()}
        for k, short in (("android", "a"), ("windows", "w"), ("mac", "m")):
            ok = self.exists(k)
            v[short + "_off"] = "" if ok else "off"
            st = self.state(k)
            v[short + "_ver"] = ("아직 없음" if not ok and st != "building" else
                                 f"최신 v{VERSION} 만드는 중 · 잠시 후 다시" if st == "building" else
                                 self._version(k) + (" (이전 버전)" if st == "old" else " · 최신"))
            if st == "building":
                v[short + "_off"] = ""
        return v

    def url(self):
        return f"http://{lan_ip()}:{self.PORT}"

    def info(self):
        url = self.url()
        qr = ""
        try:
            import qrcode
            import qrcode.image.svg
            img = qrcode.make(url, image_factory=qrcode.image.svg.SvgPathImage, box_size=8, border=1)
            qr = img.to_string(encoding="unicode")
        except Exception:
            qr = ""
        return {"url": url, "ok": self.ok, "qr": qr,
                "latest": VERSION,
                "files": {k: {"ok": self.exists(k), "ver": self._version(k) if self.exists(k) else "",
                              "state": self.state(k)}
                          for k in ("android", "windows", "mac")}}

    def save_to_downloads(self, kind):
        """이 PC의 다운로드 폴더에 설치 파일 저장 (USB 메모리 등으로 옮길 때)"""
        if self.building(kind):
            return f"최신 버전(v{VERSION})으로 설치 파일을 만드는 중이에요 · 몇 분 뒤 다시 눌러 주세요"
        if not self.exists(kind):
            return "아직 이 설치 파일이 없어요"
        path, fname, _ = self._file(kind)
        dst_dir = os.path.join(os.path.expanduser("~"), "Downloads")
        os.makedirs(dst_dir, exist_ok=True)
        dst = os.path.join(dst_dir, fname)
        try:
            if kind == "mac":
                with open(dst, "wb") as fh:
                    fh.write(self.mac_zip())
            else:
                shutil.copy2(path, dst)
            subprocess.Popen(["explorer", "/select,", dst], creationflags=0x08000000)
            return "ok"
        except OSError as e:
            return f"저장하지 못했어요: {e}"

    def usb_install(self, adb):
        """USB로 연결한 안드로이드에 바로 설치 (USB 디버깅 허용 필요)"""
        if adb is None or not os.path.exists(adb.adb):
            return "안드로이드 도구(adb)가 아직 없어요 · 태블릿 앱을 한 번 만들면 생겨요"
        if self.building("android"):
            return f"최신 버전(v{VERSION})으로 태블릿 앱을 만드는 중이에요 · 몇 분 뒤 다시 눌러 주세요"
        if not self.exists("android"):
            return "아직 태블릿 앱 파일이 없어요"
        code, out = adb.run("devices", timeout=10)
        usb = [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device") and ":" not in l.split()[0]]
        unauth = [l.split()[0] for l in out.splitlines()[1:] if "unauthorized" in l]
        if not usb:
            if unauth:
                return "태블릿 화면에 뜬 'USB 디버깅 허용'을 눌러 주세요"
            return "USB로 연결된 안드로이드가 없어요 · 케이블 연결과 'USB 디버깅'을 확인하세요"
        apk = self._file("android")[0]
        code, out = adb.run("-s", usb[0], "install", "-r", apk, timeout=180)
        print(f"[기기 추가] USB 설치 {usb[0]}: {out.strip()[-200:]}")
        return "ok" if "Success" in out else f"설치하지 못했어요: {out.strip()[-160:]}"

class Updater:
    def __init__(self, server, active=True):
        self.s = server
        self.pending = None          # (path, version) 자동 적용이 꺼져 있을 때
        self.status = ""
        self.skip = set()
        self.on_restart = None       # 트레이 아이콘 정리용
        self.rebuilding = False      # 새 exe를 만드는 동안은 다른 패치를 적용하지 않음
        if active:              # 서브 PC 프로그램 안에서 메인으로 쓸 때는 패치 적용 안 함
            threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        time.sleep(3)
        while True:
            try:
                self.scan()
            except Exception as e:
                print(f"[업데이트] 확인 오류: {e}")
            time.sleep(5)

    def scan(self):
        if self.rebuilding:
            return
        lock = os.path.join(ROOT, "rebuild.lock")      # 이전 업데이트의 exe 만들기가 아직 진행 중
        if os.path.exists(lock) and time.time() - os.path.getmtime(lock) < 900:
            return
        best = None
        for d in patch_dirs():
            for fn in os.listdir(d):
                if not (fn.lower().startswith("kvm-patch") and fn.lower().endswith(".zip")):
                    continue
                p = os.path.join(d, fn)
                if p in self.skip:
                    continue
                v = zip_version(p)
                if v and vtuple(v) > vtuple(VERSION) and (best is None or vtuple(v) > vtuple(best[1])):
                    best = (p, v)
        self.pending = best
        if best and self.s.cfg.get("auto_update", True):
            self.apply(*best)

    def apply(self, path=None, version=None):
        if path is None:
            if not self.pending:
                return False
            path, version = self.pending
        try:
            self.status = f"v{version} 적용 중…"
            print(f"[업데이트] {os.path.basename(path)} → v{version}")
            changed = apply_zip(path, ROOT)
        except Exception as e:
            self.status = f"패치 적용 실패: {e}"
            print(f"[업데이트] 실패: {e}")
            self.skip.add(path)
            return False
        for rel in changed:
            print(f"   갱신 {rel}")
        if any(r.startswith("android/") for r in changed):
            self.s.cfg["apk_outdated"] = True
        self.s.cfg["just_updated"] = version
        self.s.save()
        code_changed = any(r.startswith("windows/") for r in changed)
        if code_changed and getattr(sys, "frozen", False):
            self.status = f"v{version} 준비 중… (1~2분, 그동안 계속 사용 가능 · 끝나면 몇 초만 깜빡)"
        else:
            self.status = f"v{version} 적용 완료 · 다시 시작하는 중…"
            time.sleep(1.5)                          # 화면에 안내가 보일 시간
        self.restart(code_changed)
        return True

    def restart(self, code_changed):
        global _single_instance
        if _single_instance and not (getattr(sys, "frozen", False) and code_changed):
            ctypes.windll.kernel32.CloseHandle(_single_instance)   # 새 서버가 바로 켜질 수 있게
            _single_instance = None
        NO_WINDOW = 0x08000000
        # exe(PyInstaller)가 자기 자신을 다시 실행할 때 이전 실행의 임시 폴더 정보를 물려주면
        # 이전 프로세스가 끝나며 그 폴더가 지워져 새 서버가 죽음 → 환경 변수 초기화
        env = {k: v for k, v in os.environ.items() if not k.startswith(("_PYI", "_MEIPASS"))}
        env["PYINSTALLER_RESET_ENVIRONMENT"] = "1"
        if getattr(sys, "frozen", False):
            if code_changed:   # exe는 코드가 안에 들어 있어서 새로 빌드한 뒤 다시 실행
                # 서버는 끄지 않고 계속 동작. 새 exe가 다 만들어지면 bat이 잠깐 끄고 바꿔 끼운 뒤 다시 켬
                self.rebuilding = True
                bat = os.path.join(ROOT, "tools", "rebuild_restart.bat")
                subprocess.Popen(["cmd", "/c", bat], cwd=ROOT, creationflags=NO_WINDOW, env=env)
                print("[업데이트] 새 exe 만드는 중 (서버는 계속 동작)")
                return
            else:
                relaunch([sys.executable])
        else:
            pyw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
            exe = sys.executable if sys.stdout and getattr(sys.stdout, "stream", None) else (pyw if os.path.exists(pyw) else sys.executable)
            relaunch([exe, os.path.abspath(__file__)])
        if self.on_restart:
            try:
                self.on_restart()
            except Exception:
                pass
        os._exit(0)


# ---------------------------------------------------------------- 설정 창 (Edge WebView 기반 UI)
def resource(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


# ---------------------------------------------------------------- 윈도우 연동 (자동 실행·아이콘·창 다시 열기)
STARTUP_ARG = "--autostart"
STARTED_AT_LOGIN = STARTUP_ARG in sys.argv


def _approve_run(name):
    """윈도우 '시작 앱' 설정에서 꺼져 있으면(작업 관리자 → 시작 앱) 다시 켬"""
    import winreg
    path = r"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run"
    try:
        with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, path, 0, winreg.KEY_SET_VALUE) as k:
            winreg.SetValueEx(k, name, 0, winreg.REG_BINARY, bytes([2] + [0] * 11))
    except OSError:
        pass


def _startup_link(name):
    return os.path.join(os.environ.get("APPDATA", ""), r"Microsoft\Windows\Start Menu\Programs\Startup", name + ".lnk")


def _make_link(link, target, args=""):
    ps = (f"$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{link}');"
          f"$s.TargetPath='{target}';$s.Arguments='{args}';$s.WorkingDirectory='{os.path.dirname(target)}';$s.Save()")
    subprocess.run(["powershell", "-NoProfile", "-Command", ps], creationflags=0x08000000)


def register_autostart(name, on):
    """레지스트리 Run + 시작프로그램 폴더 바로가기 두 군데에 등록 (한쪽이 막혀도 실행되게).
    둘 다 실행돼도 두 번째는 조용히 종료됨"""
    import winreg
    run_key = r"Software\Microsoft\Windows\CurrentVersion\Run"
    if getattr(sys, "frozen", False):
        target, args = sys.executable, STARTUP_ARG
    else:
        pyw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
        target, args = (pyw if os.path.exists(pyw) else sys.executable), f'"{os.path.abspath(sys.argv[0])}" {STARTUP_ARG}'
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, run_key, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            winreg.SetValueEx(k, name, 0, winreg.REG_SZ, f'"{target}" {args}')
        else:
            try:
                winreg.DeleteValue(k, name)
            except FileNotFoundError:
                pass
    link = _startup_link(name)
    if on:
        _approve_run(name)
        _make_link(link, target, args)
    elif os.path.exists(link):
        os.remove(link)


def is_autostart(name):
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as k:
            winreg.QueryValueEx(k, name)
            return True
    except OSError:
        return os.path.exists(_startup_link(name))


def set_app_id(app_id):
    """작업 표시줄에서 python 아이콘 대신 이 프로그램 아이콘으로 묶이게"""
    try:
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(app_id)
    except Exception:
        pass


def single_instance(name):
    """이미 실행 중이면 그 창을 앞으로 불러오고 True. (로그인 자동 실행으로 중복된 경우엔 조용히)"""
    k32 = ctypes.windll.kernel32
    for _ in range(10):      # 업데이트 직후라면 이전 실행이 끝날 때까지 잠깐 기다림
        h = k32.CreateMutexW(None, False, f"Local\\{name}-Single-Instance")
        if k32.GetLastError() != 183:
            return h
        k32.CloseHandle(h)
        if STARTED_AT_LOGIN:
            return None
        ev = k32.OpenEventW(0x0002, False, f"Local\\{name}-Show")   # EVENT_MODIFY_STATE
        if ev:
            k32.SetEvent(ev)
            k32.CloseHandle(ev)
            return None
        time.sleep(0.5)
    return None


def watch_show_requests(name, on_show):
    """다른 곳에서 exe를 다시 실행하면 이미 켜진 창을 보여줌"""
    k32 = ctypes.windll.kernel32
    k32.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
    ev = k32.CreateEventW(None, False, False, f"Local\\{name}-Show")

    def loop():
        while True:
            if k32.WaitForSingleObject(ev, 0xFFFFFFFF) == 0:
                try:
                    on_show()
                except Exception:
                    pass
    threading.Thread(target=loop, daemon=True).start()


def relaunch(exe_args):
    """새 프로세스를 탐색기를 통해 실행 → 숨김 상태로 뜨는 문제 없이 보통 창으로 열림"""
    env = {k: v for k, v in os.environ.items() if not k.startswith(("_PYI", "_MEIPASS"))}
    env["PYINSTALLER_RESET_ENVIRONMENT"] = "1"
    if getattr(sys, "frozen", False) and len(exe_args) == 1:
        subprocess.Popen(["explorer.exe", exe_args[0]], env=env)
    else:
        subprocess.Popen(exe_args, env=env)


def load_tray_icon(ico_name, fallback):
    from PIL import Image as _Img
    try:
        img = _Img.open(resource(ico_name))
        try:
            img.size = (64, 64)        # .ico 안에서 64px 그림 선택
        except Exception:
            pass
        return img.convert("RGBA").resize((64, 64), _Img.LANCZOS)
    except Exception:
        return fallback()


def set_autostart(on):
    register_autostart("KVM-Server", on)


def get_autostart():
    return is_autostart("KVM-Server")


class Api:
    """화면(JS)에서 호출하는 함수들. 밑줄로 시작하는 속성은 화면에 노출되지 않음"""

    def __init__(self, server, updater, switch_cb=None, embedded=False):
        self._s = server
        self._u = updater
        self._window = None
        self._icon = None
        self._switch_cb = switch_cb
        self._embedded = embedded
        self._prof = ProfileStore(os.path.join(HERE, "profiles.json"))
        self._ssid = ""
        threading.Thread(target=self._watch_ssid, daemon=True).start()

    # --- 상태
    def get_state(self):
        s = self._s
        _, _, vw, vh = virtual_screen()
        sizes = s.client_sizes()
        versions = s.client_versions()
        rtts = s.client_rtts()
        layout = s.layout_snapshot()
        names = sorted(set(layout) | set(sizes), key=lambda n: (n not in sizes, n))
        devices = []
        for n in names:
            r = layout.get(n)
            w, h = sizes.get(n, (0, 0))
            with s.lock:
                _c = s.clients.get(n)
            devices.append({"name": n, "on": n in sizes, "w": w, "h": h, "version": versions.get(n, ""),
                            "hid": bool(_c and getattr(_c, "hid_on", False)),
                            "rtt": None if rtts.get(n) is None else round(rtts[n]),
                            "bat": (getattr(_c, "battery", None) or (None, False))[0] if _c else None,
                            "chg": (getattr(_c, "battery", None) or (None, False))[1] if _c else False,
                            "rect": r if r and "w" in r else None})
        return {
            "vw": vw, "vh": vh, "ip": lan_ip(), "port": s.cfg["port"], "version": VERSION,
            "port_error": s.port_error, "devices": devices,
            "adb": {"status": s.adb.status if s.adb else "", "paired": bool(s.cfg.get("adb_paired")),
                    "tablet": next((c.addr[0] for c in (s.adb.android_clients() if s.adb else [])), "")},
            "update": {
                "status": self._u.status,
                "pending": self._u.pending[1] if self._u.pending else None,
                "apk_outdated": s.cfg.get("apk_outdated", False),
                "apk_status": s.apk_status,
                "mac_outdated": s.cfg.get("mac_outdated", False),
            },
            "options": {
                "clipboard": s.cfg.get("clipboard", True),
                "autostart": get_autostart(),
                "show_layout_on_start": s.cfg.get("show_layout_on_start", True),
                "hotkey": s.cfg.get("return_hotkey_vk", 0x91),
                "auto_update": s.cfg.get("auto_update", True),
                "mouse_speed": s.cfg.get("mouse_speed", 1.0),
                "hotkey_name": hotkey_name(s.cfg.get("return_hotkey_vk", 0x91)),
                "macro_overlay": s.cfg.get("macro_overlay", True),
                "overlay_pos": s.cfg.get("overlay_pos", "tl"),
                "battery_overlay": s.cfg.get("battery_overlay", True),
                "battery_pos": s.cfg.get("battery_pos", "tr"),
                "battery_everywhere": s.cfg.get("battery_everywhere", True),
                "server_name": s.display_name(),
            },
            "macro": dict(s.macro.state(self._mdev()), devices=s.macro.devices()),
            "profiles": self._profiles_state(),
            "role": {"can_switch": bool(self._switch_cb), "embedded": self._embedded},
            "sub": self.sub_state() if self._switch_cb else None,
        }

    # --- 배치
    def set_rect(self, name, r):
        _, _, vw, vh = virtual_screen()
        snapped = snap({k: float(r[k]) for k in ("x", "y", "w", "h")}, vw, vh)
        self._s.set_rect(name, snapped)
        return snapped

    def resize(self, name, f):
        _, _, vw, vh = virtual_screen()
        r = self._s.layout_snapshot().get(name)
        if not r or "w" not in r or not (0.15 * vh <= r["h"] * f <= 2.5 * vh):
            return False
        side = side_of(r, vw, vh)
        cx, cy = r["x"] + r["w"] / 2, r["y"] + r["h"] / 2
        r["w"] *= f
        r["h"] *= f
        r["x"], r["y"] = cx - r["w"] / 2, cy - r["h"] / 2
        self._s.set_rect(name, snap(r, vw, vh, prefer=side))
        return True

    def forget(self, name):
        with self._s.lock:
            if name not in self._s.clients:
                self._s.layout.pop(name, None)
        self._s.save()
        return True

    # --- 옵션
    def set_option(self, key, value):
        try:
            if key == "autostart":
                set_autostart(bool(value))
            elif key == "mouse_speed":
                self._s.cfg["mouse_speed"] = max(0.3, min(3.0, float(value)))
                self._s.save()
            elif key == "hotkey":
                if int(value) in (*self._s.macro.keys(), self._s.macro.switch_key()):
                    return False                     # 매크로 키와 겹침
                self._s.cfg["return_hotkey_vk"] = int(value)
                self._s.save()
            elif key == "macro_repeat":
                self._s.cfg["macro_repeat"] = max(0, min(9999, int(value)))
                self._s.save()
                self._s.macro.changed()
            elif key == "battery_pos":
                if value not in ("tl", "ml", "bl", "tr", "mr", "br", "tc", "bc"):
                    return False
                self._s.cfg["battery_pos"] = value
                self._s.save()
                self._s.update_battery_overlay()
            elif key == "battery_everywhere":
                self._s.cfg["battery_everywhere"] = bool(value)
                self._s.save()
                self._s.update_battery_overlay()
            elif key == "battery_overlay":
                self._s.cfg["battery_overlay"] = bool(value)
                self._s.save()
                self._s.update_battery_overlay()
            elif key == "overlay_pos":
                if value not in ("tl", "ml", "bl", "tr", "mr", "br", "tc", "bc"):
                    return False
                self._s.cfg["overlay_pos"] = value
                self._s.save()
                self._s.macro.changed()
                self._s.update_battery_overlay()
            elif key == "macro_overlay":
                self._s.cfg["macro_overlay"] = bool(value)
                self._s.save()
                self._s.macro.changed()
                self._s.update_battery_overlay()
            elif key in ("clipboard", "show_layout_on_start", "auto_update", "profile_auto"):
                self._s.cfg[key] = bool(value)
                self._s.save()
            else:
                return False
            return True
        except Exception as e:
            print(f"[설정 오류] {key}: {e}")
            return False

    # --- 연결 프로필 · 역할(메인/서브) 전환
    def profile_save(self, name):
        return self._prof.save(name, self._s.profile_info(), current_ssid())

    def profile_load(self, name):
        info = self._prof.get(name)
        if info is None:
            return "없는 프로필이에요"
        self._s.apply_profile(info)
        self._s.cfg["profile_current"] = name
        self._s.save()
        print(f"[프로필] '{name}' 불러옴")
        return "ok"

    def profile_delete(self, name):
        r = self._prof.delete(name)
        if r == "ok" and self._s.cfg.get("profile_current") == name:
            self._s.cfg.pop("profile_current", None)
            self._s.save()
        return r

    def profile_rename(self, old, new):
        r = self._prof.rename(old, new)
        if r == "ok" and self._s.cfg.get("profile_current") == old:
            self._s.cfg["profile_current"] = new
            self._s.save()
        return r

    def switch_role(self):
        if not self._switch_cb:
            return False
        threading.Thread(target=self._switch_cb, daemon=True).start()
        return True

    # --- '서브' 화면: 이 PC를 서브로 쓸 때의 연결 정보 (적용을 눌러야 바뀜)
    def sub_state(self):
        cfg = load_json(SUB_CONFIG)
        store = ProfileStore(SUB_PROFILES)
        return {"host": cfg.get("host", ""), "port": cfg.get("port", 24800), "name": cfg.get("name") or socket.gethostname().lower(),
                "has_key": bool(cfg.get("key")), "current": cfg.get("profile_current"),
                "profiles": store.entries(lambda d: d.get("host") or "")}

    def sub_discover(self):
        me = lan_ip()
        return [x for x in discover() if x["ip"] != me]

    def sub_profile(self, name):
        info = ProfileStore(SUB_PROFILES).get(name) or {}
        return {"host": info.get("host", ""), "port": info.get("port", 24800), "name": info.get("name", ""),
                "key": info.get("key", "")}

    def sub_profile_save(self, pname, host, key, name):
        store = ProfileStore(SUB_PROFILES)
        old = store.get(pname) or {}
        info = {"host": str(host).strip(), "port": 24800, "name": str(name).strip() or socket.gethostname().lower(),
                "key": str(key).strip() or old.get("key") or load_json(SUB_CONFIG).get("key", "")}
        return store.save(pname, info, current_ssid())

    def sub_profile_delete(self, pname):
        return ProfileStore(SUB_PROFILES).delete(pname)

    def sub_apply(self, host, key, name, pname=""):
        """서브로 전환 (적용): 입력한 연결 정보를 저장하고 서브 화면으로 다시 켬"""
        host = str(host).strip()
        if not host:
            return "연결할 메인 PC 주소를 입력하세요"
        cfg = load_json(SUB_CONFIG)
        cfg.update({"host": host, "name": str(name).strip() or socket.gethostname().lower(), "port": 24800})
        if str(key).strip():
            cfg["key"] = str(key).strip()
        if not cfg.get("key"):
            return "메인 PC의 비밀번호를 입력하세요"
        if pname:
            cfg["profile_current"] = pname
        else:
            cfg.pop("profile_current", None)
        save_json(SUB_CONFIG, cfg)
        return "ok" if self.switch_role() else "이 프로그램에서는 바꿀 수 없어요"

    def _profiles_state(self):
        def describe(d):
            n = len([k for k, v in (d.get("layout") or {}).items() if "w" in v])
            return f"기기 배치 {n}개"
        return {"list": self._prof.entries(describe), "current": self._s.cfg.get("profile_current"),
                "ssid": self._ssid, "auto": self._s.cfg.get("profile_auto", True)}

    def _watch_ssid(self):
        """와이파이가 바뀌면 그 와이파이에서 저장한 프로필을 자동으로 불러옴 (집에 오면 집 설정)"""
        last = None
        while True:
            ssid = current_ssid()
            self._ssid = ssid
            if ssid != last:
                if last is not None and self._s.cfg.get("profile_auto", True):
                    name = self._prof.for_ssid(ssid)
                    if name and name != self._s.cfg.get("profile_current"):
                        self.profile_load(name)
                        try:
                            self._icon.notify(f"와이파이 '{ssid}' → '{name}' 연결 정보를 불러왔어요", "KVM")
                        except Exception:
                            pass
                last = ssid
            time.sleep(15)

    # 메인 PC 설정 창의 매크로 칸은 기기를 골라서 그 기기 것을 봄
    def macro_view(self, dev):
        self._mview = dev
        return True

    def _mdev(self):
        devs = self._s.macro.devices()
        if getattr(self, "_mview", None) not in devs:
            self._mview = self._s.macro.last_dev if self._s.macro.last_dev in devs else MAIN_DEV
        return self._mview

    def macro_cmd(self, op, trim=0):
        """설정 창의 녹화·실행 버튼: 창을 내리고 고른 기기에서 시작 (진행 중이면 끝/멈춤)"""
        m = self._s.macro
        dev = self._mdev()
        if m.recording:
            m.stop_record(trim=float(trim or 0))
            return "ok"
        if op == "play" and dev in m.players:
            m.stop_play(dev)
            return "ok"
        if dev != MAIN_DEV and dev not in self._s.clients:
            return f"{dev}이(가) 연결돼 있지 않아요"
        try:
            self._window.minimize()
        except Exception:
            pass

        def go():
            time.sleep(0.8)
            if op == "record":
                m.toggle_record(dev)
            else:
                m.toggle_play(dev, explicit=True)
        threading.Thread(target=go, daemon=True).start()
        return "ok"

    def macro_clear(self):
        return self._s.macro.clear(self._mdev())

    def macro_delete_all(self):
        return self._s.macro.delete_all(self._mdev())

    def macro_slot(self, name, slot):
        return self._s.macro.set_slot(self._mdev(), name, slot)

    def macro_folder(self, name, folder):
        return self._s.macro.set_folder(self._mdev(), name, folder)

    def macro_rename_folder(self, old, new):
        return self._s.macro.rename_folder(self._mdev(), old, new)

    def macro_toggle_capture(self):
        return self._s.macro.start_capture("toggle")

    def macro_toggle_clear(self):
        self._s.cfg.pop("macro_toggle_vk", None)
        self._s.cfg["macro_keys_on"] = True
        self._s.save()
        self._s.macro.changed()
        return "ok"

    def macro_toggle_keys(self):
        self._s.macro.toggle_keys()
        return "ok"

    def rename_device(self, old, new):
        return self._s.rename_device(old, new)

    def set_server_name(self, name):
        self._s.cfg["server_name"] = str(name).strip()[:30]
        self._s.save()
        return "ok"

    # --- 기기 추가: 설치 파일 나눠 주기
    def share_info(self):
        return self._s.share.info()

    def share_save(self, kind):
        return self._s.share.save_to_downloads(kind)

    def share_usb_install(self):
        return self._s.share.usb_install(self._s.adb)

    def macro_devkey(self, op):
        """고른 기기의 전용 실행·전환 키 정하기 (다음에 누르는 키)"""
        return self._s.macro.start_capture(("dplay:" if op == "play" else "dswitch:") + self._mdev())

    def macro_devkey_clear(self, op):
        return self._s.macro.clear_devkey(self._mdev(), op)

    def macro_capture(self, which):
        return self._s.macro.start_capture(which)

    def macro_save(self, name):
        return self._s.macro.save_as(self._mdev(), name)

    def macro_load(self, name):
        return self._s.macro.load(self._mdev(), name)

    def macro_delete(self, name):
        return self._s.macro.delete(self._mdev(), name)

    def macro_rename(self, old, new):
        return self._s.macro.rename(self._mdev(), old, new)

    def set_key(self, key):
        key = str(key).strip()
        if len(key) < 4:
            return False
        self._s.cfg["key"] = key
        self._s.save()
        print("[설정] 비밀번호 변경")
        return True

    # --- 진짜 마우스 모드
    def adb_pair(self, port, code):
        a = self._s.adb
        tabs = a.android_clients() if a else []
        if not tabs:
            return "태블릿이 먼저 연결돼 있어야 해요"
        return a.pair(tabs[0].addr[0], port, code)

    def adb_retry(self):
        a = self._s.adb
        for c in (a.android_clients() if a else []):
            a.last_try = 0
            a.ensure_async(c)
        return True

    # --- 업데이트
    def apply_update(self):
        threading.Thread(target=self._u.apply, daemon=True).start()
        return True

    def build_apk(self):
        return self._s.build_apk_async()

    def dismiss(self, key):
        if key in ("apk_outdated", "mac_outdated"):
            self._s.cfg[key] = False
            self._s.save()
        return True

    # --- 기타
    def copy(self, text):
        pyperclip.copy(str(text))
        return True

    def open_folder(self):
        os.startfile(HERE)
        return True

    def minimize(self):
        self._window.minimize()

    def hide(self):
        self._window.hide()

    def show(self):
        self._window.show()
        self._window.restore()

    def quit(self):
        threading.Thread(target=self._quit, daemon=True).start()

    def _quit(self):
        try:
            if self._icon:
                self._icon.stop()
        finally:
            os._exit(0)

    def _on_closing(self):
        self._window.hide()     # 닫기 = 트레이로 숨김
        return False


def tray_image():
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((4, 14, 42, 42), 7, fill=(91, 140, 255))
    d.rounded_rectangle((46, 18, 60, 46), 4, fill=(61, 220, 151))
    d.rounded_rectangle((16, 45, 30, 50), 2, fill=(91, 140, 255))
    return img


def run_ui(server, switch_cb=None, embedded=False, show_name="KVM-Server"):
    import webview

    updater = Updater(server, active=not embedded)
    server.adb = AdbManager(server)
    server.share = ShareServer(server)
    api = Api(server, updater, switch_cb, embedded)
    with open(resource("ui.html"), encoding="utf-8") as f:
        html = f.read()
    show = server.cfg.get("show_layout_on_start", True)
    win = webview.create_window(
        "KVM 서버", html=html, js_api=api, width=1140, height=740, min_size=(980, 640),
        frameless=True, easy_drag=False, background_color="#0a0b0f", hidden=not show,
    )
    api._window = win
    win.events.closing += api._on_closing

    def refresh_title():
        while True:
            names = ", ".join(sorted(server.connected_names())) or "없음"
            if api._icon:
                api._icon.title = f"KVM 서버 - 연결: {names}"
            time.sleep(2)

    api._icon = pystray.Icon(
        "kvm", load_tray_icon("kvm-server.ico", tray_image), "KVM 서버",
        menu=pystray.Menu(
            pystray.MenuItem("설정 열기", lambda *_: api.show(), default=True),
            pystray.MenuItem("종료", lambda *_: api.quit()),
        ),
    )
    api._icon.run_detached()
    server.notify_cb = lambda text: api._icon.notify(text, "KVM")
    updater.on_restart = api._icon.stop
    threading.Thread(target=refresh_title, daemon=True).start()
    watch_show_requests(show_name, api.show)       # exe를 다시 실행하면 이 창이 열림

    def on_loaded():
        if show:                  # 숨김 상태로 시작되는 경우가 있어서 한 번 더 보이게
            api.show()
    win.events.loaded += on_loaded

    if server.cfg.get("just_updated"):
        v = server.cfg.pop("just_updated")
        server.save()

        def tell():
            try:
                api._icon.notify(f"v{v}로 업데이트하고 다시 켰어요", "KVM 서버")
            except Exception:
                pass
        threading.Timer(3, tell).start()
    elif STARTED_AT_LOGIN:
        threading.Timer(4, lambda: api._icon.notify("KVM 서버가 켜졌어요 · 트레이에서 대기 중", "KVM 서버")).start()
    webview.start()
    api._quit()


def switch_to_sub():
    """메인 → 서브: 역할을 저장하고 다시 켜면 서브 화면(다른 메인 PC에 연결)으로 시작"""
    cfg = load_config()
    cfg["role"] = "sub"
    save_config(cfg)
    print("[역할] 서브로 전환")
    global _single_instance
    if _single_instance:
        ctypes.windll.kernel32.CloseHandle(_single_instance)
        _single_instance = None
    relaunch([sys.executable] + ([] if getattr(sys, "frozen", False) else [os.path.abspath(__file__)]))
    os._exit(0)


def switch_to_main_from_sub():
    """서브 화면(이 exe 안)에서 메인으로 돌아오기"""
    cfg = load_config()
    cfg["role"] = "main"
    save_config(cfg)
    print("[역할] 메인으로 전환")
    relaunch([sys.executable] + ([] if getattr(sys, "frozen", False) else [os.path.abspath(__file__)]))
    os._exit(0)


def run_main_role(config, embedded=False, switch_cb=None, show_name="KVM-Server"):
    """메인 역할 실행 (서브 PC 프로그램 안에서도 이걸로 메인이 됨)"""
    pending = config.pop("profile_pending", None)
    if pending:           # 서브 화면에서 '이 프로필로 메인 전환'을 고른 경우
        info = ProfileStore(os.path.join(HERE, "profiles.json")).get(pending)
        if info:
            for k in Server.PROFILE_KEYS:
                if k in info:
                    config[k] = info[k]
            config["profile_current"] = pending
            print(f"[프로필] 메인으로 전환하며 '{pending}' 적용")
        save_config(config)
    srv = Server(config)
    srv.start()
    if config.get("apk_outdated") and not embedded:
        srv.build_apk_async()
    run_ui(srv, switch_cb=switch_cb, embedded=embedded, show_name=show_name)


if __name__ == "__main__":
    set_app_id("KVM.Server")
    # 서버가 두 개 켜지면 서로 입력을 가로채서 방향이 꼬임 → 하나만 실행 (이미 켜져 있으면 그 창을 열어줌)
    _single_instance = single_instance("KVM-Server")
    if not _single_instance:
        sys.exit(0)
    config = load_config()
    if not os.path.exists(CONFIG_PATH):
        save_config(config)
        print(f"[설정] config.json 생성: {CONFIG_PATH}  → key(비밀번호)를 바꾸세요")
    print(f"\n[시작] v{VERSION} {'로그인 자동 실행' if STARTED_AT_LOGIN else '직접 실행'} · {sys.executable}")
    try:
        # 처음엔 자동 실행을 켜 두고, 켜져 있으면 매번 경로·'시작 앱' 허용을 다시 확인
        if not config.get("autostart_init"):
            config["autostart_init"] = True
            save_config(config)
            set_autostart(True)
        elif get_autostart():
            set_autostart(True)
    except Exception as e:
        print(f"[자동 실행] 설정 오류: {e}")
    if config.get("role") == "sub":
        # 이 PC를 서브로 쓰는 중 → 다른 메인 PC에 연결하는 화면으로
        print("[역할] 서브로 시작")
        import client as sub
        sub.VERSION = VERSION
        sub.run_sub_role(embedded=True, switch_cb=switch_to_main_from_sub, show_name="KVM-Server", main_dir=HERE)
    else:
        run_main_role(config, switch_cb=switch_to_sub)
