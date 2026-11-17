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
        return new(Required(CampusName, 120, "所属校区"), Required(QueueName, 200, "本机打印队列"), Required(PrinterAlias, 120, "打印机别名"));
    }
}
