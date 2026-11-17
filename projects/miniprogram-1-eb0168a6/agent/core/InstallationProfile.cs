namespace Siyue.Printing;

public record InstallationProfile(string CampusName, string QueueName, string PrinterAlias)
{
    public InstallationProfile Validate()
    {
        static string Required(string? value, int max, string label)
        {
            var text = value?.Trim() ?? "";
            if (text.Length < 1 || text.Length > max || text.Any(char.IsControl))
                throw new ArgumentException($"{label}需要填写 1–{max} 个有效字符");
            return text;
        }
        var queue = Required(QueueName, 200, "本机打印队列");
        if (IsMicrosoftPdfPrinter(queue)) throw new ArgumentException("请选择实体打印机");
        return new(Required(CampusName, 120, "所属校区"), queue, Required(PrinterAlias, 120, "打印机别名"));
    }

    public static bool IsMicrosoftPdfPrinter(string? queueName, string? driver = null)
    {
        static bool Matches(string? value) => value?.Trim().ToUpperInvariant() is
            "MICROSOFT PRINT TO PDF" or "MICROSOFT PRINT TO PDF V4" or
            "MICROSOFT 打印到 PDF" or "MICROSOFT PDF 打印";
        if (Matches(driver)) return true;
        if (queueName is null) return false;
        return Matches(queueName[(queueName.LastIndexOf('\\') + 1)..]);
    }
}
