namespace Siyue;

// Parsing policy is independent of the shared model credentials. Saved model
// settings cannot silently opt a document import back into inference.
public static class PdfParsingPolicy
{
    public static bool IsFixed(IConfiguration config) =>
        !string.Equals(config["Documents:ParsingStrategy"], "model-assisted", StringComparison.OrdinalIgnoreCase);

    public static string RecognitionMode(IConfiguration config, string? mode)
    {
        var validated = QuestionRecognition.Validate(mode);
        return IsFixed(config) && validated == QuestionRecognition.Auto ? QuestionRecognition.Named : validated;
    }
}

public partial class WordDocuments
{
    public string EffectiveRecognitionMode(string? mode) => PdfParsingPolicy.RecognitionMode(config, mode);
}
