"""
메인·서브 공통 도우미
 - 연결 프로필: 지금 연결 정보를 이름 붙여 저장했다가 다시 불러오기 (집·회사 등)
 - 지금 연결된 와이파이 이름 (프로필 자동 불러오기용)
"""
import json
import os
import re
import subprocess
import time

_NO_WINDOW = 0x08000000


def current_ssid():
    """지금 연결된 와이파이 이름 (유선이거나 모르면 "")"""
    try:
        out = subprocess.run(["netsh", "wlan", "show", "interfaces"], capture_output=True, timeout=5,
                             creationflags=_NO_WINDOW).stdout
        for enc in ("utf-8", "cp949"):
            try:
                text = out.decode(enc)
                break
            except UnicodeDecodeError:
                continue
        else:
            return ""
        m = re.search(r"^\s*SSID\s*:\s*(.+?)\s*$", text, re.M)     # "BSSID"는 제외됨 (줄 맨 앞이 SSID)
        return m.group(1) if m else ""
    except Exception:
        return ""


class ProfileStore:
    """이름 → 연결 정보. 파일 하나에 저장"""

    def __init__(self, path):
        self.path = path
        try:
            with open(path, encoding="utf-8") as f:
                self.data = json.load(f)
        except Exception:
            self.data = {}

    def _write(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f, ensure_ascii=False, indent=1)
        os.replace(tmp, self.path)

    def entries(self, describe=None):
        out = []
        for n, d in self.data.items():
            e = {"name": n, "saved": d.get("_saved", ""), "ssid": d.get("_ssid", "")}
            if describe:
                e["desc"] = describe(d)
            out.append(e)
        return sorted(out, key=lambda x: x["saved"], reverse=True)

    def save(self, name, info, ssid=""):
        name = str(name).strip()[:40]
        if not name:
            return "이름을 입력하세요"
        d = dict(info)
        d["_saved"] = time.strftime("%Y-%m-%d %H:%M")
        d["_ssid"] = ssid
        self.data[name] = d
        self._write()
        return "ok"

    def get(self, name):
        d = self.data.get(name)
        return None if d is None else {k: v for k, v in d.items() if not k.startswith("_")}

    def delete(self, name):
        if self.data.pop(name, None) is None:
            return "없는 프로필이에요"
        self._write()
        return "ok"

    def rename(self, old, new):
        new = str(new).strip()[:40]
        if old not in self.data or not new:
            return "이름을 확인하세요"
        if new in self.data and new != old:
            return "같은 이름이 이미 있어요"
        self.data[new] = self.data.pop(old)
        self._write()
        return "ok"

    def for_ssid(self, ssid):
        """그 와이파이에서 저장한 프로필 이름 (가장 최근 것)"""
        if not ssid:
            return None
        hits = [e for e in self.entries() if e["ssid"] == ssid]
        return hits[0]["name"] if hits else None


# ---------------------------------------------------------------- 서브 PC 설정 파일 (메인 화면에서 서브로 바꿀 때도 같은 파일)
import socket

SUB_DIR = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "KVM")
SUB_CONFIG = os.path.join(SUB_DIR, "client_config.json")
SUB_PROFILES = os.path.join(SUB_DIR, "client_profiles.json")


def load_json(path, default=None):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {} if default is None else default


def save_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


def discover(timeout=1.2, port=24800):
    """같은 와이파이의 KVM 메인 PC 찾기 (UDP 방송)"""
    found = {}
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(0.3)
    try:
        for _ in range(2):
            s.sendto(b"KVM-DISCOVER", ("255.255.255.255", port))
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


# ---------------------------------------------------------------- 매크로 이름 표시 (화면 맨 위에 작게, 클릭은 통과)
def overlay_pos(m):
    """표시 위치: tl 좌상단 · ml 좌중단 · bl 좌하단 · tr 우상단 · mr 우중단 · br 우하단"""
    p = (m or {}).get("overlay_pos") or "tl"
    return p if p in ("tl", "ml", "bl", "tr", "mr", "br", "tc", "bc") else "tl"


