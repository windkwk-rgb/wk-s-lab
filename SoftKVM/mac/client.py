"""
KVM 클라이언트 (macOS)
 - 윈도우 서버에서 넘어온 마우스·키보드 입력을 맥에서 재현합니다.

설치:  pip3 install pyobjc-framework-Quartz pyobjc-framework-Cocoa pyperclip
실행:  KVM 맥 실행.command 더블클릭  (또는 python3 client.py)
권한:  시스템 설정 > 개인정보 보호 및 보안 > 손쉬운 사용 에서
       이 스크립트를 실행하는 앱(터미널/iTerm 등)을 허용해야 합니다.
"""
import base64
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import time

import pyperclip
import Quartz as Q

# ---------------------------------------------------------------- 설정
NAME = "mac"                 # 서버 config.json의 screens 이름과 같아야 함
KEY = "change-me"            # 서버와 같은 비밀번호
PORT = 24800
SWAP_CTRL_CMD = True         # 윈도우 Ctrl → 맥 Cmd, 윈도우 Win키 → 맥 Control
INVERT_SCROLL = False        # 스크롤 방향이 반대로 느껴지면 True
HANGUL_TOGGLE = "ctrl+space" # 한/영 키를 누르면 보낼 조합: "ctrl+space" | "capslock" | "none"
SMART_SHORTCUTS = True       # 윈도우 단축키를 맥의 같은 기능으로 바꿔줌 (아래 SHORTCUT 표 참고)
AUTO_UPDATE = True           # 서버에 새 버전이 있으면 이 파일을 자동으로 바꾸고 다시 시작

# ---------------------------------------------------------------- Windows VK → macOS 키코드
VK_TO_MAC = {
    # 문자
    0x41: 0, 0x42: 11, 0x43: 8, 0x44: 2, 0x45: 14, 0x46: 3, 0x47: 5, 0x48: 4, 0x49: 34,
    0x4A: 38, 0x4B: 40, 0x4C: 37, 0x4D: 46, 0x4E: 45, 0x4F: 31, 0x50: 35, 0x51: 12,
    0x52: 15, 0x53: 1, 0x54: 17, 0x55: 32, 0x56: 9, 0x57: 13, 0x58: 7, 0x59: 16, 0x5A: 6,
    # 숫자
    0x30: 29, 0x31: 18, 0x32: 19, 0x33: 20, 0x34: 21, 0x35: 23, 0x36: 22, 0x37: 26,
    0x38: 28, 0x39: 25,
    # 기호
    0xBA: 41, 0xBB: 24, 0xBC: 43, 0xBD: 27, 0xBE: 47, 0xBF: 44, 0xC0: 50,
    0xDB: 33, 0xDC: 42, 0xDD: 30, 0xDE: 39,
    # 편집/이동
    0x08: 51, 0x09: 48, 0x0D: 36, 0x1B: 53, 0x20: 49, 0x2E: 117, 0x2D: 114,
    0x24: 115, 0x23: 119, 0x21: 116, 0x22: 121,
    0x25: 123, 0x27: 124, 0x28: 125, 0x26: 126,
    0x14: 57,
    # F키
    0x70: 122, 0x71: 120, 0x72: 99, 0x73: 118, 0x74: 96, 0x75: 97, 0x76: 98, 0x77: 100,
    0x78: 101, 0x79: 109, 0x7A: 103, 0x7B: 111,
    # 숫자 키패드
    0x60: 82, 0x61: 83, 0x62: 84, 0x63: 85, 0x64: 86, 0x65: 87, 0x66: 88, 0x67: 89,
    0x68: 91, 0x69: 92, 0x6A: 67, 0x6B: 69, 0x6D: 78, 0x6E: 65, 0x6F: 75,
    # 수정키 (아래 SWAP 설정에 따라 덮어씀)
    0x10: 56, 0xA0: 56, 0xA1: 60,
    0x12: 58, 0xA4: 58, 0xA5: 61,
}
if SWAP_CTRL_CMD:
    VK_TO_MAC.update({0x11: 55, 0xA2: 55, 0xA3: 54, 0x5B: 59, 0x5C: 62})
