using System.Runtime.InteropServices;

namespace PenBridge;

/// <summary>단축키 동작 하나 (툴바 버튼 / 손가락 제스처)</summary>
internal sealed record ShortcutAction(string Id, string Label, string Hint);

/// <summary>
/// 그림 프로그램별 단축키 프리셋 + 사용자 지정.
/// 키 표기: "Ctrl+Shift+Z", "[", "Space", "F5" … (KeyInput.Parse 참고)
/// </summary>
internal static class Shortcuts
{
    public static readonly ShortcutAction[] Actions =
    {
        new("undo", "실행 취소", "두 손가락 탭"),
        new("redo", "다시 실행", "세 손가락 탭"),
        new("eraser", "지우개", "툴바 · 다시 누르면 펜"),
        new("pen", "펜으로", "지우개를 끌 때"),
        new("sizeDown", "브러시 작게", "툴바 −"),
        new("sizeUp", "브러시 크게", "툴바 +"),
        new("hand", "손 도구", "누르는 동안 이동"),
        new("zoomIn", "확대", "툴바 +돋보기"),
        new("zoomOut", "축소", "툴바 −돋보기"),
        new("fit", "화면에 맞춤", "툴바 맞춤"),
        new("save", "저장", "툴바 저장"),
    };

    public static readonly (string Id, string Name)[] Profiles =
    {
        ("common", "Windows 기본"),
        ("csp", "클립 스튜디오"),
        ("ps", "포토샵"),
        ("krita", "Krita"),
    };

    private static readonly Dictionary<string, Dictionary<string, string>> Defaults = new()
    {
        ["common"] = new()
        {
            ["undo"] = "Ctrl+Z", ["redo"] = "Ctrl+Y", ["eraser"] = "E", ["pen"] = "B",
            ["sizeDown"] = "[", ["sizeUp"] = "]", ["hand"] = "Space",
            ["zoomIn"] = "Ctrl+=", ["zoomOut"] = "Ctrl+-", ["fit"] = "Ctrl+0", ["save"] = "Ctrl+S",
            ["zoomWheel"] = "Ctrl",
        },
        ["csp"] = new()
        {
            ["undo"] = "Ctrl+Z", ["redo"] = "Ctrl+Y", ["eraser"] = "E", ["pen"] = "P",
            ["sizeDown"] = "[", ["sizeUp"] = "]", ["hand"] = "Space",
            ["zoomIn"] = "Ctrl+=", ["zoomOut"] = "Ctrl+-", ["fit"] = "Ctrl+0", ["save"] = "Ctrl+S",
            ["zoomWheel"] = "Ctrl",
        },
        ["ps"] = new()
        {
            ["undo"] = "Ctrl+Z", ["redo"] = "Ctrl+Shift+Z", ["eraser"] = "E", ["pen"] = "B",
            ["sizeDown"] = "[", ["sizeUp"] = "]", ["hand"] = "Space",
            ["zoomIn"] = "Ctrl+=", ["zoomOut"] = "Ctrl+-", ["fit"] = "Ctrl+0", ["save"] = "Ctrl+S",
            ["zoomWheel"] = "Alt",
        },
        ["krita"] = new()
        {
            ["undo"] = "Ctrl+Z", ["redo"] = "Ctrl+Shift+Z", ["eraser"] = "E", ["pen"] = "E",
            ["sizeDown"] = "[", ["sizeUp"] = "]", ["hand"] = "Space",
            ["zoomIn"] = "Ctrl+=", ["zoomOut"] = "Ctrl+-", ["fit"] = "2", ["save"] = "Ctrl+S",
            ["zoomWheel"] = "Ctrl",
        },
    };

    public static string Profile(Settings s) => Defaults.ContainsKey(s.ShortcutProfile) ? s.ShortcutProfile : "common";

    public static string DefaultFor(string profile, string id) =>
        Defaults.TryGetValue(profile, out var d) && d.TryGetValue(id, out var k) ? k : "";

    /// <summary>현재 프로필의 실제 키 (사용자 지정이 있으면 그것)</summary>
    public static string KeyFor(Settings s, string id)
    {
        var p = Profile(s);
        if (s.KeyOverrides.TryGetValue($"{p}.{id}", out var k) && k.Length > 0) return k;
        return DefaultFor(p, id);
    }
}

