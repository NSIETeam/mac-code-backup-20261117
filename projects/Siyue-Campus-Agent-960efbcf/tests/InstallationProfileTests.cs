using Siyue.Printing;
using Xunit;
public class InstallationProfileTests
{
    [Fact] public void TrimsLocationAndKeepsQueueIdentity() { var p = new InstallationProfile(" 校区 ", " Canon Queue ", " 银座晶都12B 东 ").Validate(); Assert.Equal("校区", p.CampusName); Assert.Equal("Canon Queue", p.QueueName); Assert.Equal("银座晶都12B 东", p.PrinterAlias); }
    [Theory]
    [InlineData("", "Queue", "东")][InlineData("校区", " ", "东")][InlineData("校区", "Queue", " ")][InlineData("校\n区", "Queue", "东")]
    public void RequiresAllInitializationFields(string campus, string queue, string alias) => Assert.Throws<ArgumentException>(() => new InstallationProfile(campus, queue, alias).Validate());
}
