using System.Diagnostics;
using System.IO.Compression;
using System.Text.RegularExpressions;

namespace PenBridge;

/// <summary>adb로 보이는 태블릿 한 대 (USB와 무선 디버깅으로 중복 연결된 같은 기기는 하나로 합침)</summary>
internal sealed record AdbDevice(
    string Serial,
    string State,        // device / unauthorized / offline
    string Model,
    string Kind,         // "USB" / "무선 디버깅"
    int AppVersion,      // -1 = 앱 없음, 0 = 모름, 그 외 versionCode
    string? Busy);       // "설치 중…" 등

/// <summary>
/// adb(안드로이드 디버그 도구) 관리.
/// - 연결된 태블릿 목록, PenBridge 앱 설치 여부/버전
/// - "adb reverse"로 태블릿의 127.0.0.1:47100 → 이 PC (USB로 연결 버튼)
/// - 태블릿에 앱 설치/업데이트/열기
/// - adb가 없으면 구글 공식 platform-tools를 자동으로 받음
/// </summary>
internal sealed partial class AdbHelper : IDisposable
{
    public const string DownloadUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip";
    public const string Package = "com.penbridge";

    private readonly Settings _settings;
    private readonly HashSet<string> _reversed = new();
    private readonly Dictionary<string, (string hwSerial, string model, int app, DateTime checkedAt)> _info = new();
    private readonly Dictionary<string, string> _busy = new();
    private readonly object _runLock = new();
    private volatile bool _stopped;
    private bool _downloadTried;

    public volatile string Status = "확인 중…";
    public volatile bool UsbReady;
    public volatile IReadOnlyList<AdbDevice> Devices = Array.Empty<AdbDevice>();
    public volatile bool Downloading;
    public string? AdbPath { get; private set; }

    public AdbHelper(Settings settings) => _settings = settings;

    private static string DownloadDir => Path.Combine(Settings.Dir, "platform-tools");