def overlay_text(m):
    """매크로 상태 → (표시할 글, 종류: idle·rec·play). 표시 안 하면 글이 빈 문자열"""
    if not m or not m.get("overlay", True):
        return "", "idle"
    dev = m.get("dev")
    name = m.get("name") or ("새 녹화" if m.get("count") else "")
    slot = m.get("cur_slot")
    if m.get("recording") and m.get("rec_dev") == dev:
        return "●  매크로 녹화 중", "rec"
    if m.get("playing") and m.get("play_dev") == dev:
        rnd = f" ({m.get('round')}번째)" if m.get("repeat", 1) != 1 else ""
        return f"▶  {name or '매크로'} 실행 중{rnd}", "play"
    off = m.get("keys_on") is False
    if not name:
        return ("매크로 키 꺼짐", "idle") if off else ("", "idle")
    return f"매크로  {('[' + slot + '] ') if slot else ''}{name}{'  · 키 꺼짐' if off else ''}", "idle"


class MacroOverlay:
    """게임 화면 위에도 보이는 작은 표시줄 (항상 위 · 마우스 클릭은 아래 창으로 통과)"""
    COLORS = {"idle": "#c9d1dc", "rec": "#ff7b7b", "play": "#5fe3a8"}

    def __init__(self):
        import queue
        import threading
        self.q = queue.Queue()
        self.last = None
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def show(self, text, kind="idle", pos="tl", offset=0):
        if (text, kind, pos, offset) != self.last:
            self.last = (text, kind, pos, offset)
            self.q.put((text, kind, pos, offset))

    def _run(self):
        import ctypes
        import queue
        try:
            import tkinter as tk
        except Exception as e:
            print(f"[매크로 표시] 사용할 수 없음: {e}")
            return
        root = tk.Tk()
        root.overrideredirect(True)
        root.attributes("-topmost", True)
        root.attributes("-alpha", 0.82)
        root.configure(bg="#14161c")
        lbl = tk.Label(root, text="", fg=self.COLORS["idle"], bg="#14161c",
                       font=("Malgun Gothic", 10, "bold"), padx=12, pady=5)
        lbl.pack()
        root.geometry("+16+12")
        root.withdraw()
        try:   # 클릭 통과 · 작업 표시줄에 안 보이게
            root.update_idletasks()
            u = ctypes.windll.user32
            hwnd = u.GetParent(root.winfo_id()) or root.winfo_id()
            style = u.GetWindowLongW(hwnd, -20)
            u.SetWindowLongW(hwnd, -20, style | 0x80000 | 0x20 | 0x80 | 0x08000000)
        except Exception:
            pass
        state = {"shown": False, "tick": 0}

        def poll():
            try:
                while True:
                    text, kind, pos, offset = self.q.get_nowait()
                    if not text:
                        root.withdraw()
                        state["shown"] = False
                    else:
                        lbl.config(text=text, fg=self.COLORS.get(kind, self.COLORS["idle"]))
                        root.update_idletasks()
                        sw, sh = root.winfo_screenwidth(), root.winfo_screenheight()
                        w, h = root.winfo_reqwidth(), root.winfo_reqheight()
                        m = 16
                        x = m if pos[1] == "l" else (sw - w) // 2 if pos[1] == "c" else sw - w - m
                        y = {"t": 12, "m": (sh - h) // 2, "b": sh - h - 56}[pos[0]]   # 아래쪽은 작업 표시줄을 피해서
                        y += offset if pos[0] != "b" else -offset                        # 같은 자리에 둘이면 겹치지 않게
                        root.geometry(f"+{x}+{y}")
                        root.deiconify()
                        state["shown"] = True
            except queue.Empty:
                pass
            state["tick"] += 1
            if state["shown"] and state["tick"] % 10 == 0:
                root.attributes("-topmost", True)      # 게임 창이 위로 올라와도 다시 맨 위로
                root.lift()
            root.after(200, poll)

        poll()
        root.mainloop()
