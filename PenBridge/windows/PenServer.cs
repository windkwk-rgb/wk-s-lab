using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;

namespace PenBridge;

/// <summary>UI에 보여줄 상태. 여러 스레드에서 읽고 쓰므로 단순 필드 + volatile.</summary>
internal sealed class AppState
{
    public volatile bool Connected;
    public volatile string Transport = "";
    public volatile string Remote = "";
    public volatile float LastPressure;
    public volatile bool PenDown;
    public volatile int PacketsPerSecond;
    public volatile string? Message;   // 최근 알림(PIN 거부 등)
    public volatile int TabletVersion;  // 연결된 태블릿 앱의 versionCode (1.0 앱은 0)
}

/// <summary>
/// TCP 서버. 태블릿 하나와 연결해 펜 패킷을 받아 PenInjector로 넘긴다.
/// 새 태블릿이 연결되면 이전 연결은 끊는다.
/// </summary>
internal sealed class PenServer : IDisposable
{
    private readonly Settings _settings;
    private readonly AppState _state;
    private readonly PenInjector _injector;
    private readonly object _injectLock = new();
    private readonly object _clientLock = new();
    private TcpListener? _listener;
    private TcpClient? _current;
    private NetworkStream? _currentStream;
    private volatile bool _stopped;

    // UI 스레드에서 계산해 두는 대상 화면 영역 (Screen 정보는 UI 스레드에서 읽는 게 안전)
    private Rectangle _target;
    private float _aspect;

    private readonly Updater _updater;
    private readonly AdbHelper _adb;

    /// <summary>액정 타블렛 모드 (화면 보내기)</summary>
    public ScreenStreamer Streamer { get; }

    public PenServer(Settings settings, AppState state, Updater updater, AdbHelper adb)
    {
        _settings = settings;
        _state = state;
        _updater = updater;
        _adb = adb;
        _injector = new PenInjector();
        Streamer = new ScreenStreamer(settings, () => _target);
        UpdateTarget();
    }

    public int InjectErrors => _injector.ErrorCount;
    public int LastInjectError => _injector.LastError;

    public static IReadOnlyList<(string Label, Rectangle Bounds)> GetMonitors()
    {
        var list = new List<(string, Rectangle)>();
        var screens = Screen.AllScreens;
        for (int i = 0; i < screens.Length; i++)
        {
            var b = screens[i].Bounds;
            list.Add(($"모니터 {i + 1}  ({b.Width}×{b.Height}){(screens[i].Primary ? "  · 주 모니터" : "")}", b));
        }
        return list;
    }

    /// <summary>설정이나 모니터 구성이 바뀌면 UI 스레드에서 호출.</summary>
    public void UpdateTarget()
    {
        var screens = Screen.AllScreens;
        Rectangle r = _settings.MonitorIndex >= 0 && _settings.MonitorIndex < screens.Length
            ? screens[_settings.MonitorIndex].Bounds
            : SystemInformation.VirtualScreen;
        _target = r;
        _aspect = _settings.KeepAspect && r.Height > 0 ? (float)r.Width / r.Height : 0f;
        SendToCurrent(Proto.Make(Proto.Config, _aspect));
    }

    public void Start()
    {
        _listener = new TcpListener(IPAddress.Any, Proto.Port);
        _listener.Start();
        var t = new Thread(AcceptLoop) { IsBackground = true, Name = "PenBridge accept" };
        t.Start();
    }

    private void AcceptLoop()
    {
        while (!_stopped)
        {
            TcpClient client;
            try { client = _listener!.AcceptTcpClient(); }
            catch { if (_stopped) return; Thread.Sleep(200); continue; }

            var t = new Thread(() => HandleClient(client))
            {
                IsBackground = true,
                Name = "PenBridge client",
                Priority = ThreadPriority.AboveNormal,
            };
            t.Start();
        }
    }

