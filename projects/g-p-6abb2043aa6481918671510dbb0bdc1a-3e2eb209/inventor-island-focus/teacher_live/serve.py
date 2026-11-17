"""隔离运行入口：把 teacher-live 挂在本地临时端口上，用于香港隔离目录的浏览器/长回归。

    python3 -m teacher_live.serve --port 0 --data-dir /tmp/tl-isolated

它复用主服务的一切（登录、会话、Cookie、静态资源、既有 API），只额外接上 teacher-live
的页面与接口；不启动、不修改生产服务，也不写生产 data 目录（除非显式指定）。
"""
from __future__ import annotations

import argparse
import os
import sys
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import server  # noqa: E402
from teacher_live.api import TeacherLiveHandler  # noqa: E402
from teacher_live import session as session_mod  # noqa: E402


def initialize(data_dir=None):
    """初始化隔离库并建好 teacher-live 的表（模块自带 schema，不改 server.py）。"""
    if data_dir is not None:
        server.DATA = Path(data_dir)
        server.DB = server.DATA / 'island.sqlite3'
    server.initialize()
    with server.connection() as con:
        session_mod.ensure_schema(con)
    return server.DB


def build(host='127.0.0.1', port=0, allowed_hosts=None, verbose=False):
    http = server.Server((host, port), TeacherLiveHandler)
    http.allowed_hosts = set(allowed_hosts or {'localhost', '127.0.0.1'})
    http.verbose = verbose
    return http


def main(argv=None):
    parser = argparse.ArgumentParser(description='创想岛 · 教师真实上课工作台（隔离实例）')
    parser.add_argument('--host', default=os.environ.get('ISLAND_TEACHER_LIVE_HOST', '127.0.0.1'))
    parser.add_argument('--port', type=int, default=int(os.environ.get('ISLAND_TEACHER_LIVE_PORT', '8766')))
    parser.add_argument('--data-dir', default=os.environ.get('ISLAND_DATA_DIR', str(ROOT / 'data')))
    parser.add_argument('--allowed-hosts', default='localhost,127.0.0.1')
    parser.add_argument('--verbose', action='store_true')
    args = parser.parse_args(argv)

    db = initialize(args.data_dir)
    http = build(args.host, args.port, args.allowed_hosts.split(','), args.verbose)
    print(f'\n  创想岛 · 教师授课工作台（teacher-live）\n'
          f'  教师工作台：http://{args.host}:{http.server_port}/teacher-live.html\n'
          f'  学生课堂任务：http://{args.host}:{http.server_port}/student-live.html\n'
          f'  数据库：{db}\n  Ctrl+C 停止。\n')
    try:
        http.serve_forever()
    except KeyboardInterrupt:
        print('\n已停止。')
    finally:
        http.server_close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