else:
    VK_TO_MAC.update({0x11: 59, 0xA2: 59, 0xA3: 62, 0x5B: 55, 0x5C: 54})

MOD_FLAG = {
    56: Q.kCGEventFlagMaskShift, 60: Q.kCGEventFlagMaskShift,
    59: Q.kCGEventFlagMaskControl, 62: Q.kCGEventFlagMaskControl,
    58: Q.kCGEventFlagMaskAlternate, 61: Q.kCGEventFlagMaskAlternate,
    55: Q.kCGEventFlagMaskCommand, 54: Q.kCGEventFlagMaskCommand,
}
VK_HANGUL = 0x15

# ---------------------------------------------------------------- 입력 재현
BTN = {
    "l": (Q.kCGEventLeftMouseDown, Q.kCGEventLeftMouseUp, Q.kCGMouseButtonLeft),
    "r": (Q.kCGEventRightMouseDown, Q.kCGEventRightMouseUp, Q.kCGMouseButtonRight),
    "m": (Q.kCGEventOtherMouseDown, Q.kCGEventOtherMouseUp, 2),
    "x1": (Q.kCGEventOtherMouseDown, Q.kCGEventOtherMouseUp, 3),
    "x2": (Q.kCGEventOtherMouseDown, Q.kCGEventOtherMouseUp, 4),
}


def post(ev):
    Q.CGEventPost(Q.kCGHIDEventTap, ev)