    private void HandleClient(TcpClient client)
    {
        var remote = (IPEndPoint)client.Client.RemoteEndPoint!;
        bool isUsb = IPAddress.IsLoopback(remote.Address);
        NetworkStream? stream = null;
        bool registered = false;
        ShortcutRunner? runner = null;

        try
        {
            client.NoDelay = true;
            client.ReceiveTimeout = 6000;   // 태블릿은 1초마다 ping을 보냄
            stream = client.GetStream();
            var buf = new byte[Proto.PacketSize];

            // 1) 인사 + PIN 확인
            ReadExact(stream, buf);
            if (buf[0] == Proto.Discover)
            {
                stream.Write(Proto.MakeNamed(Proto.DiscoverReply));
                return;
            }
            if (buf[0] == Proto.Stream)
            {
                int spin = BinaryPrimitives.ReadInt32LittleEndian(buf.AsSpan(4));
                int maxW = BinaryPrimitives.ReadInt32LittleEndian(buf.AsSpan(8));
                if (!isUsb && spin.ToString("D4") != _settings.Pin) { stream.Write(Proto.Make(Proto.Reject)); return; }
                Streamer.Serve(client, stream, maxW);
                return;
            }
            if (buf[0] == Proto.UpdateRequest)
            {
                ServeUpdate(client, stream, buf, isUsb, remote);
                return;
            }
            if (buf[0] != Proto.Hello) return;
            int pin = BinaryPrimitives.ReadInt32LittleEndian(buf.AsSpan(4));
            int tabletVersion = BinaryPrimitives.ReadInt32LittleEndian(buf.AsSpan(16));   // 1.0 앱은 0
            byte helloFlags = buf[20];
            if (!isUsb && pin.ToString("D4") != _settings.Pin)
            {
                stream.Write(Proto.Make(Proto.Reject));
                _state.Message = $"{remote.Address} 에서 잘못된 PIN으로 연결을 시도했어요";
                return;
            }

            // 2) 기존 연결 교체
            TcpClient? old;
            lock (_clientLock)
            {
                old = _current;
                _current = client;
                _currentStream = stream;
                registered = true;
            }
            old?.Close();

            lock (_clientLock)
            {
                stream.Write(Proto.MakeNamed(Proto.Accept));
                stream.Write(Proto.Make(Proto.Config, _aspect));
            }

            _state.Transport = isUsb ? "USB" : "Wi‑Fi";
            _state.Remote = isUsb ? "USB 케이블" : remote.Address.ToString();
            _state.Connected = true;
            _state.Message = null;
            _state.TabletVersion = tabletVersion;

            MaybeUpdateTablet(isUsb, tabletVersion, helloFlags, stream);

            // 단축키: 툴바 버튼/손가락 제스처 → 키 입력. 지우개 상태는 태블릿 버튼 표시용으로 돌려보냄
            runner = new ShortcutRunner(_settings);
            runner.EraserChanged += on =>
            {
                var p = Proto.Make(Proto.ToolState);
                p[1] = (byte)(on ? 1 : 0);
                SendTo(stream, p);
            };

            // 3) 수신 루프
            var sw = Stopwatch.StartNew();
            int count = 0;
            while (!_stopped)
            {
                ReadExact(stream, buf);
                switch (buf[0])
                {
                    case Proto.Pen:
                        var s = Proto.ReadPen(buf);
                        lock (_injectLock)
                            _injector.Apply(s, _target, _settings.PressureGamma);
                        _state.PenDown = s.State == PenState.Contact;
                        _state.LastPressure = s.State == PenState.Contact ? s.Pressure : 0f;
                        count++;
                        break;
                    case Proto.Ping:
                        SendTo(stream, Proto.Make(Proto.Pong));
                        break;
                    case Proto.Action:
                        runner.OnAction(buf[1], buf[2] == 1);
                        break;
                    case Proto.Gesture:
                        runner.OnGesture(buf[1],
                            BinaryPrimitives.ReadSingleLittleEndian(buf.AsSpan(4)),
                            BinaryPrimitives.ReadSingleLittleEndian(buf.AsSpan(8)));
                        break;
                }
                if (sw.ElapsedMilliseconds >= 1000)
                {
                    _state.PacketsPerSecond = count;
                    count = 0;
                    sw.Restart();
                }
            }
        }
        catch
        {
            // 연결 끊김, 시간 초과 등 → 정리만 하면 됨
        }
        finally
        {
            try { runner?.ReleaseHold(); } catch { }
            bool wasCurrent = false;
            lock (_clientLock)
            {
                if (registered && _current == client)
                {
                    _current = null;
                    _currentStream = null;
                    wasCurrent = true;
                }
            }
            if (wasCurrent)
            {
                lock (_injectLock) _injector.Release();
                _state.Connected = false;
                _state.PenDown = false;
                _state.LastPressure = 0;
                _state.PacketsPerSecond = 0;
            }
            client.Close();
        }
    }

