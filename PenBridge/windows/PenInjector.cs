using System.ComponentModel;
using static PenBridge.Native;

namespace PenBridge;

/// <summary>
/// 태블릿에서 받은 펜 샘플을 Windows의 "진짜 펜 입력"으로 넣는다.
/// 호버 → 접촉 → 떼기 → 범위 벗어남 상태를 추적해서 올바른 플래그 순서를 만든다.
/// 한 번에 한 스레드에서만 호출한다(PenServer가 lock으로 보장).
/// </summary>
internal sealed class PenInjector : IDisposable
{
    private IntPtr _device;
    private bool _inRange, _inContact;
    private int _lastX, _lastY;
    private readonly POINTER_TYPE_INFO[] _buf = new POINTER_TYPE_INFO[1];

    public int ErrorCount { get; private set; }
    public int LastError { get; private set; }

    public PenInjector()
    {
        _device = CreateSyntheticPointerDevice(PT_PEN, 1, POINTER_FEEDBACK_DEFAULT);
        if (_device == IntPtr.Zero)
            throw new Win32Exception("펜 입력 장치를 만들 수 없습니다");
    }

    /// <param name="target">펜 영역이 대응될 화면 영역(물리 픽셀)</param>
    /// <param name="gamma">필압 곡선. 1=그대로, &lt;1 부드럽게(약하게 눌러도 진하게), &gt;1 단단하게</param>
    public void Apply(in PenSample s, Rectangle target, double gamma)
    {
        if (s.State == PenState.OutOfRange)
        {
            Release();
            return;
        }

        int x = target.X + (int)Math.Round(Math.Clamp(s.X, 0f, 1f) * (target.Width - 1));
        int y = target.Y + (int)Math.Round(Math.Clamp(s.Y, 0f, 1f) * (target.Height - 1));
        bool wantContact = s.State == PenState.Contact;

        // 범위 밖에서 곧바로 접촉하면 Windows가 거부하는 경우가 있어 먼저 호버 프레임을 보낸다.
        if (!_inRange)
        {
            Send(x, y, PointerFlags.InRange | PointerFlags.Update, ButtonChange.None, s, 0);
            _inRange = true;
        }

        PointerFlags flags;
        ButtonChange change = ButtonChange.None;

        if (wantContact && !_inContact)
        {
            flags = PointerFlags.InRange | PointerFlags.InContact | PointerFlags.FirstButton | PointerFlags.Down;
            change = ButtonChange.FirstDown;
        }
        else if (!wantContact && _inContact)
        {
            flags = PointerFlags.InRange | PointerFlags.Up;
            change = ButtonChange.FirstUp;
        }
        else if (wantContact)
        {
            flags = PointerFlags.InRange | PointerFlags.InContact | PointerFlags.FirstButton | PointerFlags.Update;
        }
        else
        {
            flags = PointerFlags.InRange | PointerFlags.Update;
        }

        uint pressure = 0;
        if (wantContact)
        {
            double p = Math.Pow(Math.Clamp(s.Pressure, 0f, 1f), gamma);
            pressure = (uint)Math.Clamp(Math.Round(p * 1024), 1, 1024);
        }

        Send(x, y, flags, change, s, pressure);
        _inContact = wantContact;
    }

    /// <summary>펜을 떼고 범위 밖으로 보낸다(연결 끊김, 앱 전환 등).</summary>
    public void Release()
    {
        if (_inContact)
            Send(_lastX, _lastY, PointerFlags.InRange | PointerFlags.Up, ButtonChange.FirstUp, default, 0);
        if (_inRange || _inContact)
            Send(_lastX, _lastY, PointerFlags.Update, ButtonChange.None, default, 0);
        _inContact = false;
        _inRange = false;
    }

    private void Send(int x, int y, PointerFlags flags, ButtonChange change, in PenSample s, uint pressure)
    {
        if (_device == IntPtr.Zero) return;

        var penFlags = PenFlags.None;
        if ((s.Buttons & Proto.BtnBarrel) != 0) penFlags |= PenFlags.Barrel;
        if ((s.Buttons & Proto.BtnEraser) != 0)
            penFlags |= (flags & PointerFlags.InContact) != 0 ? PenFlags.Eraser : PenFlags.Inverted;

        ref var info = ref _buf[0];
        info = default;
        info.type = PT_PEN;
        info.penInfo.pointerInfo.pointerType = PT_PEN;
        info.penInfo.pointerInfo.pointerFlags = flags | PointerFlags.Primary | PointerFlags.Confidence;
        info.penInfo.pointerInfo.ptPixelLocation = new POINT { X = x, Y = y };
        info.penInfo.pointerInfo.buttonChangeType = change;
        info.penInfo.penFlags = penFlags;
        info.penInfo.penMask = PenMask.Pressure | PenMask.TiltX | PenMask.TiltY;
        info.penInfo.pressure = pressure;
        info.penInfo.tiltX = Math.Clamp((int)Math.Round(s.TiltX), -90, 90);
        info.penInfo.tiltY = Math.Clamp((int)Math.Round(s.TiltY), -90, 90);

        if (!InjectSyntheticPointerInput(_device, _buf, 1))
        {
            ErrorCount++;
            LastError = System.Runtime.InteropServices.Marshal.GetLastWin32Error();
            // 상태가 꼬였을 수 있으니 처음부터 다시 시작
            _inContact = false;
            _inRange = false;
        }
        _lastX = x;
        _lastY = y;
    }

    public void Dispose()
    {
        try { Release(); } catch { /* 무시 */ }
        if (_device != IntPtr.Zero)
        {
            DestroySyntheticPointerDevice(_device);
            _device = IntPtr.Zero;
        }
    }
}
