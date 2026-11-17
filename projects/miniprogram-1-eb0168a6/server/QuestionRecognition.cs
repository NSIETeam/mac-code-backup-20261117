using System.Text.RegularExpressions;
namespace Siyue;

// The mode follows one asynchronous document operation, including its OCR fallbacks.
// AsyncLocal prevents simultaneous imports from changing one another's rules.
public static class QuestionRecognition
{
    public const string Auto = "auto";
    public const string Named = "named";
    public const string Legacy = "legacy";
    static readonly AsyncLocal<string?> Current = new();
    public static string Mode => Current.Value ?? Legacy;
    public static bool NamedOnly => Mode == Named;
    public static readonly Regex NamedStart = new(@"^\s*(?<label>[【\[]?\s*(?<family>例(?:题)?|练(?:习题|习)?|测(?:试题|试)?)\s*[0-9０-９一二三四五六七八九十百]+(?:\s*[-－﹣—]\s*[0-9０-９]+)*\s*[】\]]?\s*[.．、:：]?)\s*", RegexOptions.Compiled);
    public static string Validate(string? mode) {
        var value = string.IsNullOrWhiteSpace(mode) ? Auto : mode;
        Guard.That(value is Auto or Named or Legacy, "请选择有效的题号识别方式");
        return value;
    }
    public static IDisposable Use(string mode) {
        var previous = Current.Value; Current.Value = Validate(mode); return new Restore(previous);
    }
    sealed class Restore(string? previous) : IDisposable {
        public void Dispose() => Current.Value = previous;
    }
}