/// <summary>
/// 키보드/휠 입력을 Windows에 보낸다 (SendInput).
/// 그림 프로그램이 관리자 권한으로 실행 중이면 Windows가 막으므로 동작하지 않는다.
/// </summary>
internal static class KeyInput
{
    private const uint INPUT_MOUSE = 0, INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_KEYUP = 0x2, KEYEVENTF_EXTENDEDKEY = 0x1;
    private const uint MOUSEEVENTF_WHEEL = 0x0800, MOUSEEVENTF_HWHEEL = 0x01000;

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT { public int dx, dy; public int mouseData; public uint dwFlags, time; public IntPtr extra; }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr extra; }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT { public uint type; public InputUnion u; }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, INPUT[] inputs, int size);

    private static readonly int InputSize = Marshal.SizeOf<INPUT>();

    private static readonly Dictionary<string, ushort> Named = new(StringComparer.OrdinalIgnoreCase)
    {
        ["Ctrl"] = 0x11, ["Shift"] = 0x10, ["Alt"] = 0x12, ["Win"] = 0x5B,
        ["Space"] = 0x20, ["Tab"] = 0x09, ["Enter"] = 0x0D, ["Esc"] = 0x1B, ["Backspace"] = 0x08,
        ["Delete"] = 0x2E, ["Insert"] = 0x2D, ["Home"] = 0x24, ["End"] = 0x23, ["PageUp"] = 0x21, ["PageDown"] = 0x22,
        ["Left"] = 0x25, ["Up"] = 0x26, ["Right"] = 0x27, ["Down"] = 0x28,
        ["["] = 0xDB, ["]"] = 0xDD, ["-"] = 0xBD, ["="] = 0xBB, ["Plus"] = 0xBB, [","] = 0xBC, ["."] = 0xBE,
        ["/"] = 0xBF, [";"] = 0xBA, ["'"] = 0xDE, ["`"] = 0xC0, ["\\"] = 0xDC,
    };

    private static readonly HashSet<ushort> Extended = new() { 0x2D, 0x2E, 0x24, 0x23, 0x21, 0x22, 0x25, 0x26, 0x27, 0x28 };

    /// <summary>"Ctrl+Shift+Z" → [Ctrl, Shift, Z]. 모르는 키가 있으면 빈 배열</summary>
    public static ushort[] Parse(string spec)
    {
        if (string.IsNullOrWhiteSpace(spec)) return Array.Empty<ushort>();
        // "Ctrl++" 같은 표기도 처리: 마지막 '+'는 키 자체
        var parts = new List<string>();
        var s = spec.Trim();
        if (s.EndsWith("++")) { parts.AddRange(s[..^2].Split('+', StringSplitOptions.RemoveEmptyEntries)); parts.Add("="); }
        else parts.AddRange(s.Split('+', StringSplitOptions.RemoveEmptyEntries));

        var vks = new List<ushort>();
        foreach (var raw in parts)
        {
            var p = raw.Trim();
            if (Named.TryGetValue(p, out var vk)) vks.Add(vk);
            else if (p.Length == 1 && char.IsLetterOrDigit(p[0])) vks.Add((ushort)char.ToUpperInvariant(p[0]));
            else if (p.Length is 2 or 3 && (p[0] == 'F' || p[0] == 'f') && int.TryParse(p[1..], out var f) && f is >= 1 and <= 24)
                vks.Add((ushort)(0x70 + f - 1));
            else return Array.Empty<ushort>();
        }
        return vks.ToArray();
    }

    private static INPUT Key(ushort vk, bool up) => new()
    {
        type = INPUT_KEYBOARD,
        u = new InputUnion
        {
            ki = new KEYBDINPUT
            {
                wVk = vk,
                dwFlags = (up ? KEYEVENTF_KEYUP : 0) | (Extended.Contains(vk) ? KEYEVENTF_EXTENDEDKEY : 0),
            },
        },
    };

    private static void Send(List<INPUT> list)
    {
        if (list.Count > 0) SendInput((uint)list.Count, list.ToArray(), InputSize);
    }

    /// <summary>단축키 한 번 누르기 (모든 키 누름 → 거꾸로 뗌)</summary>
    public static void Tap(string spec)
    {
        var vks = Parse(spec);
        if (vks.Length == 0) return;
        var list = new List<INPUT>();
        foreach (var vk in vks) list.Add(Key(vk, false));
        for (int i = vks.Length - 1; i >= 0; i--) list.Add(Key(vks[i], true));
        Send(list);
    }

    /// <summary>누르고 있기 / 떼기 (손 도구처럼)</summary>
    public static void Hold(string spec, bool down)
    {
        var vks = Parse(spec);
        if (vks.Length == 0) return;
        var list = new List<INPUT>();
        if (down) foreach (var vk in vks) list.Add(Key(vk, false));
        else for (int i = vks.Length - 1; i >= 0; i--) list.Add(Key(vks[i], true));
        Send(list);
    }

    /// <summary>마우스 휠 (modifier: "Ctrl"/"Alt" 등을 누른 채로)</summary>
    public static void Wheel(int delta, bool horizontal, string modifier = "")
    {
        if (delta == 0) return;
        var mods = Parse(modifier);
        var list = new List<INPUT>();
        foreach (var vk in mods) list.Add(Key(vk, false));
        list.Add(new INPUT
        {
            type = INPUT_MOUSE,
            u = new InputUnion { mi = new MOUSEINPUT { mouseData = delta, dwFlags = horizontal ? MOUSEEVENTF_HWHEEL : MOUSEEVENTF_WHEEL } },
        });
        for (int i = mods.Length - 1; i >= 0; i--) list.Add(Key(mods[i], true));
        Send(list);
    }
}

