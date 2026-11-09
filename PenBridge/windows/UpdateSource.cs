namespace PenBridge;

/// <summary>
/// 인터넷 업데이트를 받아 올 GitHub 저장소.
/// 여러 프로젝트가 함께 있는 저장소라서, PenBridge Release만 태그 접두사(penbridge-v)로 골라낸다.
/// </summary>
internal static class UpdateSource
{
    public const string Repo = "windkwk-rgb/wk-s-lab";
    public const string TagPrefix = "penbridge-v";
}
