using System.Net;
using System.Net.Sockets;
using System.Text;

namespace PenBridge;

/// <summary>
/// '기기 추가'용 작은 웹 서버 (http://이PC주소:8765).
/// 같은 Wi‑Fi의 새 기기가 브라우저로 열면 태블릿 앱(APK)과 Windows 프로그램(EXE)을 내려받을 수 있다.
///
/// - 화면에서 '기기 추가'를 열 때만 켜지고, 마지막으로 연 뒤 30분이 지나면 저절로 꺼진다.
/// - 관리자 권한이 필요 없도록 HttpListener 대신 TcpListener로 직접 HTTP를 처리한다.
/// - 읽기 전용: 정해진 두 파일과 안내 페이지만 내보낸다.
/// </summary>
internal sealed class ShareServer : IDisposable
{
    public const int Port = 8765;
    private static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(30);

    private readonly object _lock = new();
    private TcpListener? _listener;
    private DateTime _until;
    private System.Threading.Timer? _timer;

    public bool Running { get; private set; }
    public string? Error { get; private set; }

    public int MinutesLeft => Running ? Math.Max(1, (int)Math.Ceiling((_until - DateTime.Now).TotalMinutes)) : 0;

    /// <summary>켜거나(이미 켜져 있으면) 꺼지는 시간을 다시 30분 뒤로</summary>
    public void Touch()
    {
        lock (_lock)
        {
            _until = DateTime.Now + Lifetime;
            if (Running) return;
            try
            {
                _listener = new TcpListener(IPAddress.Any, Port);
                _listener.Start();
                Running = true;
                Error = null;
                new Thread(AcceptLoop) { IsBackground = true, Name = "PenBridge share" }.Start();
                _timer ??= new System.Threading.Timer(_ => { if (DateTime.Now > _until) Stop(); }, null, 30_000, 30_000);
            }
            catch (Exception e)
            {
                Error = $"포트 {Port}을(를) 열 수 없어요: {e.Message}";
                Running = false;
            }
        }
    }

    public void Stop()
    {
        lock (_lock)
        {
            Running = false;
            try { _listener?.Stop(); } catch { }
            _listener = null;
        }
    }

    public static string? PrimaryUrl() =>
        DiscoveryResponder.LocalAddresses().FirstOrDefault() is { } ip ? $"http://{ip}:{Port}" : null;

    public static string[] OtherUrls() =>
        DiscoveryResponder.LocalAddresses().Skip(1).Select(ip => $"http://{ip}:{Port}").ToArray();

    private void AcceptLoop()
    {
        var l = _listener;
        while (Running && l != null)
        {
            TcpClient c;
            try { c = l.AcceptTcpClient(); }
            catch { return; }
            ThreadPool.QueueUserWorkItem(_ => Handle(c));
        }
    }

    private static void Handle(TcpClient client)
    {
        using (client)
        {
            try
            {
                client.ReceiveTimeout = 10_000;
                client.SendTimeout = 120_000;
                var s = client.GetStream();

                // 요청 헤더 읽기 (최대 16KB)
                var buf = new byte[16 * 1024];
                int len = 0;
                while (len < buf.Length)
                {
                    int n = s.Read(buf, len, buf.Length - len);
                    if (n <= 0) break;
                    len += n;
                    if (Encoding.ASCII.GetString(buf, 0, len).Contains("\r\n\r\n")) break;
                }
                var head = Encoding.ASCII.GetString(buf, 0, len);
                var first = head.Split("\r\n")[0].Split(' ');
                if (first.Length < 2) return;
                var method = first[0];
                var path = first[1].Split('?')[0];
                bool android = head.Contains("Android", StringComparison.OrdinalIgnoreCase);
                bool headOnly = method == "HEAD";
                if (method != "GET" && !headOnly) { Send(s, 405, "text/plain", "Method Not Allowed"u8.ToArray(), headOnly); return; }

                switch (path)
                {
                    case "/":
                        Send(s, 200, "text/html; charset=utf-8", Encoding.UTF8.GetBytes(Page(android)), headOnly);
                        break;
                    case "/PenBridge.apk":
                        SendFile(s, Updater.TabletApkPath, "application/vnd.android.package-archive",
                            $"PenBridge-{ApkInfo.ToVersionString(ApkInfo.ReadVersionCode(Updater.TabletApkPath))}.apk", headOnly);
                        break;
                    case "/PenBridge.exe":
                    {
                        var (exe, ver) = Updater.BestExe();
                        SendFile(s, exe, "application/octet-stream", Updater.SetupFileName(ver), headOnly);
                        break;
                    }
                    default:
                        Send(s, 404, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes("없는 주소예요"), headOnly);
                        break;
                }
            }
            catch { /* 연결이 끊기면 그냥 끝 */ }
        }
    }

    private static void Send(NetworkStream s, int code, string type, byte[] body, bool headOnly)
    {
        var status = code switch { 200 => "OK", 404 => "Not Found", 405 => "Method Not Allowed", _ => "Error" };
        var h = $"HTTP/1.1 {code} {status}\r\nContent-Type: {type}\r\nContent-Length: {body.Length}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
        s.Write(Encoding.ASCII.GetBytes(h));
        if (!headOnly) s.Write(body);
    }

