using System.Text.Json;

namespace PenBridge;

internal sealed class Settings
{
    /// <summary>Wi‑Fi 연결 시 확인하는 4자리 PIN (USB 연결은 PIN 불필요)</summary>
    public string Pin { get; set; } = NewPin();
    /// <summary>-1 = 모든 모니터 전체, 0 이상 = 해당 모니터</summary>
    public int MonitorIndex { get; set; } = 0;
    /// <summary>화면 비율 유지 (원이 찌그러지지 않음)</summary>
    public bool KeepAspect { get; set; } = true;
    /// <summary>필압 곡선 감마 (0.4 ~ 2.5)</summary>
    public double PressureGamma { get; set; } = 1.0;
    public bool UsbAuto { get; set; } = true;
    public bool StartWithWindows { get; set; } = false;
    /// <summary>노트북 앱 새 버전을 묻지 않고 바로 적용</summary>
    public bool AutoApplyPcUpdate { get; set; } = false;
    /// <summary>태블릿이 연결되면 태블릿 앱을 자동 업데이트</summary>
    public bool TabletAutoUpdate { get; set; } = true;
    /// <summary>인터넷(GitHub)에서 새 버전 확인</summary>
    public bool CheckOnline { get; set; } = true;
    /// <summary>Windows 방화벽에 PenBridge 허용 규칙이 있음 (설치 프로그램 또는 앱에서 추가)</summary>
    public bool FirewallRuleAdded { get; set; } = false;
    /// <summary>단축키 프리셋: common / csp / ps / krita</summary>
    public string ShortcutProfile { get; set; } = "common";
    /// <summary>사용자 지정 키 ("프리셋.동작" → "Ctrl+Z")</summary>
    public Dictionary<string, string> KeyOverrides { get; set; } = new();
    /// <summary>액정 타블렛 모드(화면 보내기) 허용</summary>
    public bool MirrorEnabled { get; set; } = true;
    /// <summary>화질: 0 부드럽게, 1 균형, 2 선명하게</summary>
    public int MirrorQuality { get; set; } = 1;
    /// <summary>창 위치/크기 (x, y, 너비, 높이)</summary>
    public int[]? WindowBounds { get; set; }

    public static string NewPin() => Random.Shared.Next(0, 10000).ToString("D4");

    public static string Dir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PenBridge");

    private static string FilePath => Path.Combine(Dir, "settings.json");

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
        }
        catch { /* 손상된 설정은 무시하고 기본값 */ }
        var s = new Settings();
        s.Save();
        return s;
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { /* 저장 실패는 치명적이지 않음 */ }
    }
}
