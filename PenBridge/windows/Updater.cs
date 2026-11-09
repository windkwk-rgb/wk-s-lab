using System.Diagnostics;
using System.IO.Compression;
using System.Reflection;
using System.Text.Json;

namespace PenBridge;

internal enum UpdateKind
{
    /// <summary>완성된 PenBridge.exe (그대로 교체)</summary>
    App,
    /// <summary>소스 코드 zip (빌드한 뒤 교체)</summary>
    Source,
}

internal sealed record PendingUpdate(UpdateKind Kind, Version Version, string Origin, string Payload);

/// <summary>
/// 업데이트 파일을 찾아서 적용한다.
///
/// 찾는 곳: 실행 중인 exe 폴더, 다운로드, 문서, 문서\PenBridge-output, %LOCALAPPDATA%\PenBridge\updates
/// 찾는 파일: PenBridge*.exe / PenBridge*.apk / PenBridge*.zip
///   - zip 안에 PenBridge.exe나 .apk가 있으면 그것을 사용
///   - zip이 소스 코드(PenBridge/build.ps1 + version.txt)면 빌드해서 사용
///
/// 태블릿 앱(.apk)은 %LOCALAPPDATA%\PenBridge\tablet 에 보관했다가
/// 태블릿이 연결되면 PenServer가 태블릿으로 보낸다.
/// </summary>
internal sealed class Updater : IDisposable
{
    public static Version Current { get; } =
        Norm(Assembly.GetEntryAssembly()?.GetName().Version ?? new Version(0, 0, 0));

    private readonly Settings _settings;
    private readonly object _lock = new();
    private readonly Dictionary<string, (long, DateTime)> _seen = new(StringComparer.OrdinalIgnoreCase);
    private readonly HashSet<string> _offered = new();
    private System.Threading.Timer? _timer;
    private int _busy;

    /// <summary>새 노트북 앱 버전 발견 (백그라운드 스레드). 자동 적용이 꺼져 있을 때만.</summary>
    public event Action<PendingUpdate>? UpdateFound;
    /// <summary>알림 문구 (백그라운드 스레드)</summary>
    public event Action<string>? Notice;
    /// <summary>exe 교체 완료 → 새 버전으로 다시 시작해야 함 (백그라운드 스레드)</summary>
    public event Action? RestartRequested;

    public volatile string Status = "";

    public static string TabletApkPath => Path.Combine(Settings.Dir, "tablet", "PenBridge.apk");
    public int TabletVersionCode { get; private set; } = -1;
    public bool Busy => _busy != 0;

    public Updater(Settings settings) => _settings = settings;

    public void Start()
    {
        CleanupOldExe();
        if (File.Exists(TabletApkPath)) TabletVersionCode = ApkInfo.ReadVersionCode(TabletApkPath);
        ImportEmbeddedApk();
        Directory.CreateDirectory(Path.Combine(Settings.Dir, "updates"));
        _timer = new System.Threading.Timer(_ => SafeScan(), null, 2000, 5000);
        // 인터넷(GitHub) 확인: 시작 20초 뒤, 이후 6시간마다
        _onlineTimer = new System.Threading.Timer(_ => _ = CheckOnlineAsync(manual: false), null,
            TimeSpan.FromSeconds(20), TimeSpan.FromHours(6));
    }

    // ───────────────────────── 인터넷 업데이트 (GitHub Releases) ─────────────────────────

    private System.Threading.Timer? _onlineTimer;
    private int _onlineBusy;
    public volatile string? LastOnlineCheck;

    public static bool OnlineConfigured => UpdateSource.Repo.Length > 0;

