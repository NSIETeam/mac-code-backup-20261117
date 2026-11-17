using System.ComponentModel;
using System.Runtime.InteropServices;
namespace Siyue.Agent;
sealed class SpoolerEvidence
{
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct JobInfo { public uint Id;public string? Printer,Machine,User,Document,Datatype,StatusText;public uint Status,Priority,Position,TotalPages,PagesPrinted;public short Year,Month,DayOfWeek,Day,Hour,Minute,Second,Milliseconds; }
    [DllImport("winspool.drv",EntryPoint="OpenPrinterW",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool OpenPrinter(string name,out IntPtr printer,IntPtr defaults);
    [DllImport("winspool.drv",SetLastError=true)] static extern bool ClosePrinter(IntPtr printer);
    [DllImport("winspool.drv",EntryPoint="EnumJobsW",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool EnumJobs(IntPtr printer,uint first,uint count,uint level,IntPtr buffer,uint size,out uint needed,out uint returned);
    public bool Observed {get;private set;} public uint Flags {get;private set;} public bool Failed {get;private set;} readonly HashSet<uint> ids=[];
    public string JobIds=>string.Join(",",ids.Order().Take(8));
    public async Task Watch(string queue,Guid job,CancellationToken ct) {
        while(!ct.IsCancellationRequested){try{Read(queue,job);}catch{Failed=true;}try{await Task.Delay(200,ct);}catch(OperationCanceledException){break;}}
    }
    void Read(string queue,Guid job) {
        foreach(var x in ReadJobs(queue,job)){Observed=true;Flags|=x.Flags;ids.Add(x.Id);}
    }
    public record QueueJob(uint Id,uint Flags,uint PagesPrinted);
    public static QueueJob[] ReadJobs(string queue,Guid job) {
        var result=new List<QueueJob>();
        if(!OpenPrinter(queue,out var printer,IntPtr.Zero))throw new Win32Exception();
        try {
            var ok=EnumJobs(printer,0,200,1,IntPtr.Zero,0,out var needed,out _);
            if(needed==0){if(!ok)throw new Win32Exception();return [];}
            if(needed>4*1024*1024)throw new IOException();var buffer=Marshal.AllocHGlobal((int)needed);
            try{if(!EnumJobs(printer,0,200,1,buffer,needed,out _,out var count))throw new Win32Exception();
                for(var i=0;i<count;i++){var x=Marshal.PtrToStructure<JobInfo>(IntPtr.Add(buffer,i*Marshal.SizeOf<JobInfo>()));
                    if(x.Document?.Contains(job.ToString(),StringComparison.OrdinalIgnoreCase)==true)result.Add(new(x.Id,x.Status,x.PagesPrinted));
                }
            }finally{Marshal.FreeHGlobal(buffer);}
        }finally{ClosePrinter(printer);}
        return result.ToArray();
    }
}
