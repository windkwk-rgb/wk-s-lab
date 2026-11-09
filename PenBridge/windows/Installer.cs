using System.Diagnostics;
using Microsoft.Win32;

namespace PenBridge;

/// <summary>
/// PenBridge.exe 자체가 설치 프로그램 역할을 한다.
/// - 설치: %LOCALAPPDATA%\Programs\PenBridge 로 자신을 복사, 시작 메뉴/바탕화면 바로가기,
///         '앱 및 기능' 제거 등록, 방화벽 허용 규칙 (관리자 확인 한 번)
/// - 제거: PenBridge.exe --uninstall
/// - 배포: 자신을 다른 이름으로 저장 (태블릿 앱 포함 한 파일)
/// </summary>
internal static class Installer
{
    private const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\PenBridge";
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    public static string InstallDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "PenBridge");

    public static string InstalledExe => Path.Combine(InstallDir, "PenBridge.exe");

    private static string StartMenuLink =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "PenBridge.lnk");

    private static string DesktopLink =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "PenBridge.lnk");

    /// <summary>지금 설치된 위치에서 실행 중인지</summary>
    public static bool RunningInstalled =>
        string.Equals(Path.GetFullPath(Environment.ProcessPath ?? ""), Path.GetFullPath(InstalledExe), StringComparison.OrdinalIgnoreCase);

    /// <summary>설치된 버전 (없으면 null)</summary>
    public static Version? InstalledVersion()
    {
        try
        {
            if (!File.Exists(InstalledExe)) return null;
            var v = FileVersionInfo.GetVersionInfo(InstalledExe).FileVersion;
            return Version.TryParse(v, out var ver) ? Updater.Norm(ver) : new Version(0, 0, 0);
        }
        catch { return null; }
    }

    /// <summary>
    /// 이 exe를 설치 폴더로 복사하고 바로가기 등을 만든다. 성공하면 설치된 exe 경로.
    /// 호출한 쪽은 설치된 exe를 실행하고 스스로 종료해야 한다.
    /// </summary>
    public static string Install(bool desktopShortcut, bool firewall, bool startWithWindows)
    {
        var self = Environment.ProcessPath ?? throw new InvalidOperationException("실행 파일 위치를 알 수 없어요");
        Directory.CreateDirectory(InstallDir);

        if (!RunningInstalled)
        {
            // 설치 폴더의 이전 버전이 실행 중이 아니라는 건 단일 실행 잠금으로 보장됨
            var tmp = InstalledExe + ".new";
            File.Copy(self, tmp, true);
            if (File.Exists(InstalledExe))
            {
                try { File.Delete(InstalledExe); }
                catch { File.Move(InstalledExe, $"{InstalledExe}.{Guid.NewGuid():N}.old"); }
            }
            File.Move(tmp, InstalledExe);
        }

        CreateShortcut(StartMenuLink, InstalledExe);
        if (desktopShortcut) CreateShortcut(DesktopLink, InstalledExe);

        using (var k = Registry.CurrentUser.CreateSubKey(UninstallKey))
        {
            k.SetValue("DisplayName", "PenBridge");
            k.SetValue("DisplayVersion", Updater.Current.ToString());
            k.SetValue("Publisher", "PenBridge");
            k.SetValue("DisplayIcon", InstalledExe);
            k.SetValue("InstallLocation", InstallDir);
            k.SetValue("UninstallString", $"\"{InstalledExe}\" --uninstall");
            k.SetValue("NoModify", 1, RegistryValueKind.DWord);
            k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            k.SetValue("EstimatedSize", (int)(new FileInfo(InstalledExe).Length / 1024), RegistryValueKind.DWord);
        }

        using (var run = Registry.CurrentUser.OpenSubKey(RunKey, true))
        {
            if (run != null)
            {
                if (startWithWindows) run.SetValue("PenBridge", $"\"{InstalledExe}\" --minimized");
                else if (run.GetValue("PenBridge") is string old && old.Contains("PenBridge"))
                    run.SetValue("PenBridge", $"\"{InstalledExe}\" --minimized");   // 경로만 설치 위치로 갱신
            }
        }

        if (firewall) AddFirewallRule(InstalledExe);
        return InstalledExe;
    }

    /// <summary>방화벽 허용 규칙 (관리자 확인 창). 성공 여부.</summary>
    public static bool AddFirewallRule(string exe)
    {
        var args = "/c netsh advfirewall firewall delete rule name=\"PenBridge\" >nul & " +
                   $"netsh advfirewall firewall add rule name=\"PenBridge\" dir=in action=allow program=\"{exe}\" enable=yes profile=any";
        try
        {
            using var p = Process.Start(new ProcessStartInfo("cmd.exe", args)
            {
                UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden,
            });
            if (p == null) return false;
            p.WaitForExit(20000);
            return p.HasExited && p.ExitCode == 0;
        }
        catch
        {
            return false;   // 사용자가 관리자 확인을 취소
        }
    }

    /// <summary>다른 PC용으로 저장 (이 PC에 있는 가장 새 버전). 저장한 버전을 돌려줌</summary>
    public static Version SaveCopy(string dest)
    {
        var (src, ver) = Updater.BestExe();
        File.Copy(src, dest, true);
        return ver;
    }

    /// <summary>PenBridge.exe --uninstall</summary>
    public static void Uninstall()
    {
        var r = MessageBox.Show("PenBridge를 이 PC에서 제거할까요?", "PenBridge 제거",
            MessageBoxButtons.YesNo, MessageBoxIcon.Question);
        if (r != DialogResult.Yes) return;

        // 실행 중인 PenBridge가 있으면 먼저 종료해야 함
        using (var m = new Mutex(false, "PenBridge.SingleInstance"))
        {
            bool free;
            try { free = m.WaitOne(0); } catch (AbandonedMutexException) { free = true; }
            if (!free)
            {
                MessageBox.Show("PenBridge가 실행 중이에요. 트레이 아이콘을 우클릭해서 종료한 뒤 다시 시도해 주세요.",
                    "PenBridge 제거", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            m.ReleaseMutex();
        }

        TryDelete(StartMenuLink);
        TryDelete(DesktopLink);
        try { Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, false); } catch { }
        try
        {
            using var run = Registry.CurrentUser.OpenSubKey(RunKey, true);
            run?.DeleteValue("PenBridge", false);
        }
        catch { }

        // 방화벽 규칙 삭제 (관리자 확인, 취소해도 제거는 계속)
        try
        {
            using var p = Process.Start(new ProcessStartInfo("cmd.exe", "/c netsh advfirewall firewall delete rule name=\"PenBridge\"")
            {
                UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden,
            });
            p?.WaitForExit(15000);
        }
        catch { }

        // 실행 중인 자기 자신은 지울 수 없으므로 종료 후 지우도록 예약
        Process.Start(new ProcessStartInfo("cmd.exe",
            $"/c ping 127.0.0.1 -n 3 >nul & rmdir /s /q \"{InstallDir}\" & rmdir /s /q \"{Settings.Dir}\"")
        {
            UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Path.GetTempPath(),
        });
        MessageBox.Show("PenBridge를 제거했어요.", "PenBridge 제거", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    /// <summary>바로가기(.lnk) 만들기 — Windows 기본 COM(WScript.Shell)을 PowerShell로 호출</summary>
    private static void CreateShortcut(string link, string target)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(link)!);
            string Q(string s) => s.Replace("'", "''");
            var script =
                $"$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{Q(link)}');" +
                $"$s.TargetPath='{Q(target)}';$s.WorkingDirectory='{Q(Path.GetDirectoryName(target)!)}';" +
                $"$s.IconLocation='{Q(target)},0';$s.Description='PenBridge';$s.Save()";
            using var p = Process.Start(new ProcessStartInfo("powershell.exe",
                $"-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"{script.Replace("\"", "\\\"")}\"")
            {
                UseShellExecute = false, CreateNoWindow = true,
            });
            p?.WaitForExit(15000);
        }
        catch { /* 바로가기 실패는 치명적이지 않음 */ }
    }
}