class Mac:
    def __init__(self):
        b = Q.CGDisplayBounds(Q.CGMainDisplayID())
        self.w, self.h = int(b.size.width), int(b.size.height)
        self.x, self.y = self.w / 2, self.h / 2
        self.buttons = set()
        self.keys = set()
        self.flags = 0
        self.last_click = (0.0, 0, 0, "")  # time, x, y, button
        self.click_count = 1
        self.scroll_acc = [0.0, 0.0]
        self.phys = set()            # 지금 눌린 윈도우 수정키: shift / ctrl / alt / win
        self.swallow = set()         # 단축키로 처리한 키 (뗄 때 무시)
        self.alt_state = None        # None / pending / posted / tab
        self.win_state = None        # None / pending / used / posted
        self.alt_code = 58
        self.win_code = 59
        self.last_clip = None

    # --- 마우스
    def move(self, x, y):
        self.x, self.y = x, y
        if "l" in self.buttons:
            t, btn = Q.kCGEventLeftMouseDragged, Q.kCGMouseButtonLeft
        elif "r" in self.buttons:
            t, btn = Q.kCGEventRightMouseDragged, Q.kCGMouseButtonRight
        elif self.buttons:
            t, btn = Q.kCGEventOtherMouseDragged, 2
        else:
            t, btn = Q.kCGEventMouseMoved, 0
        ev = Q.CGEventCreateMouseEvent(None, t, (x, y), btn)
        Q.CGEventSetFlags(ev, self.flags)
        post(ev)

    def button(self, b, down):
        if b not in BTN:
            return
        t_down, t_up, btn = BTN[b]
        if down:
            now = time.time()
            lt, lx, ly, lb = self.last_click
            close = abs(lx - self.x) < 5 and abs(ly - self.y) < 5
            self.click_count = self.click_count + 1 if (lb == b and now - lt < 0.4 and close) else 1
            self.last_click = (now, self.x, self.y, b)
            self.buttons.add(b)
        else:
            self.buttons.discard(b)
        ev = Q.CGEventCreateMouseEvent(None, t_down if down else t_up, (self.x, self.y), btn)
        Q.CGEventSetIntegerValueField(ev, Q.kCGMouseEventClickState, self.click_count)
        Q.CGEventSetFlags(ev, self.flags)
        post(ev)

    def wheel(self, dx, dy):
        sign = -1 if INVERT_SCROLL else 1
        self.scroll_acc[0] += dy / 120 * 3 * sign   # 한 칸 = 3줄
        self.scroll_acc[1] += -dx / 120 * 3 * sign
        ly, lx = int(self.scroll_acc[0]), int(self.scroll_acc[1])
        if ly == 0 and lx == 0:
            return
        self.scroll_acc[0] -= ly
        self.scroll_acc[1] -= lx
        post(Q.CGEventCreateScrollWheelEvent(None, Q.kCGScrollEventUnitLine, 2, ly, lx))

    # --- 키보드
    def _key(self, code, down):
        if code in MOD_FLAG:
            if down:
                self.flags |= MOD_FLAG[code]
            else:
                self.flags &= ~MOD_FLAG[code]
        ev = Q.CGEventCreateKeyboardEvent(None, code, down)
        Q.CGEventSetFlags(ev, self.flags)
        post(ev)

    # 윈도우 단축키 → 맥 동작 표 (SMART_SHORTCUTS)
    #  Alt+Tab          앱 전환 (Cmd+Tab, Alt 누른 채 Tab 반복·Shift+Tab 역방향)
    #  Alt+F4           앱 종료 (Cmd+Q)          Ctrl+F4      탭/창 닫기 (Cmd+W)
    #  Alt+← / →        뒤로 / 앞으로 (Cmd+[ ])  Ctrl+Tab     브라우저 탭 전환
    #  Win              Spotlight 검색            Win+S, Win+R 검색
    #  Win+D            바탕화면 보기             Win+Tab      Mission Control
    #  Win+E            Finder                    Win+I        시스템 설정
    #  Win+L            화면 잠금                 Win+.        이모지
    #  Win+↑ / ← / →    창 채우기 / 왼쪽 / 오른쪽 반 (macOS 15+)
    #  Win+↓            창 최소화                 Win+M        앱의 모든 창 최소화
    #  Win+N / Win+A    알림 센터 / 제어 센터     Win+Space    입력 소스 전환
    #  Win+Shift+S      영역 캡처 → 클립보드      PrtSc        전체 화면 캡처 → 클립보드
    #  Ctrl+Shift+Esc   활성 상태 보기(작업 관리자)
    #  Ctrl+← / →       단어 단위 이동            Ctrl+↑ / ↓   문단 이동
    #  Ctrl+Backspace   단어 지우기               Ctrl+Delete  뒤 단어 지우기
    #  Home / End       줄 처음 / 끝              Ctrl+Home/End 문서·페이지 맨 위/아래
    #  Ctrl+Y           다시 실행 (Cmd+Shift+Z)   F5           새로 고침 (Cmd+R)
    #  F11              전체 화면                 F2           Finder에서 이름 바꾸기
    #  볼륨·음소거·재생·이전·다음 키              그대로 동작

    def combo(self, code, flags):
        """정해진 수정키 조합으로 키 한 번 누르기 (실제 눌린 수정키 상태와 무관)"""
        for down in (True, False):
            ev = Q.CGEventCreateKeyboardEvent(None, code, down)
            Q.CGEventSetFlags(ev, flags)
            post(ev)

    @staticmethod
    def open_app(*args):
        subprocess.Popen(["open", *args])

    @staticmethod
    def media(key):
        """볼륨·재생 키 (NX 시스템 키)"""
        try:
            from AppKit import NSEvent
        except ImportError:
            return
        for down in (True, False):
            ev = NSEvent.otherEventWithType_location_modifierFlags_timestamp_windowNumber_context_subtype_data1_data2_(
                14, (0, 0), 0xA00 if down else 0xB00, 0, 0, None, 8,
                (key << 16) | ((0xA if down else 0xB) << 8), -1)
            Q.CGEventPost(0, ev.CGEvent())

    @staticmethod
    def frontmost_is_finder():
        try:
            from AppKit import NSWorkspace
            return NSWorkspace.sharedWorkspace().frontmostApplication().bundleIdentifier() == "com.apple.finder"
        except Exception:
            return False

    def shortcut(self, vk):
        """처리했으면 True"""
        CMD, CTRL = Q.kCGEventFlagMaskCommand, Q.kCGEventFlagMaskControl
        OPT, SHIFT, FN = Q.kCGEventFlagMaskAlternate, Q.kCGEventFlagMaskShift, 0x800000
        sh = "shift" in self.phys
        S = SHIFT if sh else 0
        ctrl = "ctrl" in self.phys

        if self.win_state in ("pending", "used"):
            spotlight = lambda: self.combo(49, CMD)
            table = {
                0x44: lambda: self.combo(103, 0),               # D  바탕화면 (F11)
                0x09: lambda: self.combo(126, CTRL),            # Tab Mission Control
                0x45: lambda: self.open_app(os.path.expanduser("~")),   # E Finder
                0x49: lambda: self.open_app("-a", "System Settings"),   # I
                0x4C: lambda: self.combo(12, CTRL | CMD),       # L  잠금
                0x52: spotlight, 0x51: spotlight,               # R, Q
                0xBE: lambda: self.combo(49, CTRL | CMD),       # .  이모지
                0x20: lambda: self.combo(49, CTRL),             # Space 입력 소스
                0x26: lambda: self.combo(3, FN | CTRL),         # ↑ 채우기
                0x25: lambda: self.combo(123, FN | CTRL),       # ← 왼쪽 반
                0x27: lambda: self.combo(124, FN | CTRL),       # → 오른쪽 반
                0x28: lambda: self.combo(46, CMD),              # ↓ 최소화
                0x4D: lambda: self.combo(46, CMD | OPT),        # M 모두 최소화
                0x4E: lambda: self.combo(45, FN),               # N 알림 센터
                0x41: lambda: self.combo(8, FN),                # A 제어 센터
                0x53: (lambda: self.combo(21, CMD | CTRL | SHIFT)) if sh else spotlight,  # Shift+S 캡처 / S 검색
            }
            if vk in table:
                self.win_state = "used"
                table[vk]()
                return True
            return False

        if self.alt_state == "pending":
            if vk == 0x09:                                      # Alt+Tab → Cmd 누른 상태로 전환
                self._key(55, True)
                self.keys.add(55)
                self.alt_state = "tab"
                return False                                    # Tab 자체는 아래에서 Cmd와 함께 전송
            table = {0x73: (12, CMD), 0x25: (33, CMD), 0x27: (30, CMD)}   # F4 종료, ← 뒤로, → 앞으로
            if vk in table:
                self.combo(*table[vk])
                return True
            return False

        if self.alt_state == "tab":
            return False

        if ctrl and sh and vk == 0x1B:                          # Ctrl+Shift+Esc
            self.open_app("-a", "Activity Monitor")
            return True
        if ctrl and SWAP_CTRL_CMD:
            table = {
                0x25: (123, OPT | S), 0x27: (124, OPT | S), 0x26: (126, OPT | S), 0x28: (125, OPT | S),
                0x24: (126, CMD | S), 0x23: (125, CMD | S),     # Ctrl+Home/End
                0x08: (51, OPT), 0x2E: (117, OPT),              # 단어 지우기
                0x59: (6, CMD | SHIFT),                         # Ctrl+Y 다시 실행
                0x09: (48, CTRL | S),                           # Ctrl+Tab 탭 전환
                0x73: (13, CMD),                                # Ctrl+F4 닫기
            }
            if vk in table:
                self.combo(*table[vk])
                return True
            return False

        if not ctrl:
            media = {0xAD: 7, 0xAE: 1, 0xAF: 0, 0xB3: 16, 0xB0: 17, 0xB1: 18}
            if vk in media:
                self.media(media[vk])
                return True
            table = {
                0x24: (0, CTRL | S), 0x23: (14, CTRL | S),      # Home/End → 줄 처음/끝 (Ctrl+A/E)
                0x74: (15, CMD),                                # F5 새로 고침
                0x7A: (3, CMD | CTRL),                          # F11 전체 화면
                0x2C: (20, CMD | CTRL | SHIFT),                 # PrtSc 전체 캡처 → 클립보드
            }
            if vk in table:
                self.combo(*table[vk])
                return True
            if vk == 0x71 and self.frontmost_is_finder():       # F2 이름 바꾸기
                self.combo(36, 0)
                return True
        return False

    def key(self, vk, down):
        if vk == VK_HANGUL:
            if down and HANGUL_TOGGLE == "ctrl+space":
                self.combo(49, Q.kCGEventFlagMaskControl)
            elif HANGUL_TOGGLE == "capslock":
                self._key(57, down)
            return

        # Alt·Win은 다음 키를 보고 결정 (단축키면 그 동작, 아니면 Option·Control로)
        if vk in (0x12, 0xA4, 0xA5):
            if down:
                if self.alt_state is None:
                    self.alt_state = "pending"
                    self.phys.add("alt")
            else:
                st, self.alt_state = self.alt_state, None
                self.phys.discard("alt")
                if st == "tab":
                    self._key(55, False)            # Cmd를 떼야 앱 전환이 확정됨
                    self.keys.discard(55)
                elif st == "posted":
                    self._key(self.alt_code, False)
                    self.keys.discard(self.alt_code)
            return
        if vk in (0x5B, 0x5C):
            if down:
                if self.win_state is None:
                    self.win_state = "pending"
                    self.phys.add("win")
            else:
                st, self.win_state = self.win_state, None
                self.phys.discard("win")
                if st == "pending" and SMART_SHORTCUTS:
                    self.combo(49, Q.kCGEventFlagMaskCommand)   # Win만 눌렀다 떼면 Spotlight
                elif st == "posted":
                    self._key(self.win_code, False)
                    self.keys.discard(self.win_code)
            return
        if vk in (0x10, 0xA0, 0xA1):
            (self.phys.add if down else self.phys.discard)("shift")
        elif vk in (0x11, 0xA2, 0xA3):
            (self.phys.add if down else self.phys.discard)("ctrl")

        if not down and vk in self.swallow:
            self.swallow.discard(vk)
            return
        is_mod = vk in (0x10, 0xA0, 0xA1, 0x11, 0xA2, 0xA3, 0x14)
        if down and not is_mod and SMART_SHORTCUTS and self.shortcut(vk):
            self.swallow.add(vk)
            return
        if down and not is_mod:
            # 단축키가 아니면 미뤄둔 Alt·Win을 진짜 수정키로 누름
            if self.alt_state == "pending":
                self.alt_code = VK_TO_MAC.get(0xA4, 58)
                self._key(self.alt_code, True)
                self.keys.add(self.alt_code)
                self.alt_state = "posted"
            if self.win_state in ("pending", "used"):
                self.win_code = VK_TO_MAC.get(0x5B, 59)
                self._key(self.win_code, True)
                self.keys.add(self.win_code)
                self.win_state = "posted"
        code = VK_TO_MAC.get(vk)
        if code is None:
            return
        (self.keys.add if down else self.keys.discard)(code)
        self._key(code, down)

    # --- 전환
    def release_all(self):
        for b in list(self.buttons):
            self.button(b, False)
        for code in list(self.keys):
            self._key(code, False)
        self.keys.clear()
        self.flags = 0
        self.phys.clear()
        self.swallow.clear()
        self.alt_state = self.win_state = None

    def clip_get(self):
        try:
            text = pyperclip.paste()
        except Exception:
            return None
        if text and text != self.last_clip:
            self.last_clip = text
            return text
        return None

    def clip_set(self, text):
        self.last_clip = text
        pyperclip.copy(text)


