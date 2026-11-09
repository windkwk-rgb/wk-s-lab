using System.Runtime.InteropServices;

namespace PenBridge;

/// <summary>
/// Windows 합성 포인터(펜) 입력 API. Windows 10 1809 이상.
/// 구조체 레이아웃은 64비트(x64/ARM64) 기준이며 Program 시작 시 크기를 검증한다.
/// </summary>
internal static class Native
{
    public const int PT_PEN = 3;
    public const int POINTER_FEEDBACK_DEFAULT = 1;

    [Flags]
    public enum PointerFlags : uint
    {
        None = 0,
        New = 0x1,
        InRange = 0x2,
        InContact = 0x4,
        FirstButton = 0x10,
        SecondButton = 0x20,
        Primary = 0x2000,
        Confidence = 0x4000,
        Canceled = 0x8000,
        Down = 0x10000,
        Update = 0x20000,
        Up = 0x40000,
    }

    [Flags]
    public enum PenFlags : uint { None = 0, Barrel = 1, Inverted = 2, Eraser = 4 }

    [Flags]
    public enum PenMask : uint { None = 0, Pressure = 1, Rotation = 2, TiltX = 4, TiltY = 8 }

    public enum ButtonChange : int { None = 0, FirstDown = 1, FirstUp = 2, SecondDown = 3, SecondUp = 4 }

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int X, Y; }

    [StructLayout(LayoutKind.Explicit, Size = 96)]
    public struct POINTER_INFO
    {
        [FieldOffset(0)] public int pointerType;
        [FieldOffset(4)] public uint pointerId;
        [FieldOffset(8)] public uint frameId;
        [FieldOffset(12)] public PointerFlags pointerFlags;
        [FieldOffset(16)] public IntPtr sourceDevice;
        [FieldOffset(24)] public IntPtr hwndTarget;
        [FieldOffset(32)] public POINT ptPixelLocation;
        [FieldOffset(40)] public POINT ptHimetricLocation;
        [FieldOffset(48)] public POINT ptPixelLocationRaw;
        [FieldOffset(56)] public POINT ptHimetricLocationRaw;
        [FieldOffset(64)] public uint dwTime;
        [FieldOffset(68)] public uint historyCount;
        [FieldOffset(72)] public int inputData;
        [FieldOffset(76)] public uint dwKeyStates;
        [FieldOffset(80)] public ulong performanceCount;
        [FieldOffset(88)] public ButtonChange buttonChangeType;
    }

    [StructLayout(LayoutKind.Explicit, Size = 120)]
    public struct POINTER_PEN_INFO
    {
        [FieldOffset(0)] public POINTER_INFO pointerInfo;
        [FieldOffset(96)] public PenFlags penFlags;
        [FieldOffset(100)] public PenMask penMask;
        [FieldOffset(104)] public uint pressure;   // 0 ~ 1024
        [FieldOffset(108)] public uint rotation;
        [FieldOffset(112)] public int tiltX;       // -90 ~ 90
        [FieldOffset(116)] public int tiltY;
    }

    /// <summary>POINTER_TYPE_INFO: type + (touch|pen) union. union 크기는 POINTER_TOUCH_INFO(144)</summary>
    [StructLayout(LayoutKind.Explicit, Size = 152)]
    public struct POINTER_TYPE_INFO
    {
        [FieldOffset(0)] public int type;
        [FieldOffset(8)] public POINTER_PEN_INFO penInfo;
    }

    [DllImport("user32.dll", SetLastError = true)]
    public static extern IntPtr CreateSyntheticPointerDevice(int pointerType, uint maxCount, int mode);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool InjectSyntheticPointerInput(IntPtr device, [In] POINTER_TYPE_INFO[] pointerInfo, uint count);

    [DllImport("user32.dll")]
    public static extern void DestroySyntheticPointerDevice(IntPtr device);

    public static string? CheckLayout()
    {
        if (IntPtr.Size != 8) return "64비트 Windows에서만 동작합니다.";
        int s = Marshal.SizeOf<POINTER_TYPE_INFO>();
        if (s != 152) return $"내부 구조체 크기 오류 ({s})";
        if (Environment.OSVersion.Version.Build < 17763) return "Windows 10 1809 이상이 필요합니다.";
        return null;
    }
}