    private static void SendFile(NetworkStream s, string path, string type, string fileName, bool headOnly)
    {
        if (!File.Exists(path))
        {
            Send(s, 404, "text/plain; charset=utf-8", Encoding.UTF8.GetBytes("아직 파일이 없어요"), headOnly);
            return;
        }
        using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        var h = $"HTTP/1.1 200 OK\r\nContent-Type: {type}\r\nContent-Length: {fs.Length}\r\n" +
                $"Content-Disposition: attachment; filename=\"{fileName}\"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        s.Write(Encoding.ASCII.GetBytes(h));
        if (!headOnly) fs.CopyTo(s, 256 * 1024);
    }

    private static string Size(string path)
    {
        try
        {
            if (!File.Exists(path)) return "";
            double mb = new FileInfo(path).Length / 1024.0 / 1024.0;
            return mb >= 10 ? $"{mb:0}MB" : $"{mb:0.0}MB";
        }
        catch { return ""; }
    }

    /// <summary>새 기기 브라우저에 보여 줄 안내 페이지 (휴대폰 화면 기준)</summary>
    private static string Page(bool android)
    {
        var apkVc = ApkInfo.ReadVersionCode(Updater.TabletApkPath);
        bool hasApk = apkVc > 0;
        string apkCard = hasApk
            ? $"""
              <a class="card {(android ? "pri" : "")}" href="/PenBridge.apk">
                <div class="ic">{TabletSvg}</div>
                <div class="t"><b>안드로이드 태블릿 앱</b><span>v{ApkInfo.ToVersionString(apkVc)} · {Size(Updater.TabletApkPath)}</span></div>
                <span class="go">받기</span>
              </a>
              """
            : """<div class="card off"><div class="t"><b>안드로이드 태블릿 앱</b><span>아직 준비되지 않았어요</span></div></div>""";
        var (bestExe, bestVer) = Updater.BestExe();
        string winCard = $"""
              <a class="card {(android ? "" : "pri")}" href="/PenBridge.exe">
                <div class="ic">{PcSvg}</div>
                <div class="t"><b>Windows PC 프로그램</b><span>v{bestVer} · {Size(bestExe)} · 태블릿 앱 포함</span></div>
                <span class="go">받기</span>
              </a>
              """;
        string first = android ? apkCard + winCard : winCard + apkCard;
        string tip = android
            ? "받은 파일을 누르고, ‘출처를 알 수 없는 앱’ 설치를 허용하면 설치돼요."
            : "받은 PenBridge를 실행하고 ‘이 PC에 설치하기’를 누르면 돼요.";

        return $$"""
            <!doctype html>
            <html lang="ko">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>PenBridge 내려받기</title>
            <style>
            :root { --bg:#f4f4f7; --card:#fff; --text:#17171f; --muted:#63667a; --line:rgba(20,20,40,.09); --accent:#5b45e6; --soft:rgba(91,69,230,.1); --on:#fff; color-scheme: light; }
            @media (prefers-color-scheme: dark) { :root { --bg:#16161b; --card:#212128; --text:#ececf2; --muted:#a0a2b2; --line:rgba(255,255,255,.08); --accent:#a08cff; --soft:rgba(160,140,255,.14); --on:#17123a; color-scheme: dark; } }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--text); font: 16px/1.5 system-ui, -apple-system, "Segoe UI", "Malgun Gothic", sans-serif; }
            .wrap { max-width: 520px; margin: 0 auto; padding: 28px 16px 40px; }
            header { display: flex; align-items: center; gap: 12px; margin-bottom: 22px; }
            .logo { width: 44px; height: 44px; border-radius: 12px; display: grid; place-items: center; color: #fff; background: linear-gradient(140deg,#9474ff,#4b37df); }
            h1 { font-size: 21px; margin: 0; }
            header p { margin: 0; color: var(--muted); font-size: 14px; }
            .card { display: flex; align-items: center; gap: 14px; padding: 16px; margin-bottom: 10px; border-radius: 14px; background: var(--card); border: 1px solid var(--line); color: inherit; text-decoration: none; }
            .card.pri { border: 2px solid var(--accent); }
            .card.off { opacity: .55; }
            .ic { width: 44px; height: 44px; border-radius: 12px; display: grid; place-items: center; background: var(--soft); color: var(--accent); flex: none; }
            .t { flex: 1; min-width: 0; }
            .t b { display: block; }
            .t span { color: var(--muted); font-size: 13.5px; }
            .go { padding: 9px 16px; border-radius: 10px; background: var(--accent); color: var(--on); font-weight: 600; font-size: 15px; flex: none; }
            .tip { margin-top: 18px; padding: 14px 16px; border-radius: 12px; background: var(--soft); font-size: 14.5px; }
            .foot { margin-top: 18px; color: var(--muted); font-size: 13px; }
            </style>
            </head>
            <body><div class="wrap">
            <header><div class="logo">{{TabletSvg}}</div><div><h1>PenBridge 내려받기</h1><p>{{WebUtility.HtmlEncode(Environment.MachineName)}}에서 보내는 설치 파일이에요</p></div></header>
            {{first}}
            <div class="tip">{{tip}}</div>
            <div class="foot">Mac · iPad용은 아직 준비 중이에요.</div>
            </div></body>
            </html>
            """;
    }

    private const string TabletSvg =
        """<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="3.5" width="12.5" height="17" rx="2.3"/><path d="M8.6 17.3h2.3"/><path d="M20.6 5.2l-7 7"/><path d="M13.6 12.2l-1.6 3 3-1.6z"/></svg>""";

    private const string PcSvg =
        """<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8"/><path d="M12 17v4"/></svg>""";

    public void Dispose()
    {
        Stop();
        _timer?.Dispose();
    }
}
