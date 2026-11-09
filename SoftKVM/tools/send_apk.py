"""같은 와이파이의 태블릿 브라우저에서 KVM-Android.apk를 내려받게 하는 임시 서버"""
import http.server
import os
import socket
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK = os.path.join(ROOT, "KVM-Android.apk")
PORT = 8765


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/", "/KVM-Android.apk"):
            self.send_error(404)
            return
        if self.path == "/":
            body = ('<html><meta name="viewport" content="width=device-width">'
                    '<body style="font-family:sans-serif;background:#0f1115;color:#eceef1;'
                    'display:flex;height:90vh;align-items:center;justify-content:center">'
                    '<a href="/KVM-Android.apk" style="background:#2563eb;color:#fff;padding:18px 32px;'
                    'border-radius:14px;text-decoration:none;font-size:20px">KVM 앱 내려받기</a>'
                    '</body></html>').encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        with open(APK, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Disposition", 'attachment; filename="KVM-Android.apk"')
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        print("[전송 완료] 태블릿에서 내려받은 파일을 눌러 설치하세요. 이 창은 닫아도 됩니다.")

    def log_message(self, *args):
        pass


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


if __name__ == "__main__":
    if not os.path.exists(APK):
        sys.exit("KVM-Android.apk가 없습니다. 먼저 apk만들기.bat을 실행하세요.")
    print("=" * 50)
    print(f"  태블릿 브라우저(Chrome)에서 아래 주소를 여세요:")
    print(f"     http://{lan_ip()}:{PORT}")
    print("=" * 50)
    print("방화벽 허용 창이 뜨면 '개인 네트워크' 허용 / 끝나면 이 창을 닫으세요.")
    http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
