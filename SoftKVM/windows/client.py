"""
KVM 클라이언트 (Windows 서브 PC)
 - 메인 윈도우(서버)에서 넘어온 마우스·키보드 입력을 이 PC에서 재현합니다.
 - 설정 창에서 같은 와이파이의 메인 PC를 자동으로 찾아 연결합니다.
 - exe는 메인 PC에 새 버전이 생기면 스스로 받아서 업데이트합니다.

실행:  KVM-Client.exe  (또는 서브PC실행.bat)
"""
import base64
import ctypes
import hashlib
import json
import os
import socket
import sys
import subprocess
import threading
import time
from ctypes import wintypes

import pyperclip

from kvm_common import ProfileStore, current_ssid, load_json, save_json, MacroOverlay, overlay_text, overlay_pos

if sys.platform != "win32":
    sys.exit("이 클라이언트는 Windows 전용입니다.")

user32 = ctypes.windll.user32
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    user32.SetProcessDPIAware()

HERE = os.path.dirname(sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__))
# 설정·기록은 exe 위치와 상관없이 항상 같은 곳(%LOCALAPPDATA%\KVM)에 저장
APP_DIR = os.path.join(os.environ.get("LOCALAPPDATA") or HERE, "KVM")
os.makedirs(APP_DIR, exist_ok=True)
CONFIG_PATH = os.path.join(APP_DIR, "client_config.json")
_old_cfg = os.path.join(HERE, "client_config.json")
if not os.path.exists(CONFIG_PATH) and os.path.exists(_old_cfg):
    try:
        with open(_old_cfg, "rb") as _src, open(CONFIG_PATH, "wb") as _dst:
            _dst.write(_src.read())
    except OSError:
        pass


class _Log:
    """exe는 콘솔이 없어서, 출력 내용을 kvm-client.log에 남김"""
    def __init__(self, stream):
        self.stream = stream
        self.file = open(os.path.join(APP_DIR, "kvm-client.log"), "a", encoding="utf-8", buffering=1)

    def write(self, s):
        if self.stream:
            try:
                self.stream.write(s)
            except Exception:
                pass
        self.file.write(s)

    def flush(self):
        pass


sys.stdout = _Log(sys.stdout)
FROZEN = getattr(sys, "frozen", False)
DISCOVER_PORT = 24800


def resource(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


def read_version():
    for p in (resource("VERSION.txt"), os.path.join(os.path.dirname(HERE), "VERSION.txt"),
              os.path.join(HERE, "VERSION.txt")):
        try:
            with open(p, encoding="utf-8") as f:
                return f.read().strip()
        except OSError:
            pass
    return "0.0.0"


VERSION = read_version()


def vtuple(v):
    try:
        return tuple(int(x) for x in str(v).strip().split("."))
    except ValueError:
        return (0,)

# ---------------------------------------------------------------- SendInput 구조체
INPUT_MOUSE, INPUT_KEYBOARD = 0, 1
MOUSEEVENTF_MOVE, MOUSEEVENTF_ABSOLUTE, MOUSEEVENTF_VIRTUALDESK = 0x0001, 0x8000, 0x4000
MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP = 0x0002, 0x0004
MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP = 0x0008, 0x0010
MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP = 0x0020, 0x0040
MOUSEEVENTF_XDOWN, MOUSEEVENTF_XUP = 0x0080, 0x0100
MOUSEEVENTF_WHEEL, MOUSEEVENTF_HWHEEL = 0x0800, 0x1000
KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP = 0x0001, 0x0002
ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD)]


class _U(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("u", _U)]


def send_inputs(*items):
    arr = (INPUT * len(items))(*items)
    user32.SendInput(len(items), arr, ctypes.sizeof(INPUT))


def mouse_input(flags, dx=0, dy=0, data=0):
    i = INPUT(type=INPUT_MOUSE)
    i.u.mi = MOUSEINPUT(dx, dy, ctypes.c_ulong(data & 0xFFFFFFFF).value, flags, 0, 0)
    return i


# 방향키·Home/End·Insert/Delete·오른쪽 Ctrl/Alt·Win 키 등은 "확장 키"로 보내야 정확히 동작
EXTENDED = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, 0x5B, 0x5C, 0x5D,
            0xA3, 0xA5, 0x6F, 0x90, 0x2C}


