namespace Siyue.Printing;
public static class PrintEvidence
{
    public static Outcome Evaluate(int exitCode,bool observed,uint flags,string jobIds,bool observationFailed)
    {
        var suffix="组件退出码 "+exitCode+(jobIds.Length>0?"；Windows 任务 "+jobIds:"");
        if(exitCode!=0)return new("uncertain","打印组件返回异常；可能已部分提交，禁止自动重打。"+suffix);
        if((flags & (1u|2u|4u|32u|64u|512u))!=0)return new("uncertain","已观察到 Windows 队列异常（状态 "+flags+"），请运行远程诊断。"+suffix);
        if(!observed)return new("uncertain",(observationFailed?"无法读取 Windows 作业证据":"组件正常退出，但未捕获到对应 Windows 打印作业")+"；未确认出纸，请诊断或远程查看。"+suffix);
        return new("submitted","已观察到对应 Windows 打印作业，实际出纸未确认。"+suffix);
    }
}
