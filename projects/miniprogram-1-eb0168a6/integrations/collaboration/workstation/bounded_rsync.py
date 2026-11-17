#!/usr/bin/python3
"""Bound trusted maintenance transfers, including Linux file-cache accounting."""
import os,pathlib,sys

def main():
    if os.geteuid()!=0 or len(sys.argv)<2 or sys.argv[1]!='--server':raise PermissionError('Maintenance rsync server only')
    limits={'memory':{'memory.limit_in_bytes':1024**3,'memory.memsw.limit_in_bytes':2*1024**3},
            'cpu':{'cpu.cfs_period_us':100000,'cpu.cfs_quota_us':100000},'pids':{'pids.max':128}}
    for controller,values in limits.items():
        p=pathlib.Path('/sys/fs/cgroup')/controller/'siyue-maintenance-transfer';p.mkdir(exist_ok=True)
        for key,value in values.items():(p/key).write_text(str(value))
        (p/'cgroup.procs').write_text(str(os.getpid()))
    os.execv('/usr/bin/rsync',['rsync',*sys.argv[1:]])

if __name__=='__main__':main()
