using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;
namespace Siyue;
// Production migrations always target PostgreSQL, independent of local preview settings.
public sealed class DesignTimeData : IDesignTimeDbContextFactory<Data>
{
    public Data CreateDbContext(string[] args) => new(new DbContextOptionsBuilder<Data>().UseNpgsql("Host=localhost;Database=siyue").Options);
}