    /// <summary>
    /// GitHub의 최신 Release를 확인. 새 버전이면 PenBridge.exe를 받아서 업데이트 제안.
    /// 반환: 사용자에게 보여줄 결과 문구
    /// </summary>
    public async Task<string> CheckOnlineAsync(bool manual)
    {
        if (!OnlineConfigured) return "인터넷 업데이트 주소가 아직 설정되지 않았어요.";
        if (!manual && !_settings.CheckOnline) return "";
        if (Interlocked.Exchange(ref _onlineBusy, 1) == 1) return "이미 확인 중이에요.";
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("PenBridge-Updater");
            http.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");

            // 저장소의 최근 Release 중 PenBridge 것(태그 penbridge-v…)만 골라 가장 새 버전을 찾음
            var json = await http.GetStringAsync($"https://api.github.com/repos/{UpdateSource.Repo}/releases?per_page=30").ConfigureAwait(false);
            using var doc = JsonDocument.Parse(json);
            LastOnlineCheck = DateTime.Now.ToString("M월 d일 HH:mm");
            Version? v = null;
            string? exeUrl = null;
            foreach (var rel in doc.RootElement.EnumerateArray())
            {
                if (rel.TryGetProperty("draft", out var d) && d.GetBoolean()) continue;
                if (rel.TryGetProperty("prerelease", out var pr) && pr.GetBoolean()) continue;
                var tag = rel.GetProperty("tag_name").GetString() ?? "";
                if (!tag.StartsWith(UpdateSource.TagPrefix, StringComparison.OrdinalIgnoreCase)) continue;
                if (!Version.TryParse(tag[UpdateSource.TagPrefix.Length..], out var rv)) continue;
                rv = Norm(rv);
                if (v != null && rv <= v) continue;
                string? url = null;
                foreach (var a in rel.GetProperty("assets").EnumerateArray())
                {
                    if (string.Equals(a.GetProperty("name").GetString(), "PenBridge.exe", StringComparison.OrdinalIgnoreCase))
                        url = a.GetProperty("browser_download_url").GetString();
                }
                if (url == null) continue;
                v = rv;
                exeUrl = url;
            }
            if (v == null) return "아직 인터넷에 올라온 PenBridge 버전이 없어요.";
            if (v <= Current) return $"최신 버전이에요 (v{Current}).";

            lock (_lock)
            {
                if (!manual && _offered.Contains($"{UpdateKind.App}:{v}")) return "";
            }

            Status = $"새 버전 v{v} 받는 중…";
            var staging = Path.Combine(Settings.Dir, "staging");
            Directory.CreateDirectory(staging);
            var dest = Path.Combine(staging, $"PenBridge-{v}.exe");
            var tmp = dest + ".part";
            using (var resp = await http.GetAsync(exeUrl, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false))
            {
                resp.EnsureSuccessStatusCode();
                long total = resp.Content.Headers.ContentLength ?? 0;
                await using var src = await resp.Content.ReadAsStreamAsync().ConfigureAwait(false);
                await using var fs = File.Create(tmp);
                var buf = new byte[81920];
                long got = 0;
                int n;
                while ((n = await src.ReadAsync(buf).ConfigureAwait(false)) > 0)
                {
                    await fs.WriteAsync(buf.AsMemory(0, n)).ConfigureAwait(false);
                    got += n;
                    if (total > 0) Status = $"새 버전 v{v} 받는 중… {got * 100 / total}%";
                }
            }
            File.Move(tmp, dest, true);
            Status = "";
            return OfferExe(dest, $"인터넷 (GitHub v{v})", manual) ? $"새 버전 v{v}을(를) 받았어요." : "받은 파일을 확인하지 못했어요.";
        }
        catch (HttpRequestException e) when (e.StatusCode == System.Net.HttpStatusCode.NotFound)
        {
            Status = "";
            return "아직 인터넷에 올라온 버전이 없어요.";
        }
        catch (Exception e)
        {
            Status = "";
            return manual ? "인터넷 업데이트 확인 실패: " + e.Message : "";
        }
        finally
        {
            Interlocked.Exchange(ref _onlineBusy, 0);
        }
    }

    // ───────────────────────── 탐지 ─────────────────────────

    public static IEnumerable<string> WatchFolders()
    {
        var list = new List<string>();
        var exeDir = Path.GetDirectoryName(Environment.ProcessPath);
        if (exeDir != null)
        {
            list.Add(exeDir);
            var parent = Path.GetDirectoryName(exeDir);   // 예: ...\PenBridge-v1.1.0-source (PenBridge-output의 상위)
            if (parent != null && Path.GetPathRoot(parent) != parent) list.Add(parent);
        }
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        list.Add(Path.Combine(home, "Downloads"));
        var docs = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        list.Add(docs);
        list.Add(Path.Combine(docs, "PenBridge-output"));
        list.Add(Path.Combine(Settings.Dir, "updates"));
        return list.Distinct(StringComparer.OrdinalIgnoreCase);
    }

    private void SafeScan()
    {
        if (Busy) return;
        try { Scan(); } catch { /* 다음 주기에 다시 */ }
    }

    private void Scan()
    {
        foreach (var dir in WatchFolders())
        {
            if (!Directory.Exists(dir)) continue;
            foreach (var file in Directory.EnumerateFiles(dir, "PenBridge*"))
            {
                var ext = Path.GetExtension(file).ToLowerInvariant();
                if (ext is not (".exe" or ".apk" or ".zip")) continue;
                var fi = new FileInfo(file);
                if (DateTime.Now - fi.LastWriteTime < TimeSpan.FromSeconds(3)) continue;   // 아직 복사/다운로드 중일 수 있음
                lock (_lock)
                {
                    if (_seen.TryGetValue(file, out var v) && v == (fi.Length, fi.LastWriteTimeUtc)) continue;
                    _seen[file] = (fi.Length, fi.LastWriteTimeUtc);
                }
                Examine(file, manual: false);
            }
        }
    }

    /// <summary>파일 하나를 검사. 새 버전이 있으면 true.</summary>
    public bool Examine(string file, bool manual)
    {
        try
        {
            return Path.GetExtension(file).ToLowerInvariant() switch
            {
                ".apk" => ImportApk(file, Path.GetFileName(file)),
                ".exe" => OfferExe(file, file, manual),
                ".zip" => ExamineZip(file, manual),
                _ => false,
            };
        }
        catch
        {
            return false;
        }
    }

    private bool ExamineZip(string zipPath, bool manual)
    {
        using var zip = ZipFile.OpenRead(zipPath);
        ZipArchiveEntry? exe = null, apk = null, ver = null, script = null;
        foreach (var e in zip.Entries)
        {
            var full = e.FullName.Replace('\\', '/');
            if (e.Name.Equals("PenBridge.exe", StringComparison.OrdinalIgnoreCase)) exe ??= e;
            else if (e.Name.EndsWith(".apk", StringComparison.OrdinalIgnoreCase) && !full.Contains("/build/intermediates/")) apk ??= e;
            else if (full.Equals("version.txt", StringComparison.OrdinalIgnoreCase) ||
                     full.EndsWith("PenBridge/version.txt", StringComparison.OrdinalIgnoreCase)) ver ??= e;
            else if (full.EndsWith("PenBridge/build.ps1", StringComparison.OrdinalIgnoreCase)) script ??= e;
        }

        bool found = false;
        var staging = Path.Combine(Settings.Dir, "staging");
        Directory.CreateDirectory(staging);

        if (apk != null)
        {
            var tmp = Path.Combine(staging, "from-zip.apk");
            apk.ExtractToFile(tmp, true);
            found |= ImportApk(tmp, Path.GetFileName(zipPath));
        }

        if (exe != null)
        {
            var tmp = Path.Combine(staging, $"PenBridge-{Guid.NewGuid():N}.exe");
            exe.ExtractToFile(tmp, true);
            found |= OfferExe(tmp, zipPath, manual);
        }
        else if (script != null && ver != null)
        {
            using var r = new StreamReader(ver.Open());
            if (Version.TryParse(r.ReadToEnd().Trim(), out var v) && Norm(v) > Current)
                found |= Offer(new PendingUpdate(UpdateKind.Source, Norm(v), zipPath, zipPath), manual);
        }
        return found;
    }

    private bool OfferExe(string exePath, string origin, bool manual)
    {
        var info = FileVersionInfo.GetVersionInfo(exePath);
        if (!Version.TryParse(info.FileVersion ?? "", out var v)) return false;
        if (!string.Equals(info.ProductName, "PenBridge", StringComparison.OrdinalIgnoreCase)) return false;
        v = Norm(v);
        if (v <= Current) return false;
        return Offer(new PendingUpdate(UpdateKind.App, v, origin, exePath), manual);
    }

    private bool Offer(PendingUpdate p, bool manual)
    {
        var key = $"{p.Kind}:{p.Version}";
        lock (_lock)
        {
            if (!manual && !_offered.Add(key)) return false;   // 같은 버전은 한 번만 물어봄
            _offered.Add(key);
        }
        if (_settings.AutoApplyPcUpdate) Apply(p);
        else UpdateFound?.Invoke(p);
        return true;
    }

    // ───────────────────────── 태블릿 앱 보관 ─────────────────────────

    private void ImportEmbeddedApk()
    {
        try
        {
            using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("PenBridge.apk");
            if (s == null) return;
            var tmp = Path.Combine(Settings.Dir, "staging", "embedded.apk");
            Directory.CreateDirectory(Path.GetDirectoryName(tmp)!);
            using (var f = File.Create(tmp)) s.CopyTo(f);
            ImportApk(tmp, "노트북 앱에 포함된 파일");
        }
        catch { }
    }

    private bool ImportApk(string apkPath, string origin)
    {
        int vc = ApkInfo.ReadVersionCode(apkPath);
        if (vc <= 0) return false;
        lock (_lock)
        {
            if (vc <= TabletVersionCode) return false;
            Directory.CreateDirectory(Path.GetDirectoryName(TabletApkPath)!);
            var tmp = TabletApkPath + ".tmp";
            File.Copy(apkPath, tmp, true);
            File.Move(tmp, TabletApkPath, true);
            TabletVersionCode = vc;
        }
        Notice?.Invoke($"태블릿 앱 v{ApkInfo.ToVersionString(vc)} 준비됨 ({origin}) — 태블릿이 연결되면 자동으로 설치돼요");
        return true;
    }

    // ───────────────────────── 적용 ─────────────────────────

    public void Apply(PendingUpdate p)
    {
        if (Interlocked.Exchange(ref _busy, 1) == 1) return;
        new Thread(() =>
        {
            try
            {
                if (p.Kind == UpdateKind.App) ApplyExe(p.Payload);
                else BuildFromSource(p);
            }
            catch (Exception e)
            {
                Status = "";
                Notice?.Invoke("업데이트 실패: " + e.Message);
            }
            finally
            {
                Interlocked.Exchange(ref _busy, 0);
            }
        }) { IsBackground = true, Name = "PenBridge update" }.Start();
    }

    private void ApplyExe(string newExe)
    {
        var cur = Environment.ProcessPath ?? throw new InvalidOperationException("실행 파일 위치를 알 수 없어요");

        // 이미 누군가 실행 중인 파일 자리에 새 버전을 넣어 둔 경우 → 다시 시작만 하면 됨
        if (!string.Equals(Path.GetFullPath(newExe), Path.GetFullPath(cur), StringComparison.OrdinalIgnoreCase))
        {
            Status = "새 버전으로 바꾸는 중…";
            var staged = cur + ".new";
            File.Copy(newExe, staged, true);

            // 실행 중인 exe는 덮어쓸 수 없지만 이름은 바꿀 수 있음
            var old = cur + ".old";
            try { if (File.Exists(old)) File.Delete(old); }
            catch { old = $"{cur}.{Guid.NewGuid():N}.old"; }

            File.Move(cur, old);
            try
            {
                File.Move(staged, cur);
            }
            catch
            {
                File.Move(old, cur);   // 되돌리기
                throw;
            }
        }
        Status = "";
        RestartRequested?.Invoke();
    }

    private void BuildFromSource(PendingUpdate p)
    {
        var root = Path.Combine(Settings.Dir, "build");
        var src = Path.Combine(root, "src");
        var outDir = Path.Combine(root, "out");

        Status = $"v{p.Version} 소스 압축 푸는 중…";
        if (Directory.Exists(src)) Directory.Delete(src, true);
        if (Directory.Exists(outDir)) Directory.Delete(outDir, true);
        ZipFile.ExtractToDirectory(p.Payload, src, true);
        var script = Directory.GetFiles(src, "build.ps1", SearchOption.AllDirectories).FirstOrDefault()
                     ?? throw new FileNotFoundException("build.ps1이 없어요");

        Status = $"v{p.Version} 빌드 준비 중…";
        Notice?.Invoke($"새 버전 v{p.Version} 소스를 찾아서 빌드하고 있어요. 끝나면 자동으로 바뀌어요.");
        // 창 없이 실행 (실수로 창을 닫아 빌드가 끊기지 않게). 진행 상황은 기록 파일의 '==' 줄로 표시
        var psi = new ProcessStartInfo("powershell.exe",
            $"-NoProfile -ExecutionPolicy Bypass -File \"{script}\" -OutDir \"{outDir}\" -NoPause")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            WorkingDirectory = Path.GetDirectoryName(script)!,
        };
        var log = Path.Combine(Path.GetDirectoryName(Path.GetDirectoryName(script)!)!, "PenBridge-build-log.txt");
        using (var proc = Process.Start(psi) ?? throw new InvalidOperationException("PowerShell을 실행할 수 없어요"))
        {
            proc.OutputDataReceived += (_, _) => { };
            proc.ErrorDataReceived += (_, _) => { };
            proc.BeginOutputReadLine();
            proc.BeginErrorReadLine();
            while (!proc.WaitForExit(2000))
            {
                try
                {
                    using var fs = new FileStream(log, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                    using var r = new StreamReader(fs);
                    var last = r.ReadToEnd().Split('\n').LastOrDefault(l => l.StartsWith("== "));
                    if (last != null) Status = $"v{p.Version} 빌드 중 · {last[3..].Trim()}";
                }
                catch { }
            }
            if (proc.ExitCode != 0)
            {
                // 기록을 찾기 쉬운 곳(프로그램 폴더)에 복사
                try
                {
                    var exeDir = Path.GetDirectoryName(Environment.ProcessPath)!;
                    File.Copy(log, Path.Combine(exeDir, "PenBridge-build-log.txt"), true);
                }
                catch { }
                throw new Exception("빌드에 실패했어요. 프로그램 폴더의 PenBridge-build-log.txt에 기록이 있어요.");
            }
        }

        var apk = Path.Combine(outDir, "PenBridge.apk");
        if (File.Exists(apk)) ImportApk(apk, $"v{p.Version} 빌드");

        var exe = Path.Combine(outDir, "PenBridge.exe");
        if (!File.Exists(exe)) throw new FileNotFoundException("빌드 결과에 PenBridge.exe가 없어요");
        ApplyExe(exe);
    }

    private static void CleanupOldExe()
    {
        try
        {
            var cur = Environment.ProcessPath;
            var dir = Path.GetDirectoryName(cur);
            if (dir == null) return;
            foreach (var f in Directory.EnumerateFiles(dir, Path.GetFileName(cur) + "*.old"))
                try { File.Delete(f); } catch { }
            var staging = Path.Combine(Settings.Dir, "staging");
            if (Directory.Exists(staging))
                foreach (var f in Directory.EnumerateFiles(staging, "PenBridge-*.exe"))
                    try { File.Delete(f); } catch { }
        }
        catch { }
    }

    /// <summary>
    /// 다른 PC로 내보낼 exe: 이 PC에 있는 PenBridge 중 가장 새 버전.
    /// 실행 중인 파일, 설치된 파일, 받아 두었지만 아직 적용하지 않은 업데이트, 소스로 빌드한 결과를 비교한다.
    /// (업데이트를 '나중에'로 미뤄 둔 상태에서도 최신 버전이 나가도록)
    /// </summary>
    public static (string Path, Version Version) BestExe()
    {
        var self = Environment.ProcessPath!;
        var candidates = new List<string> { self, Installer.InstalledExe, Path.Combine(Settings.Dir, "build", "out", "PenBridge.exe") };
        var staging = Path.Combine(Settings.Dir, "staging");
        try
        {
            if (Directory.Exists(staging)) candidates.AddRange(Directory.EnumerateFiles(staging, "PenBridge-*.exe"));
        }
        catch { }

        var best = (Path: self, Version: Current);
        foreach (var p in candidates.Distinct(StringComparer.OrdinalIgnoreCase))
        {
            var v = ExeVersion(p);
            if (v != null && v > best.Version) best = (p, v);
        }
        return best;
    }

    /// <summary>PenBridge exe의 버전 (PenBridge가 아니거나 없으면 null)</summary>
    public static Version? ExeVersion(string path)
    {
        try
        {
            if (!File.Exists(path)) return null;
            var info = FileVersionInfo.GetVersionInfo(path);
            if (!string.Equals(info.ProductName, "PenBridge", StringComparison.OrdinalIgnoreCase)) return null;
            return Version.TryParse(info.FileVersion ?? "", out var v) ? Norm(v) : null;
        }
        catch { return null; }
    }

    /// <summary>다른 PC용 파일 이름</summary>
    public static string SetupFileName(Version v) => $"PenBridge-Setup-v{v}.exe";

    public static Version Norm(Version v) => new(v.Major, Math.Max(0, v.Minor), Math.Max(0, v.Build));

    public void Dispose()
    {
        _timer?.Dispose();
        _onlineTimer?.Dispose();
    }
}
