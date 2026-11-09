using System.Diagnostics;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Microsoft.Win32;

namespace PenBridge;

/// <summary>
/// 메인 창. 화면은 ui/index.html(WebView2)이 그리고, 이 클래스는
/// 0.1초마다 상태를 JSON으로 보내고(Push) 화면에서 온 명령을 처리한다.
/// </summary>
internal sealed class MainForm : Form
{
    private readonly Settings _settings = Settings.Load();
    private readonly AppState _state = new();
    private readonly PenServer _server;
    private readonly DiscoveryResponder _discovery = new();
    private readonly AdbHelper _adb;
    private readonly Updater _updater;
    private readonly ShareServer _share = new();
    private string _page = "home";
    private readonly NotifyIcon _tray;
    private Icon _iconIdle;
    private readonly Icon _iconActive;
    private readonly System.Windows.Forms.Timer _ui = new() { Interval = 100 };
    private readonly WebView2 _web = new() { Dock = DockStyle.Fill };
    private bool _webReady;
    private string _lastJson = "";

    private (string Path, Version Version) _bestExe = (Environment.ProcessPath!, Updater.Current);
    private DateTime _bestExeAt;
    private PendingUpdate? _pending;
    private int _pendingId;
    private string? _notice;
    private DateTime _noticeAt;
    private bool _exiting, _balloonShown;
    private readonly bool _startMinimized;

