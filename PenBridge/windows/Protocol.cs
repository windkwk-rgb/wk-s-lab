using System.Buffers.Binary;

namespace PenBridge;

/// <summary>
/// 태블릿 ↔ PC 통신 규격. 모든 패킷은 24바이트 고정, 리틀엔디언.
/// 자세한 내용은 PROTOCOL.md 참고.
/// </summary>
internal static class Proto
{
    public const int Port = 47100;
    public const int DiscoveryPort = 47101;
    public const int PacketSize = 24;
    public const byte Version = 1;

    // 태블릿 → PC
    public const byte Hello = 1;
    public const byte Pen = 2;
    public const byte Ping = 3;
    public const byte UpdateRequest = 5;   // 새 연결의 첫 패킷: 태블릿 앱 파일 요청
    public const byte Discover = 6;        // 새 연결의 첫 패킷: "PenBridge 맞아요?" (Wi‑Fi 찾기)
    public const byte DiscoverReply = 15;  // [4]=이름 길이, [8..23]=PC 이름(UTF‑8, 최대 16바이트)
    public const byte Action = 7;          // [1]=동작 번호, [2]=1 누름 / 0 뗌 (툴바 버튼, 손가락 탭)
    public const byte Gesture = 8;         // [1]=1 이동(f0=dx, f1=dy, dp) / 2 확대·축소(f0=배율)
    public const byte Stream = 9;          // 새 연결의 첫 패킷: 화면 보내기 요청 [4]=PIN, [8]=최대 폭(px)
    public const byte FrameAck = 20;       // 화면 한 장 받음
    public const byte Frame = 16;          // PC → 태블릿: [4]=JPEG 길이, [8]=폭, [12]=높이, 뒤이어 JPEG
    public const byte ToolState = 17;      // PC → 태블릿: [1]=지우개 켜짐
    // PC → 태블릿
    public const byte Pong = 4;
    public const byte Config = 10;
    public const byte Reject = 11;
    public const byte Accept = 12;
    public const byte UpdateOffer = 13;    // [4]=versionCode, [8]=크기(int64)
    public const byte UpdateData = 14;     // [4]=versionCode, [8]=크기(int64), 뒤이어 파일 내용

    // HELLO [20] 플래그
    public const byte HelloAutoUpdate = 1;

    // Pen 패킷 buttons 비트
    public const byte BtnBarrel = 1;
    public const byte BtnEraser = 2;
    public const byte BtnSecondary = 4;

    // 동작 번호 (태블릿 툴바/제스처와 같은 값)
    public static readonly string[] ActionIds =
        { "", "undo", "redo", "eraser", "sizeDown", "sizeUp", "hand", "zoomIn", "zoomOut", "fit", "save" };

    public const string DiscoverQuery = "PENBRIDGE?";
    public const string DiscoverReplyText = "PENBRIDGE!";

    public static byte[] Make(byte type, float f0 = 0, float f1 = 0)
    {
        var b = new byte[PacketSize];
        b[0] = type;
        b[1] = Version;
        BinaryPrimitives.WriteSingleLittleEndian(b.AsSpan(4), f0);
        BinaryPrimitives.WriteSingleLittleEndian(b.AsSpan(8), f1);
        return b;
    }

    /// <summary>PC 이름을 담은 패킷 (ACCEPT, DISCOVER_REPLY). [4]=길이, [8..23]=이름</summary>
    public static byte[] MakeNamed(byte type)
    {
        var b = Make(type);
        var name = System.Text.Encoding.UTF8.GetBytes(Environment.MachineName);
        int n = Math.Min(name.Length, 16);
        BinaryPrimitives.WriteInt32LittleEndian(b.AsSpan(4), n);
        Array.Copy(name, 0, b, 8, n);
        return b;
    }

    public static PenSample ReadPen(ReadOnlySpan<byte> b) => new(
        (PenState)b[1],
        b[2],
        BinaryPrimitives.ReadSingleLittleEndian(b[4..]),
        BinaryPrimitives.ReadSingleLittleEndian(b[8..]),
        BinaryPrimitives.ReadSingleLittleEndian(b[12..]),
        BinaryPrimitives.ReadSingleLittleEndian(b[16..]),
        BinaryPrimitives.ReadSingleLittleEndian(b[20..]));
}

internal enum PenState : byte { Hover = 0, Contact = 1, OutOfRange = 2 }

internal readonly record struct PenSample(
    PenState State, byte Buttons, float X, float Y, float Pressure, float TiltX, float TiltY);