/// <summary>
/// 태블릿에서 온 동작/제스처를 키 입력으로 바꾼다. 연결 하나당 하나.
/// </summary>
internal sealed class ShortcutRunner
{
    private readonly Settings _settings;
    private bool _eraser;
    private string? _holding;
    private double _panX, _panY, _zoom;

    public ShortcutRunner(Settings settings) => _settings = settings;

    /// <summary>지우개 상태가 바뀌면 호출 (태블릿 버튼 표시용)</summary>
    public event Action<bool>? EraserChanged;

    public void OnAction(int id, bool down)
    {
        if (id <= 0 || id >= Proto.ActionIds.Length) return;
        var name = Proto.ActionIds[id];
        if (name == "hand")
        {
            var key = Shortcuts.KeyFor(_settings, "hand");
            if (down) { _holding = key; KeyInput.Hold(key, true); }
            else ReleaseHold();
            return;
        }
        if (!down) return;
        if (name == "eraser")
        {
            _eraser = !_eraser;
            KeyInput.Tap(Shortcuts.KeyFor(_settings, _eraser ? "eraser" : "pen"));
            EraserChanged?.Invoke(_eraser);
            return;
        }
        KeyInput.Tap(Shortcuts.KeyFor(_settings, name));
    }

    /// <summary>kind 1 = 이동(dx, dy: dp), 2 = 확대·축소(a = 배율)</summary>
    public void OnGesture(int kind, float a, float b)
    {
        const double wheelPerDp = 3.0;              // 손가락 40dp ≈ 휠 한 칸
        if (kind == 1)
        {
            // 손가락을 내리면 내용이 내려오도록 (태블릿/휴대폰과 같은 방향)
            _panY += b * wheelPerDp;
            _panX += -a * wheelPerDp;
            int y = (int)_panY, x = (int)_panX;
            if (y != 0) { KeyInput.Wheel(y, false); _panY -= y; }
            if (x != 0) { KeyInput.Wheel(x, true); _panX -= x; }
        }
        else if (kind == 2 && a > 0)
        {
            _zoom += Math.Log(a);
            var notch = Math.Log(1.15);
            var mod = Shortcuts.KeyFor(_settings, "zoomWheel");
            while (_zoom >= notch) { KeyInput.Wheel(120, false, mod); _zoom -= notch; }
            while (_zoom <= -notch) { KeyInput.Wheel(-120, false, mod); _zoom += notch; }
        }
    }

    public void ReleaseHold()
    {
        if (_holding != null) { KeyInput.Hold(_holding, false); _holding = null; }
    }
}