    public MainForm(bool startMinimized, bool justUpdated = false, bool justInstalled = false)
    {
        _startMinimized = startMinimized;
        _adb = new AdbHelper(_settings);
        _updater = new Updater(_settings);
        _server = new PenServer(_settings, _state, _updater, _adb);

        AutoScaleDimensions = new SizeF(96f, 96f);
        AutoScaleMode = AutoScaleMode.Dpi;
        Text = "PenBridge";
        ClientSize = new Size(1000, 660);
        MinimumSize = new Size(860, 560);
        StartPosition = FormStartPosition.CenterScreen;
        RestoreWindowBounds();
        BackColor = IsDarkMode() ? Color.FromArgb(23, 24, 28) : Color.FromArgb(243, 244, 247);

        _iconActive = LoadTrayIcon("tray.on.ico");
        _iconIdle = LoadTrayIcon(IsTaskbarLight() ? "tray.idle-dark.ico" : "tray.idle-light.ico");
        try { Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? _iconActive; } catch { Icon = _iconActive; }

        Controls.Add(_web);
        _web.DefaultBackgroundColor = BackColor;

        _tray = new NotifyIcon { Icon = _iconIdle, Text = "PenBridge — 대기 중", Visible = true, ContextMenuStrip = new ContextMenuStrip() };
        _tray.ContextMenuStrip.Items.Add("열기", null, (_, _) => ShowMain());
        _tray.ContextMenuStrip.Items.Add("종료", null, (_, _) => ExitApp());
        _tray.DoubleClick += (_, _) => ShowMain();
        _tray.BalloonTipClicked += (_, _) => ShowMain();

        SystemEvents.DisplaySettingsChanged += OnDisplayChanged;
        SystemEvents.UserPreferenceChanged += OnUserPreferenceChanged;

        try { _server.Start(); }
        catch (Exception e)
        {
            MessageBox.Show($"포트 {Proto.Port}을(를) 열 수 없어요. 다른 프로그램이 쓰고 있는지 확인해 주세요.\n\n{e.Message}",
                "PenBridge", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        try { _discovery.Start(); } catch { /* 자동 검색만 안 될 뿐 */ }
        _adb.Start();

        _updater.Notice += msg => Post(() => ShowNotice(msg));
        _updater.UpdateFound += p => Post(() => OfferUpdate(p));
        _updater.RestartRequested += () => Post(RestartIntoNewVersion);
        _updater.Start();

        _ui.Tick += (_, _) => Push();
        _ui.Start();

        if (justUpdated) ShowNotice($"PenBridge v{Updater.Current}(으)로 업데이트됐어요");
        if (justInstalled) ShowNotice("설치가 끝났어요. 시작 메뉴에서 PenBridge를 찾을 수 있어요.");
        _ = InitWebAsync();
        Task.Run(CheckFirewallRule);
    }

    /// <summary>방화벽 규칙이 이미 있는지 확인 (설치 프로그램이 추가했을 수 있음)</summary>
    private void CheckFirewallRule()
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo("netsh", "advfirewall firewall show rule name=\"PenBridge\"")
            {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true,
            });
            if (p == null) return;
            p.StandardOutput.ReadToEnd();
            p.WaitForExit(5000);
            bool exists = p.ExitCode == 0;
            if (exists != _settings.FirewallRuleAdded)
            {
                _settings.FirewallRuleAdded = exists;
                _settings.Save();
            }
        }
        catch { }
    }

    protected override void SetVisibleCore(bool value)
    {
        // --minimized 로 시작하면 트레이에만 표시
        if (_startMinimized && !IsHandleCreated)
        {
            CreateHandle();
            value = false;
        }
        base.SetVisibleCore(value);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        ApplyTitleBarTheme();
    }

    // ───────────────────────── WebView2 ─────────────────────────

    private async Task InitWebAsync()
    {
        try
        {
            var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(Settings.Dir, "webview"));
            await _web.EnsureCoreWebView2Async(env);
            var core = _web.CoreWebView2;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.IsZoomControlEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.AreBrowserAcceleratorKeysEnabled = false;
            core.WebMessageReceived += OnWebMessage;
            core.NewWindowRequested += (_, a) => a.Handled = true;
            core.NavigateToString(LoadUi());
        }
        catch (WebView2RuntimeNotFoundException)
        {
            ShowFallback("화면을 표시하려면 Microsoft Edge WebView2 런타임이 필요해요.\n" +
                         "https://go.microsoft.com/fwlink/p/?LinkId=2124703 에서 설치한 뒤 다시 실행해 주세요.\n\n" +
                         "펜 입력 기능은 지금도 동작하고 있어요.");
        }
        catch (Exception e)
        {
            ShowFallback("화면을 불러오지 못했어요: " + e.Message + "\n\n펜 입력 기능은 지금도 동작하고 있어요.");
        }
    }

    private static string LoadUi()
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("ui.index.html")
                      ?? throw new FileNotFoundException("ui.index.html");
        using var r = new StreamReader(s);
        var html = r.ReadToEnd();
        using var q = Assembly.GetExecutingAssembly().GetManifestResourceStream("ui.qrcode.js");
        if (q != null)
        {
            using var qr = new StreamReader(q);
            html = html.Replace("<!--QRLIB-->", "<script>" + qr.ReadToEnd() + "</script>");
        }
        return html;
    }

    private void ShowFallback(string text)
    {
        Controls.Remove(_web);
        Controls.Add(new Label
        {
            Text = text, Dock = DockStyle.Fill, Padding = new Padding(20),
            Font = new Font("Segoe UI", 10f), ForeColor = IsDarkMode() ? Color.White : Color.Black,
        });
    }

    private void OnWebMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        JsonNode? msg;
        try { msg = JsonNode.Parse(e.WebMessageAsJson); } catch { return; }
        var cmd = msg?["cmd"]?.GetValue<string>();
        switch (cmd)
        {
            case "ready":
                _webReady = true;
                _lastJson = "";
                Push();
                break;
            case "set":
                ApplySetting(msg!["key"]?.GetValue<string>() ?? "", msg["value"]);
                break;
            case "newPin":
                _settings.Pin = Settings.NewPin();
                _settings.Save();
                break;
            case "downloadAdb":
                _ = Task.Run(async () =>
                {
                    try { await _adb.DownloadAsync(new Progress<string>(s => _adb.Status = s)); }
                    catch (Exception ex) { Post(() => ShowNotice("adb를 받지 못했어요: " + ex.Message, warn: true)); }
                });
                break;
            case "installApp":
                InstallApp(msg!["serial"]?.GetValue<string>());
                break;
            case "openApp":
                var serial = msg!["serial"]?.GetValue<string>();
                if (serial != null) Task.Run(() => _adb.OpenApp(serial, autoConnect: true));
                break;
            case "pickUpdate":
                PickUpdateFile();
                break;
            case "checkOnline":
                Task.Run(async () =>
                {
                    var r = await _updater.CheckOnlineAsync(manual: true);
                    if (r.Length > 0) Post(() => ShowNotice(r));
                });
                break;
            case "applyUpdate":
                if (_pending != null) _updater.Apply(_pending);
                _pending = null;
                break;
            case "dismissUpdate":
                _pending = null;
                break;
            case "fixFirewall":
                AddFirewallRule();
                break;
            case "page":
                _page = msg!["page"]?.GetValue<string>() ?? "home";
                if (_page == "share") _share.Touch();
                break;
            case "copyUrl":
                var url = ShareServer.PrimaryUrl();
                if (url != null)
                {
                    try { Clipboard.SetText(url); ShowNotice("주소를 복사했어요."); } catch { }
                }
                break;
            case "setKey":
            {
                var id = msg!["id"]?.GetValue<string>() ?? "";
                var keys = msg["keys"]?.GetValue<string>() ?? "";
                if (Shortcuts.Actions.Any(a => a.Id == id) && KeyInput.Parse(keys).Length > 0)
                {
                    _settings.KeyOverrides[$"{Shortcuts.Profile(_settings)}.{id}"] = keys;
                    _settings.Save();
                }
                else ShowNotice("이 키는 쓸 수 없어요. 다른 키를 눌러 주세요.", warn: true);
                break;
            }
            case "resetKeys":
            {
                var prefix = Shortcuts.Profile(_settings) + ".";
                foreach (var k in _settings.KeyOverrides.Keys.Where(k => k.StartsWith(prefix)).ToList())
                    _settings.KeyOverrides.Remove(k);
                _settings.Save();
                ShowNotice("이 프리셋의 단축키를 기본값으로 되돌렸어요.");
                break;
            }
            case "usbInstallAll":
                UsbInstallAll();
                break;
            case "saveInstaller":
                SaveInstaller(msg!["which"]?.GetValue<string>() ?? "");
                break;
            case "installSelf":
                InstallSelf(msg!["desktop"]?.GetValue<bool>() ?? true, msg["firewall"]?.GetValue<bool>() ?? true);
                break;
            case "saveCopy":
                SaveCopy();
                break;
            case "uninstall":
                StartUninstall();
                break;
            case "exit":
                ExitApp();
                break;
        }
        Push();
    }

    private void ApplySetting(string key, JsonNode? value)
    {
        try
        {
            switch (key)
            {
                case "usbAuto": _settings.UsbAuto = value!.GetValue<bool>(); break;
                case "keepAspect": _settings.KeepAspect = value!.GetValue<bool>(); _server.UpdateTarget(); break;
                case "monitor": _settings.MonitorIndex = value!.GetValue<int>(); _server.UpdateTarget(); break;
                case "gamma": _settings.PressureGamma = Math.Clamp(value!.GetValue<double>(), 0.3, 3.0); break;
                case "tabletAutoUpdate": _settings.TabletAutoUpdate = value!.GetValue<bool>(); break;
                case "autoApplyPc": _settings.AutoApplyPcUpdate = value!.GetValue<bool>(); break;
                case "checkOnline": _settings.CheckOnline = value!.GetValue<bool>(); break;
                case "shortcutProfile": _settings.ShortcutProfile = value!.GetValue<string>(); break;
                case "zoomWheel":
                    _settings.KeyOverrides[$"{Shortcuts.Profile(_settings)}.zoomWheel"] = value!.GetValue<string>();
                    break;
                case "mirrorEnabled":
                    _settings.MirrorEnabled = value!.GetValue<bool>();
                    if (!_settings.MirrorEnabled) _server.Streamer.StopAll();
                    break;
                case "mirrorQuality": _settings.MirrorQuality = Math.Clamp(value!.GetValue<int>(), 0, 2); break;
                case "startup": SetStartup(value!.GetValue<bool>()); break;
                default: return;
            }
            _settings.Save();
        }
        catch { /* 잘못된 값 무시 */ }
    }

    /// <summary>화면에 보낼 상태. 바뀐 게 있을 때만 보냄.</summary>
    private void Push()
    {
        if (_exiting || IsDisposed) return;
        if (DateTime.Now - _bestExeAt > TimeSpan.FromSeconds(5))
        {
            _bestExe = Updater.BestExe();
            _bestExeAt = DateTime.Now;
        }
        // 트레이 아이콘은 화면과 상관없이 갱신
        bool c = _state.Connected;
        var icon = c ? _iconActive : _iconIdle;
        if (_tray.Icon != icon) _tray.Icon = icon;
        var tip = c ? $"PenBridge — 연결됨 ({_state.Transport})" : "PenBridge — 대기 중";
        if (_tray.Text != tip) _tray.Text = tip;

        if (!_webReady || !Visible) return;

        // 알림은 12초 뒤 사라짐 (진행 중 메시지는 계속)
        var message = _state.Message;
        if (message != null && message != _notice) { _notice = message; _noticeAt = DateTime.Now; }
        string? shownNotice = _notice != null && DateTime.Now - _noticeAt < TimeSpan.FromSeconds(12) ? _notice : null;
        if (_server.InjectErrors > 0 && shownNotice == null)
            shownNotice = $"펜 입력 오류 {_server.InjectErrors}회 (코드 {_server.LastInjectError})";

        int tabletApk = _updater.TabletVersionCode;
        var monitors = PenServer.GetMonitors().Select(m => new
        {
            x = m.Bounds.X, y = m.Bounds.Y, w = m.Bounds.Width, h = m.Bounds.Height,
            primary = m.Label.Contains("주 모니터"),
        }).ToArray();
        if (_page == "share" && Visible) _share.Touch();   // 보고 있는 동안은 계속 켜 둠
        var devices = _adb.Devices.Select(d => new
        {
            serial = d.Serial,
            model = d.Model,
            kind = d.Kind,
            state = d.State,
            app = d.AppVersion > 0 ? ApkInfo.ToVersionString(d.AppVersion) : d.AppVersion == 0 ? "?" : null,
            canUpdate = d.AppVersion > 0 && tabletApk > d.AppVersion,
            busy = d.Busy,
        }).ToArray();

        var s = new
        {
            appVersion = Updater.Current.ToString(),
            connected = c,
            transport = _state.Transport,
            remote = _state.Remote,
            pps = _state.PacketsPerSecond,
            pressure = Math.Round(_state.LastPressure, 3),
            message = shownNotice,
            messageWarn = _noticeWarn && shownNotice == _notice,
            adbStatus = _adb.Downloading ? _adb.Status : _adb.Status,
            adbMissing = _adb.AdbPath == null && !_adb.Downloading,
            devices,
            ips = DiscoveryResponder.LocalAddresses().ToArray(),
            pin = _settings.Pin,
            firewallOk = _settings.FirewallRuleAdded,
            apkSize = FileSize(Updater.TabletApkPath),
            exeSize = FileSize(_bestExe.Path),
            exeVersion = _bestExe.Version.ToString(),
            share = new
            {
                running = _share.Running,
                url = ShareServer.PrimaryUrl(),
                minutesLeft = _share.MinutesLeft,
                otherUrls = ShareServer.OtherUrls(),
            },
            firewallHint = !c && !_settings.FirewallRuleAdded
                ? "Wi‑Fi에서 이 PC를 못 찾으면 Windows 방화벽이 막고 있을 수 있어요."
                : null,
            monitors,
            monitor = _settings.MonitorIndex >= 0 && _settings.MonitorIndex < monitors.Length ? _settings.MonitorIndex : -1,
            usbAuto = _settings.UsbAuto,
            keepAspect = _settings.KeepAspect,
            gamma = _settings.PressureGamma,
            tabletApk = tabletApk > 0 ? ApkInfo.ToVersionString(tabletApk) : null,
            tabletVersion = _state.TabletVersion > 0 ? ApkInfo.ToVersionString(_state.TabletVersion) : "1.0.0",
            tabletAutoUpdate = _settings.TabletAutoUpdate,
            autoApplyPc = _settings.AutoApplyPcUpdate,
            startup = _settings.StartWithWindows,
            installed = Installer.RunningInstalled,
            shortcutProfile = Shortcuts.Profile(_settings),
            profiles = Shortcuts.Profiles.Select(p => new { id = p.Id, name = p.Name }).ToArray(),
            bindings = Shortcuts.Actions.Select(a => new
            {
                id = a.Id,
                label = a.Label,
                hint = a.Hint,
                keys = Shortcuts.KeyFor(_settings, a.Id),
                custom = Shortcuts.KeyFor(_settings, a.Id) != Shortcuts.DefaultFor(Shortcuts.Profile(_settings), a.Id),
            }).ToArray(),
            zoomWheel = Shortcuts.KeyFor(_settings, "zoomWheel"),
            mirrorEnabled = _settings.MirrorEnabled,
            mirrorQuality = _settings.MirrorQuality,
            mirror = new
            {
                active = _server.Streamer.Active,
                fps = _server.Streamer.Fps,
                size = _server.Streamer.FrameSize,
                kbps = _server.Streamer.KbPerSec,
            },
            installedVersion = _installedVersion?.ToString(),
            updateStatus = _updater.Status,
            checkOnline = _settings.CheckOnline,
            onlineConfigured = Updater.OnlineConfigured,
            lastOnlineCheck = _updater.LastOnlineCheck,
            updateBusy = _updater.Busy,
            pending = _pending == null ? null : new
            {
                id = _pendingId,
                version = _pending.Version.ToString(),
                source = _pending.Kind == UpdateKind.Source,
                origin = _pending.Origin,
            },
        };
        var json = JsonSerializer.Serialize(s);
        if (json == _lastJson) return;
        _lastJson = json;
        try { _web.CoreWebView2?.PostWebMessageAsJson(json); } catch { }
    }

    // ───────────────────────── 동작 ─────────────────────────

    private bool _noticeWarn;

    private void ShowNotice(string msg, bool warn = false)
    {
        _noticeWarn = warn;
        _state.Message = msg;
        if (!Visible) _tray.ShowBalloonTip(5000, "PenBridge", msg, warn ? ToolTipIcon.Warning : ToolTipIcon.Info);
    }

    private void InstallApp(string? serial)
    {
        if (serial == null) return;
        var apk = Updater.TabletApkPath;
        if (!File.Exists(apk))
        {
            ShowNotice("설치할 태블릿 앱 파일이 없어요. 업데이트 파일(zip/apk)을 먼저 선택해 주세요.", warn: true);
            return;
        }
        Task.Run(() =>
        {
            var err = _adb.InstallTo(serial, apk, autoConnect: true);
            Post(() => ShowNotice(err == null
                ? "태블릿에 설치했어요. 태블릿에서 PenBridge가 열려요."
                : "설치하지 못했어요: " + err, warn: err != null));
        });
    }

    private void OfferUpdate(PendingUpdate p)
    {
        if (_updater.Busy) return;
        _pending = p;
        _pendingId++;
        ShowMain();
        if (!_webReady)
        {
            // 화면이 없으면 예전 방식으로 물어봄
            var r = MessageBox.Show(this, $"새 버전 v{p.Version}을(를) 찾았어요.\n출처: {p.Origin}\n\n지금 업데이트할까요?",
                "PenBridge 업데이트", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (r == DialogResult.Yes) _updater.Apply(p);
            _pending = null;
        }
    }

    private void PickUpdateFile()
    {
        using var dlg = new OpenFileDialog
        {
            Title = "PenBridge 업데이트 파일 선택",
            Filter = "PenBridge 업데이트 (*.zip;*.exe;*.apk)|*.zip;*.exe;*.apk|모든 파일 (*.*)|*.*",
        };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;
        var file = dlg.FileName;
        Task.Run(() =>
        {
            bool found = _updater.Examine(file, manual: true);
            if (!found) Post(() => ShowNotice("이 파일에는 지금보다 새로운 버전이 없어요."));
        });
    }

    /// <summary>방화벽 허용 규칙 추가 (관리자 확인 창이 한 번 뜸)</summary>
    private void AddFirewallRule()
    {
        var exe = Environment.ProcessPath!;
        var args = "/c netsh advfirewall firewall delete rule name=\"PenBridge\" >nul & " +
                   $"netsh advfirewall firewall add rule name=\"PenBridge\" dir=in action=allow program=\"{exe}\" enable=yes profile=any";
        try
        {
            using var p = Process.Start(new ProcessStartInfo("cmd.exe", args)
            {
                UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden,
            });
            p?.WaitForExit(15000);
            if (p is { HasExited: true, ExitCode: 0 })
            {
                _settings.FirewallRuleAdded = true;
                _settings.Save();
                ShowNotice("방화벽에 PenBridge를 허용했어요. 태블릿에서 다시 'Wi‑Fi에서 찾기'를 눌러 보세요.");
            }
        }
        catch
        {
            ShowNotice("방화벽 설정을 취소했어요.", warn: true);
        }
    }

    // ───────────────────────── 기기 추가 ─────────────────────────

    private static string FileSize(string path)
    {
        try
        {
            if (!File.Exists(path)) return "";
            double mb = new FileInfo(path).Length / 1024.0 / 1024.0;
            return mb >= 10 ? $"{mb:0}MB" : $"{mb:0.0}MB";
        }
        catch { return ""; }
    }

    private void UsbInstallAll()
    {
        var targets = _adb.Devices.Where(d => d.State == "device").ToList();
        if (targets.Count == 0)
        {
            ShowNotice(_adb.Devices.Any(d => d.State == "unauthorized")
                ? "태블릿 화면에서 ‘USB 디버깅 허용’을 눌러 주세요."
                : "USB로 연결된 태블릿이 없어요. 케이블을 꽂고 USB 디버깅을 켜 주세요. (태블릿 메뉴에 방법이 있어요)", warn: true);
            return;
        }
        var apk = Updater.TabletApkPath;
        if (!File.Exists(apk)) { ShowNotice("설치할 태블릿 앱 파일이 없어요.", warn: true); return; }
        Task.Run(() =>
        {
            int ok = 0;
            string? lastErr = null;
            foreach (var d in targets)
            {
                var err = _adb.InstallTo(d.Serial, apk, autoConnect: true);
                if (err == null) ok++; else lastErr = $"{d.Model}: {err}";
            }
            Post(() => ShowNotice(lastErr == null
                ? $"태블릿 {ok}대에 설치했어요. 태블릿에서 PenBridge가 열려요."
                : $"설치하지 못한 기기가 있어요 — {lastErr}", warn: lastErr != null));
        });
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern int SHGetKnownFolderPath([MarshalAs(UnmanagedType.LPStruct)] Guid rfid, uint flags, IntPtr token, out string path);

    private static string DownloadsFolder()
    {
        try
        {
            if (SHGetKnownFolderPath(new Guid("374DE290-123F-4565-9164-39C4925E467B"), 0, IntPtr.Zero, out var p) == 0) return p;
        }
        catch { }
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
    }

    /// <summary>설치 파일을 다운로드 폴더에 저장하고 탐색기에서 보여 줌</summary>
    private void SaveInstaller(string which)
    {
        try
        {
            var dir = DownloadsFolder();
            Directory.CreateDirectory(dir);
            string src, dest;
            if (which == "android")
            {
                src = Updater.TabletApkPath;
                if (!File.Exists(src)) { ShowNotice("태블릿 앱 파일이 아직 없어요.", warn: true); return; }
                dest = Path.Combine(dir, $"PenBridge-태블릿-v{ApkInfo.ToVersionString(_updater.TabletVersionCode)}.apk");
            }
            else
            {
                var best = Updater.BestExe();
                src = best.Path;
                dest = Path.Combine(dir, Updater.SetupFileName(best.Version));
            }
            File.Copy(src, dest, true);
            Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{dest}\"") { UseShellExecute = true });
            ShowNotice($"다운로드 폴더에 저장했어요: {Path.GetFileName(dest)}");
        }
        catch (Exception e)
        {
            ShowNotice("저장하지 못했어요: " + e.Message, warn: true);
        }
    }

    // ───────────────────────── 창 위치 기억 ─────────────────────────

    private void RestoreWindowBounds()
    {
        var b = _settings.WindowBounds;
        if (b is not { Length: 4 }) return;
        var r = new Rectangle(b[0], b[1], Math.Max(b[2], MinimumSize.Width), Math.Max(b[3], MinimumSize.Height));
        // 화면 밖으로 나가 있으면 무시 (모니터 구성이 바뀐 경우)
        if (!Screen.AllScreens.Any(s => s.WorkingArea.IntersectsWith(new Rectangle(r.X, r.Y, 200, 60)))) return;
        StartPosition = FormStartPosition.Manual;
        Bounds = r;
    }

    private void SaveBounds()
    {
        if (WindowState != FormWindowState.Normal || !Visible) return;
        _settings.WindowBounds = new[] { Bounds.X, Bounds.Y, Bounds.Width, Bounds.Height };
        _settings.Save();
    }

    // ───────────────────────── 설치 / 배포 / 제거 ─────────────────────────

    private Version? _installedVersion = Installer.InstalledVersion();

    private void InstallSelf(bool desktop, bool firewall)
    {
        try
        {
            var exe = Installer.Install(desktop, firewall, _settings.StartWithWindows);
            if (firewall) { _settings.FirewallRuleAdded = true; }
            _settings.Save();
            Process.Start(new ProcessStartInfo(exe, "--installed")
            {
                UseShellExecute = false,
                WorkingDirectory = Path.GetDirectoryName(exe)!,
            });
            ExitApp();
        }
        catch (Exception e)
        {
            ShowNotice("설치하지 못했어요: " + e.Message, warn: true);
        }
    }

    private void SaveCopy()
    {
        using var dlg = new SaveFileDialog
        {
            Title = "다른 PC용 PenBridge 저장",
            FileName = Updater.SetupFileName(Updater.BestExe().Version),
            Filter = "실행 파일 (*.exe)|*.exe",
        };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            var v = Installer.SaveCopy(dlg.FileName);
            ShowNotice($"v{v}을(를) 저장했어요. 다른 PC에서 실행하고 '이 PC에 설치하기'를 누르면 돼요.");
        }
        catch (Exception e)
        {
            ShowNotice("저장하지 못했어요: " + e.Message, warn: true);
        }
    }

    private void StartUninstall()
    {
        // 실행 중에는 제거할 수 없으니 이 창을 닫은 뒤 제거 프로그램을 실행
        var exe = File.Exists(Installer.InstalledExe) ? Installer.InstalledExe : Environment.ProcessPath!;
        Process.Start(new ProcessStartInfo("cmd.exe", $"/c ping 127.0.0.1 -n 3 >nul & \"{exe}\" --uninstall")
        {
            UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Path.GetTempPath(),
        });
        ExitApp();
    }

    private void SetStartup(bool on)
    {
        _settings.StartWithWindows = on;
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
            if (key == null) return;
            if (on) key.SetValue("PenBridge", $"\"{Environment.ProcessPath}\" --minimized");
            else key.DeleteValue("PenBridge", false);
        }
        catch (Exception e)
        {
            ShowNotice("자동 실행 설정에 실패했어요: " + e.Message, warn: true);
        }
    }

    private void RestartIntoNewVersion()
    {
        try
        {
            var args = Visible ? "--updated" : "--updated --minimized";
            Process.Start(new ProcessStartInfo(Environment.ProcessPath!, args)
            {
                UseShellExecute = false,
                WorkingDirectory = Path.GetDirectoryName(Environment.ProcessPath!)!,
            });
            ExitApp();
        }
        catch (Exception e)
        {
            ShowNotice("새 버전을 시작하지 못했어요. PenBridge를 직접 다시 실행해 주세요. " + e.Message, warn: true);
        }
    }

    private void Post(Action a)
    {
        if (IsDisposed) return;
        if (!IsHandleCreated) { try { CreateHandle(); } catch { return; } }
        try { BeginInvoke(a); } catch { }
    }

    private void OnDisplayChanged(object? sender, EventArgs e) => Post(() => _server.UpdateTarget());

    private void OnUserPreferenceChanged(object? sender, UserPreferenceChangedEventArgs e)
    {
        if (e.Category == UserPreferenceCategory.General)
        {
            Post(() =>
            {
                ApplyTitleBarTheme();
                _iconIdle = LoadTrayIcon(IsTaskbarLight() ? "tray.idle-dark.ico" : "tray.idle-light.ico");
            });
        }
    }

    // ───────────────────────── 창 / 트레이 ─────────────────────────

    private void ShowMain()
    {
        Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate();
        _lastJson = "";
    }

    private void ExitApp()
    {
        _exiting = true;
        Close();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        SaveBounds();
        if (!_exiting && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            if (!_balloonShown)
            {
                _tray.ShowBalloonTip(3000, "PenBridge", "트레이에서 계속 실행 중이에요. 종료하려면 아이콘을 우클릭하세요.", ToolTipIcon.Info);
                _balloonShown = true;
            }
            return;
        }
        base.OnFormClosing(e);
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        SystemEvents.DisplaySettingsChanged -= OnDisplayChanged;
        SystemEvents.UserPreferenceChanged -= OnUserPreferenceChanged;
        _ui.Stop();
        _tray.Visible = false;
        _tray.Dispose();
        _updater.Dispose();
        _share.Dispose();
        _adb.Dispose();
        _discovery.Dispose();
        _server.Dispose();
        base.OnFormClosed(e);
    }

    // ───────────────────────── 다크 모드 ─────────────────────────

    private static bool IsDarkMode()
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return k?.GetValue("AppsUseLightTheme") is int v && v == 0;
        }
        catch { return false; }
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    private void ApplyTitleBarTheme()
    {
        bool dark = IsDarkMode();
        BackColor = dark ? Color.FromArgb(23, 24, 28) : Color.FromArgb(243, 244, 247);
        try { _web.DefaultBackgroundColor = BackColor; } catch { }
        if (!IsHandleCreated) return;
        int on = dark ? 1 : 0;
        try { DwmSetWindowAttribute(Handle, 20, ref on, sizeof(int)); } catch { }   // 제목 표시줄 다크 모드
    }

    /// <summary>트레이 아이콘 (exe 안에 들어 있는 .ico). 화면 배율에 맞는 크기를 고름</summary>
    private static Icon LoadTrayIcon(string name)
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name);
        if (s == null) return SystemIcons.Application;
        return new Icon(s, SystemInformation.SmallIconSize);
    }

    /// <summary>작업 표시줄이 밝은 테마인지 (대기 아이콘의 선 색을 고르기 위해)</summary>
    private static bool IsTaskbarLight()
    {
        try
        {
            using var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return k?.GetValue("SystemUsesLightTheme") is int v && v == 1;
        }
        catch { return false; }
    }
}