def key_input(vk, down):
    flags = 0 if down else KEYEVENTF_KEYUP
    if vk in EXTENDED:
        flags |= KEYEVENTF_EXTENDEDKEY
    scan = user32.MapVirtualKeyW(vk, 0)
    i = INPUT(type=INPUT_KEYBOARD)
    i.u.ki = KEYBDINPUT(vk, scan, flags, 0, 0)
    return i


def virtual_screen():
    return (user32.GetSystemMetrics(76), user32.GetSystemMetrics(77),
            user32.GetSystemMetrics(78), user32.GetSystemMetrics(79))


BTN_FLAGS = {
    "l": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP, 0),
    "r": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP, 0),
    "m": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP, 0),
    "x1": (MOUSEEVENTF_XDOWN, MOUSEEVENTF_XUP, 1),
    "x2": (MOUSEEVENTF_XDOWN, MOUSEEVENTF_XUP, 2),
}


# ---------------------------------------------------------------- 입력 재현
class WinInput:
    def __init__(self):
        self.l, self.t, self.w, self.h = virtual_screen()
        self.buttons = set()
        self.keys = set()
        self.last_clip = None

    def move(self, x, y):
        # 가상 화면 전체 기준 0~65535 절대 좌표
        ax = int((x + 0.5) * 65536 / self.w)
        ay = int((y + 0.5) * 65536 / self.h)
        send_inputs(mouse_input(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, ax, ay))

    def button(self, b, down):
        if b not in BTN_FLAGS:
            return
        fdown, fup, data = BTN_FLAGS[b]
        (self.buttons.add if down else self.buttons.discard)(b)
        send_inputs(mouse_input(fdown if down else fup, data=data))

    def wheel(self, dx, dy):
        if dy:
            send_inputs(mouse_input(MOUSEEVENTF_WHEEL, data=dy))
        if dx:
            send_inputs(mouse_input(MOUSEEVENTF_HWHEEL, data=dx))

    def key(self, vk, down):
        # 윈도우끼리는 키 코드가 같아서 그대로 재현 (한/영 키 포함)
        (self.keys.add if down else self.keys.discard)(vk)
        send_inputs(key_input(vk, down))

    def release_all(self):
        for b in list(self.buttons):
            self.button(b, False)
        for vk in list(self.keys):
            send_inputs(key_input(vk, False))
        self.keys.clear()

    def size(self):
        self.l, self.t, self.w, self.h = virtual_screen()
        return self.w, self.h

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
        try:
            pyperclip.copy(text)
        except Exception:
            pass


# ---------------------------------------------------------------- 설정
def load_config():
    cfg = {"host": "", "port": 24800, "key": "", "name": socket.gethostname().lower(), "show_on_start": True}
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            cfg.update(json.load(f))
    except (OSError, ValueError):
        pass
    return cfg


class _SPS(ctypes.Structure):
    _fields_ = [("ACLineStatus", ctypes.c_ubyte), ("BatteryFlag", ctypes.c_ubyte), ("BatteryLifePercent", ctypes.c_ubyte),
                ("SystemStatusFlag", ctypes.c_ubyte), ("BatteryLifeTime", ctypes.c_ulong), ("BatteryFullLifeTime", ctypes.c_ulong)]


_bat = {"at": 0.0, "v": None}


def battery():
    """노트북 배터리 (잔량 %, 충전 중) · 배터리가 없는 데스크톱이면 None"""
    if time.time() - _bat["at"] < 30:
        return _bat["v"]
    _bat["at"] = time.time()
    try:
        sps = _SPS()
        ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(sps))
        if sps.BatteryFlag & 128 or sps.BatteryLifePercent > 100:
            _bat["v"] = None
        else:
            _bat["v"] = (int(sps.BatteryLifePercent), sps.ACLineStatus == 1)
    except Exception:
        _bat["v"] = None
    return _bat["v"]


MACRO_PATH = os.path.join(APP_DIR, "my_macros.json")   # 이 PC에서 녹화한 매크로 (메인일 때 쓰는 macros.json과 따로)


def load_macros():
    try:
        with open(MACRO_PATH, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_macros(data):
    try:
        tmp = MACRO_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
        os.replace(tmp, MACRO_PATH)
    except OSError as e:
        print(f"[매크로] 저장 실패: {e}")


def save_config(cfg):
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)


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
    register_autostart("KVM-Client", on)


def get_autostart():
    return is_autostart("KVM-Client")


