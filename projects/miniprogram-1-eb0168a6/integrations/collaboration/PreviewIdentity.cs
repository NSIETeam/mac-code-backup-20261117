namespace Siyue.Collaboration;
// Only registered in the isolated Testing environment, which has no Git or delivery keys.
public sealed class PreviewIdentity(IConfiguration config):IIdentityDirectory {
 public static readonly Person Teacher=new(Guid.Parse("55555555-5555-4555-8555-555555555555"),"模拟老师","teacher","preview-teacher",true,"模拟教研组");
 public Task<Person?> Session(string hash,CancellationToken ct=default)=>Task.FromResult<Person?>(Check.Same(hash,Check.Hash(config["Preview:SessionToken"]!))?Teacher:null);
 public Task<Person[]> People(CancellationToken ct=default)=>Task.FromResult(new[]{Teacher,new Person(Guid.Parse("66666666-6666-4666-8666-666666666666"),"模拟协作者","teacher","preview-peer",true,"模拟教研组")});
}
