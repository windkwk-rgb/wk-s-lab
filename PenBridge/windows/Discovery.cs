using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace PenBridge;

/// <summary>
/// 태블릿 앱의 "Wi‑Fi에서 찾기"에 응답한다.
/// 태블릿이 UDP 브로드캐스트로 "PENBRIDGE?"를 보내면 "PENBRIDGE!|PC이름|포트"로 답한다.
/// </summary>
internal sealed class DiscoveryResponder : IDisposable
{
    private UdpClient? _udp;
    private volatile bool _stopped;

    public void Start()
    {
        _udp = new UdpClient(new IPEndPoint(IPAddress.Any, Proto.DiscoveryPort)) { EnableBroadcast = true };
        new Thread(Loop) { IsBackground = true, Name = "PenBridge discovery" }.Start();
    }

    private void Loop()
    {
        var reply = Encoding.UTF8.GetBytes($"{Proto.DiscoverReplyText}|{Environment.MachineName}|{Proto.Port}");
        while (!_stopped)
        {
            try
            {
                var from = new IPEndPoint(IPAddress.Any, 0);
                var data = _udp!.Receive(ref from);
                if (Encoding.UTF8.GetString(data).StartsWith(Proto.DiscoverQuery))
                    _udp.Send(reply, reply.Length, from);
            }
            catch
            {
                if (_stopped) return;
                Thread.Sleep(100);
            }
        }
    }

    /// <summary>수동 입력용으로 보여줄 이 PC의 IPv4 주소들(실제로 연결된 네트워크만).</summary>
    public static IEnumerable<string> LocalAddresses()
    {
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
            var props = ni.GetIPProperties();
            if (props.GatewayAddresses.Count == 0) continue;   // 가상 어댑터 등 제외
            foreach (var ua in props.UnicastAddresses)
                if (ua.Address.AddressFamily == AddressFamily.InterNetwork)
                    yield return ua.Address.ToString();
        }
    }

    public void Dispose()
    {
        _stopped = true;
        _udp?.Dispose();
    }
}