    // ───────────────────────── 태블릿 앱 자동 업데이트 ─────────────────────────

    private void MaybeUpdateTablet(bool isUsb, int tabletVersion, byte flags, NetworkStream stream)
    {
        if (!_settings.TabletAutoUpdate) return;
        int have = _updater.TabletVersionCode;
        if (have <= 0 || tabletVersion >= have) return;
        var apk = Updater.TabletApkPath;
        if (!File.Exists(apk)) return;

        bool tabletAllows = (flags & Proto.HelloAutoUpdate) != 0;
        bool oldApp = tabletVersion == 0;   // 1.0 앱: 자동 업데이트 기능이 없음 → USB로만 가능
        if (!tabletAllows && !oldApp) return;

        // USB: adb로 조용히 설치하고 앱을 다시 실행 (가장 매끄러움)
        if (isUsb && _adb.UsbReady)
        {
            _state.Message = $"태블릿 앱을 v{ApkInfo.ToVersionString(have)}(으)로 업데이트하는 중 (USB)…";
            Task.Run(() =>
            {
                var err = _adb.UpdateAll(apk, have);
                if (err == null)
                {
                    _state.Message = $"태블릿 앱 v{ApkInfo.ToVersionString(have)} 설치 완료";
                    return;
                }
                _state.Message = "USB 설치 실패: " + err;
                if (tabletAllows) SendOffer(stream, have, apk);   // 태블릿이 직접 받게
            });
            return;
        }

        // Wi‑Fi: 태블릿에 알리면 태블릿이 받아서 설치
        if (tabletAllows) SendOffer(stream, have, apk);
    }

    private void SendOffer(NetworkStream stream, int versionCode, string apk)
    {
        var p = Proto.Make(Proto.UpdateOffer);
        BinaryPrimitives.WriteInt32LittleEndian(p.AsSpan(4), versionCode);
        BinaryPrimitives.WriteInt64LittleEndian(p.AsSpan(8), new FileInfo(apk).Length);
        SendTo(stream, p);
        _state.Message = $"태블릿에 새 버전 v{ApkInfo.ToVersionString(versionCode)}을(를) 알렸어요";
    }

    /// <summary>태블릿이 별도 연결로 요청하면 APK 파일을 보낸다.</summary>
    private void ServeUpdate(TcpClient client, NetworkStream stream, byte[] req, bool isUsb, IPEndPoint remote)
    {
        int pin = BinaryPrimitives.ReadInt32LittleEndian(req.AsSpan(4));
        var apk = Updater.TabletApkPath;
        if ((!isUsb && pin.ToString("D4") != _settings.Pin) || !File.Exists(apk))
        {
            stream.Write(Proto.Make(Proto.Reject));
            return;
        }

        byte[] data;
        using (var fs = new FileStream(apk, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
        {
            data = new byte[fs.Length];
            fs.ReadExactly(data);
        }
        var header = Proto.Make(Proto.UpdateData);
        BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(4), _updater.TabletVersionCode);
        BinaryPrimitives.WriteInt64LittleEndian(header.AsSpan(8), data.Length);

        client.SendTimeout = 30000;
        stream.Write(header);
        stream.Write(data);
        stream.Flush();
        client.Client.Shutdown(SocketShutdown.Send);
        _state.Message = $"태블릿({(isUsb ? "USB" : remote.Address.ToString())})으로 업데이트 파일을 보냈어요";

        // 태블릿이 다 받고 연결을 닫을 때까지 잠깐 대기
        try { client.ReceiveTimeout = 30000; stream.Read(new byte[1]); } catch { }
    }

    private void SendTo(NetworkStream stream, byte[] packet)
    {
        lock (_clientLock)
        {
            try { stream.Write(packet); } catch { /* 수신 루프에서 정리됨 */ }
        }
    }


    private void SendToCurrent(byte[] packet)
    {
        lock (_clientLock)
        {
            try { _currentStream?.Write(packet); } catch { }
        }
    }

    private static void ReadExact(NetworkStream s, byte[] buf)
    {
        int off = 0;
        while (off < buf.Length)
        {
            int n = s.Read(buf, off, buf.Length - off);
            if (n <= 0) throw new IOException("연결 종료");
            off += n;
        }
    }

    public void Dispose()
    {
        _stopped = true;
        try { _listener?.Stop(); } catch { }
        lock (_clientLock) { _current?.Close(); }
        lock (_injectLock) _injector.Dispose();
    }
}
