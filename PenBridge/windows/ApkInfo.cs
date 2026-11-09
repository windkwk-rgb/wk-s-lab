using System.Buffers.Binary;
using System.IO.Compression;

namespace PenBridge;

/// <summary>
/// APK 안의 AndroidManifest.xml(바이너리 XML)에서 versionCode만 읽는다.
/// 외부 도구(aapt) 없이 태블릿 앱의 버전을 비교하기 위함.
/// </summary>
internal static class ApkInfo
{
    private const uint VersionCodeAttr = 0x0101021b;   // android:versionCode 리소스 ID

    public static int ReadVersionCode(string apkPath)
    {
        try
        {
            using var fs = new FileStream(apkPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var zip = new ZipArchive(fs, ZipArchiveMode.Read);
            var entry = zip.GetEntry("AndroidManifest.xml");
            if (entry == null) return -1;
            using var ms = new MemoryStream();
            using (var s = entry.Open()) s.CopyTo(ms);
            return Parse(ms.ToArray());
        }
        catch
        {
            return -1;
        }
    }

    /// <summary>10203 → "1.2.3"</summary>
    public static string ToVersionString(int code) =>
        code <= 0 ? "없음" : $"{code / 10000}.{code / 100 % 100}.{code % 100}";

    private static int U16(byte[] b, int o) => BinaryPrimitives.ReadUInt16LittleEndian(b.AsSpan(o));
    private static uint U32(byte[] b, int o) => BinaryPrimitives.ReadUInt32LittleEndian(b.AsSpan(o));

    private static int Parse(byte[] b)
    {
        if (b.Length < 8 || U16(b, 0) != 0x0003) return -1;
        uint[] resMap = Array.Empty<uint>();
        int off = U16(b, 2);
        while (off + 8 <= b.Length)
        {
            int type = U16(b, off);
            int header = U16(b, off + 2);
            int size = (int)U32(b, off + 4);
            if (size < 8 || off + size > b.Length) break;

            if (type == 0x0180)            // 리소스 ID 맵
            {
                int n = (size - header) / 4;
                resMap = new uint[n];
                for (int i = 0; i < n; i++) resMap[i] = U32(b, off + header + 4 * i);
            }
            else if (type == 0x0102)       // 첫 시작 태그 = <manifest>
            {
                int ext = off + header;
                int attrStart = U16(b, ext + 8);
                int attrSize = U16(b, ext + 10);
                int attrCount = U16(b, ext + 12);
                for (int i = 0; i < attrCount; i++)
                {
                    int a = ext + attrStart + i * attrSize;
                    if (a + 20 > b.Length) break;
                    uint name = U32(b, a + 4);
                    if (name < resMap.Length && resMap[name] == VersionCodeAttr)
                        return (int)U32(b, a + 16);
                }
                return -1;
            }
            off += size;
        }
        return -1;
    }
}
