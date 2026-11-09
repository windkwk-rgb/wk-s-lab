using System.Buffers.Binary;
using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace PenBridge;

/// <summary>
/// 액정 타블렛 모드: 그리기 영역 모니터의 화면을 태블릿으로 보낸다.
///
/// - 태블릿이 별도 연결로 STREAM을 요청하면 시작 (한 번에 한 대, 새 요청이 오면 이전 것은 끝냄)
/// - 한 장 보내고 태블릿이 "받았어요(FRAME_ACK)"라고 해야 다음 장 → 화면이 밀려 늦어지지 않음
/// - 화면이 그대로면 보내지 않음 (가만히 있을 때 네트워크·CPU를 거의 안 씀)
/// - JPEG: 간단하고 태블릿에서 빠르게 풀 수 있음. 화질 3단계
/// </summary>
internal sealed class ScreenStreamer
{
    private readonly Settings _settings;
    private readonly Func<Rectangle> _target;
    private int _generation;

    public volatile bool Active;
    public volatile int Fps;
    public volatile string FrameSize = "";
    public volatile int KbPerSec;

    public ScreenStreamer(Settings settings, Func<Rectangle> target)
    {
        _settings = settings;
        _target = target;
    }

    private static (int maxW, long quality) Quality(int level) => level switch
    {
        0 => (1024, 55L),   // 부드럽게: 작고 빠름
        2 => (1920, 85L),   // 선명하게
        _ => (1440, 72L),   // 균형
    };

    /// <summary>연결 하나를 맡아 끝날 때까지 보냄 (연결 스레드에서 호출)</summary>
    public void Serve(TcpClient client, NetworkStream stream, int tabletMaxW)
    {
        if (!_settings.MirrorEnabled)
        {
            stream.Write(Proto.Make(Proto.Reject));
            return;
        }
        int gen = Interlocked.Increment(ref _generation);
        Thread.CurrentThread.Priority = ThreadPriority.Normal;   // 펜 입력보다 낮게
        client.NoDelay = true;
        client.SendTimeout = 5000;
        client.ReceiveTimeout = 5000;
        stream.Write(Proto.Make(Proto.Accept));

        Bitmap? full = null, small = null;
        byte[]? lastThumb = null;
        var jpegCodec = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
        var ack = new byte[Proto.PacketSize];
        var fpsWatch = Stopwatch.StartNew();
        var lastSent = Stopwatch.StartNew();
        int frames = 0;
        long bytes = 0;
        Active = true;
        try
        {
            while (gen == _generation)
            {
                var frameStart = Stopwatch.GetTimestamp();
                var r = _target();
                if (r.Width <= 0 || r.Height <= 0) { Thread.Sleep(200); continue; }

                var (qMaxW, q) = Quality(_settings.MirrorQuality);
                int maxW = Math.Max(320, Math.Min(qMaxW, tabletMaxW > 0 ? tabletMaxW : qMaxW));
                int w = Math.Min(r.Width, maxW);
                int h = Math.Max(1, (int)Math.Round(r.Height * (double)w / r.Width));

                if (full == null || full.Width != r.Width || full.Height != r.Height)
                {
                    full?.Dispose();
                    full = new Bitmap(r.Width, r.Height, PixelFormat.Format32bppRgb);
                }
                if (small == null || small.Width != w || small.Height != h)
                {
                    small?.Dispose();
                    small = new Bitmap(w, h, PixelFormat.Format24bppRgb);
                }

                using (var g = Graphics.FromImage(full))
                    g.CopyFromScreen(r.X, r.Y, 0, 0, r.Size, CopyPixelOperation.SourceCopy);

                // 화면이 바뀌지 않았으면 건너뜀 (단, 2초마다는 한 장 보냄: 연결 확인 겸)
                var thumb = Thumbnail(full);
                if (lastThumb != null && thumb.AsSpan().SequenceEqual(lastThumb) && lastSent.ElapsedMilliseconds < 2000)
                {
                    Thread.Sleep(25);
                    UpdateStats(fpsWatch, ref frames, ref bytes);
                    continue;
                }
                lastThumb = thumb;

                Bitmap src = full;
                if (w != r.Width)
                {
                    using var g2 = Graphics.FromImage(small);
                    g2.InterpolationMode = InterpolationMode.Bilinear;
                    g2.PixelOffsetMode = PixelOffsetMode.HighSpeed;
                    g2.CompositingQuality = CompositingQuality.HighSpeed;
                    g2.DrawImage(full, new Rectangle(0, 0, w, h));
                    src = small;
                }

                using var ms = new MemoryStream(256 * 1024);
                using (var ep = new EncoderParameters(1))
                {
                    ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, q);
                    src.Save(ms, jpegCodec, ep);
                }

                var header = Proto.Make(Proto.Frame);
                BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(4), (int)ms.Length);
                BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(8), w);
                BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(12), h);
                stream.Write(header);
                stream.Write(ms.GetBuffer(), 0, (int)ms.Length);
                lastSent.Restart();
                FrameSize = $"{w}×{h}";
                frames++;
                bytes += ms.Length;

                // 태블릿이 받았다고 할 때까지 기다림 (한 장씩)
                int got = 0;
                while (got < ack.Length)
                {
                    int n = stream.Read(ack, got, ack.Length - got);
                    if (n <= 0) throw new IOException("끊김");
                    got += n;
                }

                UpdateStats(fpsWatch, ref frames, ref bytes);

                // 최대 초당 30장
                var elapsedMs = (Stopwatch.GetTimestamp() - frameStart) * 1000 / Stopwatch.Frequency;
                if (elapsedMs < 33) Thread.Sleep((int)(33 - elapsedMs));
            }
        }
        catch
        {
            // 연결 끊김 → 끝
        }
        finally
        {
            full?.Dispose();
            small?.Dispose();
            if (gen == _generation)
            {
                Active = false;
                Fps = 0;
                KbPerSec = 0;
            }
        }
    }

    private void UpdateStats(Stopwatch watch, ref int frames, ref long bytes)
    {
        if (watch.ElapsedMilliseconds < 1000) return;
        Fps = (int)Math.Round(frames * 1000.0 / watch.ElapsedMilliseconds);
        KbPerSec = (int)(bytes * 1000 / 1024 / Math.Max(1, watch.ElapsedMilliseconds));
        frames = 0;
        bytes = 0;
        watch.Restart();
    }

    /// <summary>화면 변화 감지용: 화면 전체에서 고르게 뽑은 점들의 색</summary>
    private static byte[] Thumbnail(Bitmap bmp)
    {
        const int gx = 48, gy = 27;
        var data = bmp.LockBits(new Rectangle(0, 0, bmp.Width, bmp.Height), ImageLockMode.ReadOnly, PixelFormat.Format32bppRgb);
        try
        {
            var result = new byte[gx * gy * 4];
            var row = new byte[4];
            for (int y = 0; y < gy; y++)
            {
                int py = (int)((y + 0.5) * bmp.Height / gy);
                for (int x = 0; x < gx; x++)
                {
                    int px = (int)((x + 0.5) * bmp.Width / gx);
                    Marshal.Copy(data.Scan0 + py * data.Stride + px * 4, row, 0, 4);
                    Array.Copy(row, 0, result, (y * gx + x) * 4, 4);
                }
            }
            return result;
        }
        finally
        {
            bmp.UnlockBits(data);
        }
    }

    /// <summary>보내는 중인 화면을 끝냄 (설정에서 끈 경우)</summary>
    public void StopAll() => Interlocked.Increment(ref _generation);
}