def discover(timeout=1.2):
    """같은 와이파이의 KVM 서버 찾기 (UDP 방송)"""
    found = {}
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(0.3)
    try:
        for _ in range(2):
            s.sendto(b"KVM-DISCOVER", ("255.255.255.255", DISCOVER_PORT))
        end = time.time() + timeout
        while time.time() < end:
            try:
                data, addr = s.recvfrom(2048)
                info = json.loads(data.decode("utf-8"))
                if info.get("kvm"):
                    found[addr[0]] = {"ip": addr[0], "name": info.get("host", ""), "port": info.get("port", 24800),
                                      "version": info.get("version", "")}
            except (socket.timeout, ValueError):
                pass
    except OSError:
        pass
    finally:
        s.close()
    return list(found.values())


def clean_env():
    """exe가 자기 자신을 다시 실행할 때 이전 실행의 임시 폴더 정보를 물려주지 않도록"""
    env = {k: v for k, v in os.environ.items() if not k.startswith(("_PYI", "_MEIPASS"))}
    env["PYINSTALLER_RESET_ENVIRONMENT"] = "1"
    return env


# ---------------------------------------------------------------- 연결 담당
class Core:
    def __init__(self, embedded=False):
        self.embedded = embedded      # 메인 PC 프로그램 안에서 서브로 쓰는 중 (이 exe는 바꿔 끼우지 않음)
        self.cfg = load_config()
        self.pc = WinInput()
        self.state, self.text = "idle", "메인 PC를 선택하고 연결을 누르세요"
        self.sock = None
        self.on_exit = None
        self.update_text = ""
        self.macro = {}               # 메인 PC가 보내 준 매크로 상태 (이 PC에서 녹화한 것만)
        self.toast = ("", 0.0)
        self.send_fn = None
        self.send_lock = threading.Lock()
        self.overlay = None
        self.bat_overlay = None
        threading.Thread(target=self._battery_loop, daemon=True).start()
        self.paused = False
        self.notify = lambda text: None      # 트레이 알림 (설정 창이 준비되면 연결)
        self.server_name, self.side, self.peers = "", "", []
        self.active = False          # 지금 메인 PC가 이 PC를 조작 중인지
        self.since = 0.0
        threading.Thread(target=self.loop, daemon=True).start()

    def set(self, state, text):
        self.state, self.text = state, text

    notify_cb = None

    def update_battery(self):
        """이 PC 배터리를 이 PC 화면에만 표시 (위치·켜기는 이 창의 설정 탭에서)"""
        b = battery()
        on = self.cfg.get("battery_overlay", True) and b and self.state == "connected"
        if not on:
            if self.bat_overlay:
                self.bat_overlay.show("")
            return
        lv, chg = b
        pos = self.cfg.get("battery_pos", "tr")
        mpos = overlay_pos(self.macro) if self.macro and self.macro.get("overlay", True) and overlay_text(self.macro)[0] else None
        try:
            if self.bat_overlay is None:
                self.bat_overlay = MacroOverlay()
            self.bat_overlay.show(f"🔋  {lv}%{' ⚡' if chg else ''}", "rec" if lv <= 20 and not chg else "idle",
                                  pos, 36 if mpos == pos else 0)
        except Exception as e:
            print(f"[배터리 표시] 오류: {e}")

    def _battery_loop(self):
        while True:
            time.sleep(5)
            _bat["at"] = 0 if time.time() - _bat["at"] > 30 else _bat["at"]
            self.update_battery()

    def macro_cmd(self, op, **kw):
        if not self.send_fn or self.state != "connected":
            return False
        try:
            self.send_fn({"t": "macro", "op": op, **kw})
            return True
        except OSError:
            return False

    def reconnect(self):
        try:
            if self.sock:
                self.sock.close()
        except OSError:
            pass

    def loop(self):
        while True:
            if self.paused:
                self.set("paused", "연결을 멈췄어요 · 저장하고 연결을 누르면 다시 연결해요")
                time.sleep(0.5)
                continue
            if not self.cfg.get("host"):
                self.set("idle", "메인 PC를 선택하고 연결을 누르세요")
                time.sleep(0.5)
                continue
            host, port = self.cfg["host"], int(self.cfg.get("port", 24800))
            try:
                self.set("connecting", f"{host} 에 연결 중…")
                sock = socket.create_connection((host, port), timeout=4)
                sock.settimeout(7)        # 메인 PC 신호(2초마다)가 7초 없으면 끊긴 것으로 보고 다시 연결
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                self.sock = sock
                self.session(sock)
                try:
                    sock.close()
                except OSError:
                    pass
            except OSError as e:
                if self.state != "rejected":
                    self.set("error", f"메인 PC에 연결할 수 없어요 · 서버가 켜져 있는지 확인하세요")
                print(f"[연결 실패] {e}")
            self.pc.release_all()
            self.sock = None
            self.active = False
            if self.overlay:
                self.overlay.show("")       # 연결이 끊기면 매크로 표시도 숨김
            if self.bat_overlay:
                self.bat_overlay.show("")
            for _ in range(6 if self.state == "rejected" else 2):
                time.sleep(0.5)

    def session(self, sock):
        def send(obj):
            data = (json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8")
            with self.send_lock:
                sock.sendall(data)
        self.send_fn = send

        w, h = self.pc.size()
        hello = {"t": "hello", "name": self.cfg["name"], "key": self.cfg.get("key", ""), "w": w, "h": h,
                 "version": VERSION,
                 "platform": "windows-embedded" if self.embedded else ("windows-exe" if FROZEN else "windows")}
        send(hello)
        print(f"[연결] {self.cfg['host']} v{VERSION} ({hello['platform']})")
        self.since = time.time()
        self.server_name = self.cfg["host"]
        self.set("connected", f"연결됨 · 메인 PC {self.cfg['host']}")
        last_size = (w, h)
        for line in sock.makefile("r", encoding="utf-8"):
            m = json.loads(line)
            t = m.get("t")
            if t == "move":
                self.pc.move(m["x"], m["y"])
            elif t == "ping":
                pong = {"t": "pong", "ts": m.get("ts")}
                b = battery()
                if b:
                    pong["bat"], pong["chg"] = b
                send(pong)
            elif t == "btn":
                self.pc.button(m["b"], m["d"])
            elif t == "wheel":
                self.pc.wheel(m["dx"], m["dy"])
            elif t == "key":
                self.pc.key(m["vk"], m["d"])
            elif t == "welcome":
                self.server_name, self.side = m.get("server", ""), m.get("side", "")
                # 이 PC에 보관 중인 매크로를 메인 PC로 (실행은 메인 PC가, 보관은 이 PC가)
                send({"t": "macro_lib", "data": load_macros()})
                send({"t": "macro", "op": "state"})
            elif t == "rename":            # 메인 PC 설정 창에서 이 PC 이름을 바꿈
                self.cfg["name"] = str(m.get("name") or self.cfg.get("name"))
                save_config(self.cfg)
                print(f"[이름] '{self.cfg['name']}'(으)로 바뀜 · 다시 연결")
                return
            elif t == "macro_store":
                save_macros(m.get("data") or {})
            elif t == "macro_state":
                self.macro = m
                try:      # 게임 화면 위에도 지금 매크로 이름 표시
                    if self.overlay is None:
                        self.overlay = MacroOverlay()
                    self.overlay.show(*overlay_text(m), overlay_pos(m))
                    self.update_battery()          # 매크로 표시와 같은 자리면 겹치지 않게
                except Exception as e:
                    print(f"[매크로 표시] 오류: {e}")
            elif t == "toast":
                self.toast = (m.get("text", ""), time.time())
                if self.notify_cb:
                    try:
                        self.notify_cb(m.get("text", ""))
                    except Exception:
                        pass
            elif t == "layout":
                self.side = m.get("side", "")
            elif t == "peers":
                self.peers = m.get("names", [])
            elif t == "enter":
                self.active = True
                if self.pc.size() != last_size:
                    last_size = self.pc.size()
                    send({"t": "size", "w": last_size[0], "h": last_size[1]})
                self.pc.move(m["x"], m["y"])
            elif t == "leave":
                self.active = False
                if not m.get("keep"):          # 매크로 실행 중이면 눌린 키·버튼을 그대로 둠
                    self.pc.release_all()
                text = self.pc.clip_get()
                if text:
                    send({"t": "clip", "text": text})
            elif t == "clip":
                self.pc.clip_set(m["text"])
            elif t == "exe_info" and FROZEN and not self.embedded:
                print(f"[업데이트 확인] 서버 v{m.get('version')} / 이 PC v{VERSION}")
                tried = self.cfg.get("update_try") or {}
                if vtuple(m.get("version")) > vtuple(VERSION):
                    if tried.get("version") == m.get("version") and time.time() - tried.get("time", 0) < 600:
                        # 방금 설치했는데도 버전이 그대로면 교체가 실패한 것 → 반복하지 않음
                        self.update_text = f"v{m['version']} 자동 설치 실패 · 메인 PC의 KVM-Client.exe로 교체해 주세요"
                        print("[업데이트] 직전 설치가 적용되지 않아 건너뜀")
                    else:
                        self.update_text = f"v{m['version']} 받는 중…"
                        self.notify(f"새 버전 v{m['version']}을 받는 중이에요. 잠시 후 자동으로 다시 켜져요.")
                        self.pending_version = m.get("version")
                        send({"t": "exe_get"})
            elif t == "exe_data" and FROZEN:
                self.install_exe(base64.b64decode(m["b64"]), getattr(self, "pending_version", "?"))
            elif t == "client_hash" and not FROZEN:
                if m.get("hash") and m["hash"] != file_hash(os.path.abspath(__file__)):
                    send({"t": "file_get", "path": "windows/client.py"})
            elif t == "file_data" and not FROZEN:
                self.install_py(base64.b64decode(m["b64"]))
            elif t == "error":
                self.set("rejected", "비밀번호가 맞지 않아요 · 메인 PC 설정 창의 비밀번호를 확인하세요")
                return
        self.set("error", "메인 PC와 연결이 끊겼어요 · 다시 연결하는 중")

    # --- 자동 업데이트
    def install_exe(self, data, version):
        exe = sys.executable
        new = exe + ".new"
        with open(new, "wb") as f:
            f.write(data)
        self.cfg["update_try"] = {"version": version, "time": time.time()}
        save_config(self.cfg)
        self.update_text = "업데이트 설치 중…"
        print(f"[업데이트] v{version} 설치 → {exe}")
        bat = os.path.join(APP_DIR, "kvm_client_update.bat")
        # 이전 실행이 완전히 끝나 exe가 풀릴 때까지 최대 15초 동안 교체를 다시 시도
        with open(bat, "w", encoding="utf-8") as f:
            f.write("@echo off\r\n"
                    "for /l %%i in (1,1,15) do (\r\n"
                    f'  timeout /t 1 /nobreak >nul\r\n'
                    f'  move /y "{new}" "{exe}" >nul 2>&1 && goto done\r\n'
                    ")\r\n"
                    ":done\r\n"
                    f'explorer.exe "{exe}"\r\n')
        self.pc.release_all()
        subprocess.Popen(["cmd", "/c", bat], creationflags=0x08000000, env=clean_env())
        self.quit()

    def install_py(self, data):
        path = os.path.abspath(__file__)
        with open(path + ".new", "wb") as f:
            f.write(data)
        os.replace(path + ".new", path)
        self.pc.release_all()
        subprocess.Popen([sys.executable, path], env=clean_env())
        self.quit()

    def quit(self):
        global _single_instance
        if _single_instance:
            ctypes.windll.kernel32.CloseHandle(_single_instance)
            _single_instance = None
        if self.on_exit:
            try:
                self.on_exit()
            except Exception:
                pass
        os._exit(0)


def file_hash(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


# ---------------------------------------------------------------- 설정 창
class Api:
    def __init__(self, core, switch_cb=None, main_dir=None):
        self._main_dir = main_dir or APP_DIR      # 메인으로 쓸 때의 설정이 있는 폴더
        self._c = core
        self._window = None
        self._icon = None
        self._switch_cb = switch_cb
        self._prof = ProfileStore(os.path.join(APP_DIR, "client_profiles.json"))
        self._ssid = ""
        threading.Thread(target=self._watch_ssid, daemon=True).start()

    # --- 연결 프로필 (집·회사의 메인 PC 주소·비밀번호를 저장했다가 불러오기)
    def profile_save(self, name):
        c = self._c.cfg
        info = {k: c.get(k) for k in ("host", "port", "key", "name")}
        return self._prof.save(name, info, current_ssid())

    def profile_load(self, name):
        info = self._prof.get(name)
        if info is None:
            return "없는 프로필이에요"
        c = self._c
        for k, v in info.items():
            if v is not None:
                c.cfg[k] = v
        c.cfg["profile_current"] = name
        save_config(c.cfg)
        c.paused = False
        c.set("connecting", f"{c.cfg.get('host')} 에 연결 중…")
        c.reconnect()
        print(f"[프로필] '{name}' 불러옴 → {c.cfg.get('host')}")
        return "ok"

    def profile_delete(self, name):
        r = self._prof.delete(name)
        if r == "ok" and self._c.cfg.get("profile_current") == name:
            self._c.cfg.pop("profile_current", None)
            save_config(self._c.cfg)
        return r

    def profile_rename(self, old, new):
        r = self._prof.rename(old, new)
        if r == "ok" and self._c.cfg.get("profile_current") == old:
            self._c.cfg["profile_current"] = new
            save_config(self._c.cfg)
        return r

    def switch_role(self):
        if not self._switch_cb:
            return False
        threading.Thread(target=self._switch_cb, daemon=True).start()
        return True

    # --- '메인' 화면: 이 PC를 메인으로 쓸 때 불러올 연결 프로필 (적용을 눌러야 바뀜)
    def main_state(self):
        def describe(d):
            n = len([k for k, v in (d.get("layout") or {}).items() if "w" in v])
            return f"기기 배치 {n}개"
        cfg = load_json(os.path.join(self._main_dir, "config.json"))
        store = ProfileStore(os.path.join(self._main_dir, "profiles.json"))
        return {"profiles": store.entries(describe), "current": cfg.get("profile_current"),
                "has_config": bool(cfg), "pc": socket.gethostname()}

    def main_apply(self, pname=""):
        """메인으로 전환 (적용): 고른 프로필(없으면 지난번 메인 설정 그대로)로 다시 켬"""
        path = os.path.join(self._main_dir, "config.json")
        cfg = load_json(path)
        if pname:
            cfg["profile_pending"] = pname
        if cfg:
            save_json(path, cfg)
        return "ok" if self.switch_role() else "이 프로그램에서는 바꿀 수 없어요"

    def _watch_ssid(self):
        last = None
        while True:
            ssid = current_ssid()
            self._ssid = ssid
            if ssid != last:
                if last is not None and self._c.cfg.get("profile_auto", True):
                    name = self._prof.for_ssid(ssid)
                    if name and name != self._c.cfg.get("profile_current"):
                        self.profile_load(name)
                        try:
                            self._icon.notify(f"와이파이 '{ssid}' → '{name}' 연결 정보를 불러왔어요", "KVM")
                        except Exception:
                            pass
                last = ssid
            time.sleep(15)

    def _profiles_state(self):
        return {"list": self._prof.entries(lambda d: d.get("host") or ""),
                "current": self._c.cfg.get("profile_current"), "ssid": self._ssid,
                "auto": self._c.cfg.get("profile_auto", True)}

    def get_state(self):
        c = self._c
        return {"state": c.state, "text": c.text, "update": c.update_text, "version": VERSION,
                "server": c.server_name, "side": c.side, "peers": c.peers, "active": c.active,
                "since": int(time.time() - c.since) if c.state == "connected" else 0,
                "host": c.cfg.get("host", ""), "name": c.cfg.get("name", ""), "has_key": bool(c.cfg.get("key")),
                "autostart": get_autostart(), "show_on_start": c.cfg.get("show_on_start", True),
                "pc": socket.gethostname(), "macro": c.macro if c.state == "connected" else {},
                "toast": c.toast[0] if time.time() - c.toast[1] < 3 else "", "toast_at": c.toast[1],
                "profiles": self._profiles_state(),
                "battery": {"has": battery() is not None, "on": c.cfg.get("battery_overlay", True),
                            "pos": c.cfg.get("battery_pos", "tr"), "level": (battery() or (None,))[0]},
                "role": {"can_switch": bool(self._switch_cb), "embedded": c.embedded},
                "main": self.main_state() if self._switch_cb else None}

    def macro(self, op, args=None):
        """매크로 명령. 녹화·실행은 이 창을 내리고 이전 화면에서 시작"""
        args = dict(args or {})
        if op in ("record", "play") and not (self._c.macro.get("recording") or self._c.macro.get("playing")):
            try:
                self._window.minimize()
            except Exception:
                pass
            args.setdefault("delay", 0.8)
        return self._c.macro_cmd(op, **args)

    def discover(self):
        return discover()

    def save(self, host, key, name):
        c = self._c
        c.cfg["host"] = str(host).strip()
        if str(key).strip():
            c.cfg["key"] = str(key).strip()
        c.cfg["name"] = str(name).strip() or socket.gethostname().lower()
        if not c.cfg.get("autostart_init"):
            c.cfg["autostart_init"] = True
            try:
                set_autostart(True)
            except Exception as e:
                print(f"[자동 실행] 설정 오류: {e}")
        save_config(c.cfg)
        c.paused = False
        c.set("connecting", f"{c.cfg['host']} 에 연결 중…")
        c.reconnect()
        return True

    def disconnect(self):
        self._c.paused = True
        self._c.reconnect()
        return True

    def set_option(self, k, v):
        if k == "autostart":
            set_autostart(bool(v))
        elif k == "battery_overlay":
            self._c.cfg["battery_overlay"] = bool(v)
            save_config(self._c.cfg)
            self._c.update_battery()
        elif k == "battery_pos":
            if v in ("tl", "ml", "bl", "tr", "mr", "br", "tc", "bc"):
                self._c.cfg["battery_pos"] = v
                save_config(self._c.cfg)
                self._c.update_battery()
        elif k == "profile_auto":
            self._c.cfg["profile_auto"] = bool(v)
            save_config(self._c.cfg)
        elif k == "show_on_start":
            self._c.cfg["show_on_start"] = bool(v)
            save_config(self._c.cfg)
        return True

    def minimize(self):
        self._window.minimize()

    def hide(self):
        self._window.hide()

    def show(self):
        self._window.show()
        self._window.restore()

    def quit(self):
        threading.Thread(target=self._c.quit, daemon=True).start()

    def _on_closing(self):
        self._window.hide()
        return False


def tray_image(color):
    from PIL import Image, ImageDraw
    base = load_tray_icon("kvm-client.ico", lambda: None)
    if base is not None:
        img = base.copy()
        d = ImageDraw.Draw(img)
        d.ellipse((40, 40, 63, 63), fill=(17, 19, 25, 255))
        d.ellipse((44, 44, 59, 59), fill=color)
        return img
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((6, 12, 58, 46), 7, fill=(91, 140, 255))
    d.rounded_rectangle((24, 48, 40, 53), 2, fill=(91, 140, 255))
    d.ellipse((44, 2, 62, 20), fill=color)
    return img


def run_ui(core, switch_cb=None, show_name="KVM-Client", main_dir=None):
    try:   # 입력 재현이 다른 프로그램에 밀리지 않게
        ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(), 0x80)
    except Exception:
        pass
    import pystray
    import webview

    api = Api(core, switch_cb, main_dir)
    with open(resource("client_ui.html"), encoding="utf-8") as f:
        html = f.read()
    show = core.cfg.get("show_on_start", True) or not core.cfg.get("host")
    win = webview.create_window("KVM 서브 PC", html=html, js_api=api, width=460, height=700,
                                min_size=(420, 600), frameless=True, easy_drag=False,
                                background_color="#0a0b0f", hidden=not show)
    api._window = win
    win.events.closing += api._on_closing

    colors = {"connected": (61, 220, 151), "connecting": (245, 184, 74)}
    api._icon = pystray.Icon("kvm-client", tray_image((138, 144, 156)), "KVM 서브 PC",
                             menu=pystray.Menu(pystray.MenuItem("설정 열기", lambda *_: api.show(), default=True),
                                               pystray.MenuItem("종료", lambda *_: api.quit())))
    api._icon.run_detached()
    core.on_exit = api._icon.stop
    core.notify_cb = lambda text: api._icon.notify(text, "KVM 매크로")

    def notify(text):
        try:
            api._icon.notify(text, "KVM 서브 PC")
        except Exception:
            pass
    core.notify = notify

    # 방금 자동 업데이트로 다시 켜진 거라면 알려줌
    tried = core.cfg.get("update_try") or {}
    if tried.get("version") == VERSION:
        core.cfg.pop("update_try", None)
        save_config(core.cfg)
        core.update_text = f"v{VERSION}로 업데이트했어요"
        threading.Timer(2.0, lambda: notify(f"v{VERSION}로 업데이트했어요")).start()
        threading.Timer(15.0, lambda: setattr(core, "update_text", "")).start()

    def tray_status():
        last = None
        while True:
            if core.state != last:
                last = core.state
                api._icon.icon = tray_image(colors.get(core.state, (255, 107, 107) if core.state in ("error", "rejected") else (138, 144, 156)))
                api._icon.title = f"KVM 서브 PC - {core.text}"
            time.sleep(1)

    threading.Thread(target=tray_status, daemon=True).start()
    watch_show_requests(show_name, api.show)       # 바로가기를 다시 누르면 이 창이 열림

    def on_loaded():
        if show:
            api.show()
    win.events.loaded += on_loaded
    webview.start()
    core.quit()


def self_install():
    """다운로드·임시 폴더에서 실행해도 %LOCALAPPDATA%\\KVM 에 설치하고 바로가기를 만든 뒤 거기서 다시 실행"""
    if not FROZEN:
        return False
    target = os.path.join(APP_DIR, "KVM-Client.exe")
    if os.path.normcase(os.path.abspath(sys.executable)) == os.path.normcase(target):
        return False
    try:
        with open(sys.executable, "rb") as src, open(target + ".new", "wb") as dst:
            dst.write(src.read())
        os.replace(target + ".new", target)
    except OSError as e:
        print(f"[설치] 실패: {e}")
        return False
    links = [os.path.join(os.environ.get("APPDATA", ""), r"Microsoft\Windows\Start Menu\Programs", "KVM 서브 PC.lnk"),
             os.path.join(os.path.expanduser("~"), "Desktop", "KVM 서브 PC.lnk")]
    ps = ";".join(f"$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{l}');$s.TargetPath='{target}';$s.Save()"
                  for l in links if os.path.isdir(os.path.dirname(l)))
    subprocess.run(["powershell", "-NoProfile", "-Command", ps], creationflags=0x08000000)
    if get_autostart():
        _exe = sys.executable
        sys.executable = target    # 자동 실행 경로도 설치된 위치로
        try:
            set_autostart(True)
        finally:
            sys.executable = _exe
    print(f"[설치] {target}")
    return target


_single_instance = None

def run_sub_role(embedded=False, switch_cb=None, show_name="KVM-Client", main_dir=None):
    """서브 역할 실행 (메인 PC 프로그램 안에서도 이걸로 서브가 됨)"""
    run_ui(Core(embedded=embedded), switch_cb=switch_cb, show_name=show_name, main_dir=main_dir)


def _restart_self():
    global _single_instance
    if _single_instance:
        ctypes.windll.kernel32.CloseHandle(_single_instance)
        _single_instance = None
    relaunch([sys.executable] + ([] if FROZEN else [os.path.abspath(__file__)]))
    os._exit(0)


def switch_to_main():
    cfg = load_config()
    cfg["role"] = "main"
    save_config(cfg)
    print("[역할] 메인으로 전환")
    _restart_self()


def switch_to_sub():
    cfg = load_config()
    cfg["role"] = "sub"
    save_config(cfg)
    print("[역할] 서브로 전환")
    _restart_self()


if __name__ == "__main__":
    set_app_id("KVM.Client")
    _single_instance = single_instance("KVM-Client")   # 이미 켜져 있으면 그 창을 열고 종료
    if not _single_instance:
        sys.exit(0)
    print(f"\n[시작] v{VERSION} {'로그인 자동 실행' if STARTED_AT_LOGIN else '직접 실행'} · {sys.executable}")
    installed = self_install()
    if installed:
        ctypes.windll.kernel32.CloseHandle(_single_instance)
        relaunch([installed])
        user32.MessageBoxW(None, "KVM 서브 PC를 이 PC에 설치했어요.\n\n시작 메뉴와 바탕화면의 'KVM 서브 PC' 바로가기로 실행하면 되고,\n"
                                 "받은 파일은 지워도 괜찮아요.", "KVM 서브 PC", 0x40)
        os._exit(0)
    try:
        if get_autostart():
            set_autostart(True)       # 경로·'시작 앱' 허용을 매번 다시 확인
    except Exception as e:
        print(f"[자동 실행] 확인 오류: {e}")
    if load_config().get("role") == "main":
        # 이 PC를 메인으로 쓰는 중 → 다른 기기를 조작하는 서버로 시작
        print("[역할] 메인으로 시작")
        import server as main_role
        main_role.VERSION = VERSION
        cfg = main_role.load_config()
        if not os.path.exists(main_role.CONFIG_PATH):
            main_role.save_config(cfg)
        main_role.run_main_role(cfg, embedded=True, switch_cb=switch_to_sub, show_name="KVM-Client")
    else:
        run_sub_role(switch_cb=switch_to_main)