# ---------------------------------------------------------------- 자동 업데이트
def my_hash():
    with open(os.path.abspath(__file__), "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def self_update(data, sock, mac):
    path = os.path.abspath(__file__)
    tmp = path + ".new"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)
    print("[업데이트] 완료 · 다시 시작합니다")
    mac.release_all()
    try:
        sock.close()
    finally:
        os.execv(sys.executable, [sys.executable, path] + sys.argv[1:])


# ---------------------------------------------------------------- 메인 루프
_bat = {"at": 0.0, "v": None}


def battery():
    """맥북 배터리 (잔량 %, 충전 중) · 1분마다 한 번만 읽음"""
    if time.time() - _bat["at"] < 60:
        return _bat["v"]
    _bat["at"] = time.time()
    try:
        out = subprocess.run(["pmset", "-g", "batt"], capture_output=True, text=True, timeout=3).stdout
        mm = re.search(r"(\d+)%;\s*([a-zA-Z ]+);", out)
        _bat["v"] = (int(mm.group(1)), mm.group(2).strip() in ("charging", "charged", "finishing charge")) if mm else None
    except Exception:
        _bat["v"] = None
    return _bat["v"]


def run(host):
    global NAME
    mac = Mac()
    while True:
        try:
            print(f"[연결 시도] {host}:{PORT}")
            sock = socket.create_connection((host, PORT), timeout=5)
            sock.settimeout(7)        # 메인 PC 신호(2초마다)가 7초 없으면 다시 연결
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

            def send(obj):
                sock.sendall((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))

            send({"t": "hello", "name": NAME, "key": KEY, "w": mac.w, "h": mac.h, "platform": "mac",
                  "version": my_hash()[:7]})
            print(f"[연결됨] 화면 {mac.w}x{mac.h}")
            for line in sock.makefile("r", encoding="utf-8"):
                m = json.loads(line)
                t = m.get("t")
                if t == "move":
                    mac.move(m["x"], m["y"])
                elif t == "ping":
                    pong = {"t": "pong", "ts": m.get("ts")}
                    b = battery()
                    if b:
                        pong["bat"], pong["chg"] = b
                    send(pong)
                elif t == "btn":
                    mac.button(m["b"], m["d"])
                elif t == "wheel":
                    mac.wheel(m["dx"], m["dy"])
                elif t == "key":
                    mac.key(m["vk"], m["d"])
                elif t == "enter":
                    mac.move(m["x"], m["y"])
                elif t == "leave":
                    mac.release_all()
                    text = mac.clip_get()
                    if text:
                        send({"t": "clip", "text": text})
                elif t == "clip":
                    mac.clip_set(m["text"])
                elif t == "client_hash" and AUTO_UPDATE:
                    if m.get("hash") and m["hash"] != my_hash():
                        print("[업데이트] 새 버전을 받는 중…")
                        send({"t": "file_get", "path": "mac/client.py"})
                elif t == "file_data" and AUTO_UPDATE:
                    self_update(base64.b64decode(m["b64"]), sock, mac)
                elif t == "rename":       # 메인 PC 설정 창에서 이 맥 이름을 바꿈
                    NAME = str(m.get("name") or NAME)
                    try:
                        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mac_config.json")
                        with open(path, encoding="utf-8") as f:
                            c = json.load(f)
                        c["name"] = NAME
                        with open(path, "w", encoding="utf-8") as f:
                            json.dump(c, f, ensure_ascii=False, indent=2)
                    except Exception:
                        pass
                    print(f"[이름] '{NAME}'(으)로 바뀜 · 다시 연결")
                    break
                elif t == "error":
                    print(f"[서버 거부] {m.get('msg')}")
                    sys.exit(1)
            print("[끊김] 서버 연결 종료")
        except (OSError, ValueError) as e:
            print(f"[오류] {e}")
        mac.release_all()
        time.sleep(1)


def load_host():
    """처음 실행 때 서버 IP·비밀번호를 물어보고 mac_config.json에 저장"""
    global KEY, NAME
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mac_config.json")
    cfg = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f)
    if len(sys.argv) > 1:
        cfg["host"] = sys.argv[1]
    if not cfg.get("host"):
        print("처음 실행이네요. 윈도우 메인 PC 설정 창의 '서버 주소'를 입력하세요.")
        cfg["host"] = input("  서버 IP (예: 192.168.0.10): ").strip()
        cfg["key"] = input("  비밀번호 [엔터 = change-me]: ").strip() or "change-me"
        cfg["name"] = input("  이 맥 이름 [엔터 = mac]: ").strip() or "mac"
        with open(path, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
        print(f"저장했습니다: {path}  (바꾸려면 이 파일을 지우세요)\n")
    KEY = cfg.get("key", KEY)
    NAME = cfg.get("name", NAME)
    return cfg["host"]


if __name__ == "__main__":
    run(load_host())