    public static string? FindAdb()
    {
        var candidates = new List<string>
        {
            Path.Combine(DownloadDir, "adb.exe"),
            Path.Combine(AppContext.BaseDirectory, "platform-tools", "adb.exe"),
            Path.Combine(AppContext.BaseDirectory, "adb.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android", "Sdk", "platform-tools", "adb.exe"),
        };
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            try { candidates.Add(Path.Combine(dir.Trim(), "adb.exe")); } catch { }
        }
        return candidates.FirstOrDefault(File.Exists);
    }

    public void Start() => new Thread(Loop) { IsBackground = true, Name = "PenBridge adb" }.Start();

    private void Loop()
    {
        while (!_stopped)
        {
            try { Tick(); }
            catch (Exception e) { Status = "오류: " + e.Message; UsbReady = false; }
            for (int i = 0; i < 20 && !_stopped; i++) Thread.Sleep(100);
        }
    }

    private void Tick()
    {
        AdbPath ??= FindAdb();
        if (AdbPath == null)
        {
            Devices = Array.Empty<AdbDevice>();
            UsbReady = false;
            // 처음 한 번은 자동으로 받아 둠
            if (!_downloadTried && _settings.UsbAuto)
            {
                _downloadTried = true;
                try { DownloadAsync(new Progress<string>(s => Status = s)).GetAwaiter().GetResult(); }
                catch { Status = "태블릿 연결 도구를 받지 못했어요"; }
            }
            else if (!Downloading) Status = "태블릿 연결 도구(adb)가 없어요";
            return;
        }

        var (ok, output) = Run("devices -l");
        if (!ok) { Status = "adb 실행 실패"; UsbReady = false; return; }

        // "serial  state  ... model:Lenovo_TB_X ..."
        var raw = new List<(string serial, string state, string model)>();
        foreach (var line in output.Split('\n'))
        {
            var l = line.Trim();
            if (l.Length == 0 || l.StartsWith("List of devices") || l.StartsWith("*")) continue;
            var parts = l.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 2) continue;
            var model = parts.FirstOrDefault(p => p.StartsWith("model:"))?[6..].Replace('_', ' ') ?? "";
            raw.Add((parts[0], parts[1], model));
        }

        // 기기 정보 (하드웨어 일련번호, 모델명, 앱 버전) — 10초마다 갱신
        foreach (var (serial, state, model) in raw.Where(r => r.state == "device"))
        {
            bool stale;
            lock (_info) stale = !_info.TryGetValue(serial, out var inf) || DateTime.UtcNow - inf.checkedAt > TimeSpan.FromSeconds(10);
            if (stale) RefreshInfo(serial, model);
        }

        // 같은 기기가 USB와 무선으로 여러 번 보이면 하나만 (USB 우선)
        var chosen = new Dictionary<string, AdbDevice>();
        foreach (var (serial, state, model) in raw.OrderBy(r => KindRank(r.serial)))
        {
            string key = serial, name = model, busy;
            int app = 0;
            lock (_info)
            {
                if (_info.TryGetValue(serial, out var inf))
                {
                    key = string.IsNullOrEmpty(inf.hwSerial) ? serial : inf.hwSerial;
                    if (inf.model.Length > 0) name = inf.model;
                    app = inf.app;
                }
            }
            if (chosen.ContainsKey(key)) continue;
            lock (_busy) busy = _busy.GetValueOrDefault(serial) ?? "";
            if (name.Length == 0) name = "안드로이드 기기";
            chosen[key] = new AdbDevice(serial, state, name, KindOf(serial), app, busy.Length > 0 ? busy : null);
        }
        var devices = chosen.Values.ToList();
        Devices = devices;

        // USB로 연결 버튼용 포트 연결
        var online = devices.Where(d => d.State == "device").Select(d => d.Serial).ToList();
        string[] todo;
        lock (_reversed)
        {
            _reversed.IntersectWith(online);
            todo = online.Where(s => !_reversed.Contains(s)).ToArray();
        }
        if (_settings.UsbAuto)
        {
            foreach (var serial in todo)
            {
                var (rok, _) = Run($"-s {serial} reverse tcp:{Proto.Port} tcp:{Proto.Port}");
                if (rok) lock (_reversed) _reversed.Add(serial);
            }
        }

        int count;
        lock (_reversed) count = _reversed.Count;
        UsbReady = count > 0 && _settings.UsbAuto;
        if (devices.Count == 0) Status = "";
        else if (devices.Any(d => d.State == "unauthorized")) Status = "태블릿에서 허용을 눌러 주세요";
        else Status = $"{devices.Count}대 연결됨";
    }

    private static int KindRank(string serial) =>
        serial.Contains("_adb-tls-connect") ? 2 : serial.Contains(':') ? 1 : 0;

    private static string KindOf(string serial) => KindRank(serial) == 0 ? "USB" : "무선 디버깅";

    [GeneratedRegex(@"versionCode=(\d+)")]
    private static partial Regex VersionCodeRx();

    private void RefreshInfo(string serial, string modelFromList)
    {
        var (_, hw) = Run($"-s {serial} shell getprop ro.serialno");
        var (_, model) = Run($"-s {serial} shell getprop ro.product.model");
        var (_, dump) = Run($"-s {serial} shell dumpsys package {Package}");
        int app = -1;
        var m = VersionCodeRx().Match(dump);
        if (m.Success && int.TryParse(m.Groups[1].Value, out var vc)) app = vc;
        var name = model.Trim();
        if (name.Length == 0) name = modelFromList;
        lock (_info) _info[serial] = (hw.Trim(), name, app, DateTime.UtcNow);
    }

    private void Invalidate(string serial)
    {
        lock (_info) _info.Remove(serial);
    }

    private void SetBusy(string serial, string? text)
    {
        lock (_busy)
        {
            if (text == null) _busy.Remove(serial);
            else _busy[serial] = text;
        }
        // 화면에 바로 반영
        Devices = Devices.Select(d => d.Serial == serial ? d with { Busy = text } : d).ToList();
    }

    private (bool ok, string output) Run(string args, int timeoutMs = 8000)
    {
        lock (_runLock)   // 설치(몇십 초)와 주기 점검이 동시에 adb를 부르지 않게
        {
            var psi = new ProcessStartInfo(AdbPath!, args)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            using var p = Process.Start(psi);
            if (p == null) return (false, "");
            var outTask = p.StandardOutput.ReadToEndAsync();
            var errTask = p.StandardError.ReadToEndAsync();
            if (!p.WaitForExit(timeoutMs)) { try { p.Kill(); } catch { } return (false, "시간 초과"); }
            p.WaitForExit();
            return (p.ExitCode == 0, outTask.Result + errTask.Result);
        }
    }

    // ───────────────────────── 앱 설치 / 열기 ─────────────────────────

    /// <summary>기기 한 대에 앱 설치(또는 업데이트) 후 실행. 성공하면 null, 실패하면 이유.</summary>
    public string? InstallTo(string serial, string apkPath, bool autoConnect)
    {
        if (AdbPath == null) return "adb 없음";
        if (!File.Exists(apkPath)) return "설치할 태블릿 앱 파일이 없어요";
        SetBusy(serial, "설치 중…");
        try
        {
            var (ok, output) = Run($"-s {serial} install -r \"{apkPath}\"", 180_000);
            if (!ok || !output.Contains("Success"))
            {
                if (output.Contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
                    return "태블릿에 다른 서명의 PenBridge가 있어요. 태블릿에서 PenBridge를 지운 뒤 다시 설치해 주세요.";
                if (output.Contains("INSTALL_FAILED_VERSION_DOWNGRADE"))
                    return "태블릿에 더 새 버전이 있어요.";
                if (output.Contains("INSTALL_FAILED_USER_RESTRICTED") || output.Contains("INSTALL_CANCELED_BY_USER"))
                    return "태블릿에서 설치를 막았어요. 개발자 옵션의 'USB를 통해 설치'를 켜거나 태블릿 화면의 확인 창을 눌러 주세요.";
                var last = output.Trim().Split('\n').LastOrDefault()?.Trim();
                return string.IsNullOrEmpty(last) ? "설치 실패" : last;
            }
            OpenApp(serial, autoConnect);
            return null;
        }
        finally
        {
            Invalidate(serial);
            SetBusy(serial, null);
        }
    }

    public void OpenApp(string serial, bool autoConnect = true)
    {
        if (AdbPath == null) return;
        Run($"-s {serial} shell am start -n {Package}/.MainActivity" + (autoConnect ? " --ez autoconnect true" : ""));
    }

    /// <summary>
    /// 자동 업데이트용: PenBridge가 깔려 있고 버전이 낮은 기기 모두에 설치.
    /// 성공하면 null, 실패하면 이유.
    /// </summary>
    public string? UpdateAll(string apkPath, int newVersion)
    {
        var targets = Devices.Where(d => d.State == "device" && d.AppVersion != -1 && d.AppVersion < newVersion).ToList();
        if (targets.Count == 0) return "업데이트할 태블릿이 없어요";
        foreach (var d in targets)
        {
            var err = InstallTo(d.Serial, apkPath, autoConnect: true);
            if (err != null) return err;
        }
        return null;
    }

    /// <summary>구글 공식 platform-tools를 받아 앱 데이터 폴더에 푼다.</summary>
    public async Task DownloadAsync(IProgress<string> progress)
    {
        if (Downloading) return;
        Downloading = true;
        try
        {
            Directory.CreateDirectory(Settings.Dir);
            var zip = Path.Combine(Settings.Dir, "platform-tools.zip");
            progress.Report("태블릿 연결 도구(adb) 받는 중…");
            using (var http = new HttpClient { Timeout = TimeSpan.FromMinutes(5) })
            {
                var bytes = await http.GetByteArrayAsync(DownloadUrl).ConfigureAwait(false);
                await File.WriteAllBytesAsync(zip, bytes).ConfigureAwait(false);
            }
            progress.Report("압축 푸는 중…");
            if (Directory.Exists(DownloadDir)) Directory.Delete(DownloadDir, true);
            ZipFile.ExtractToDirectory(zip, Settings.Dir);   // zip 안에 platform-tools 폴더가 있음
            File.Delete(zip);
            AdbPath = FindAdb();
            progress.Report(AdbPath != null ? "준비 완료" : "adb를 찾지 못했어요");
        }
        finally
        {
            Downloading = false;
        }
    }

    public void Dispose() => _stopped = true;
}
