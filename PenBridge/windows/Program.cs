namespace PenBridge;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        if (args.Contains("--uninstall"))
        {
            Application.EnableVisualStyles();
            Installer.Uninstall();
            return;
        }

        // 업데이트/설치 직후에는 이전 프로세스가 끝날 때까지 기다림
        bool updated = args.Contains("--updated") || args.Contains("--installed");

        // 중복 실행 방지 (포트가 겹치므로).
        // 업데이트 직후에는 이전 버전이 종료될 때까지 잠시 기다린다.
        using var mutex = new Mutex(false, "PenBridge.SingleInstance");
        bool owned;
        try { owned = mutex.WaitOne(updated ? 20000 : 0); }
        catch (AbandonedMutexException) { owned = true; }
        if (!owned)
        {
            MessageBox.Show("PenBridge가 이미 실행 중이에요. 작업 표시줄 오른쪽 아이콘을 확인해 주세요.",
                "PenBridge", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        var problem = Native.CheckLayout();
        if (problem != null)
        {
            MessageBox.Show(problem, "PenBridge", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        try
        {
            Application.Run(new MainForm(args.Contains("--minimized"), args.Contains("--updated"), args.Contains("--installed")));
        }
        catch (Exception e)
        {
            MessageBox.Show("시작하지 못했어요:\n" + e.Message, "PenBridge", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        finally
        {
            try { mutex.ReleaseMutex(); } catch { }
        }
    }
}
