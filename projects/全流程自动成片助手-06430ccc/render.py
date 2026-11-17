#!/usr/bin/env python3
"""Use ArcReel's own edit timeline and local render queue to export one episode."""

import argparse
import getpass
import json
from pathlib import Path
import shutil
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


BASE = "http://127.0.0.1:1241"
API = "/api/v1"
TIMELINE_NAME = "一键成片"


class LocalRequests(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class ArcReel:
    def __init__(self):
        self.opener = build_opener(ProxyHandler({}), LocalRequests())
        self.token = None

    def request(self, path, payload=None, content_type="application/json"):
        if not path.startswith(API + "/"):
            raise RuntimeError("ArcReel 返回的下载路径无效。")
        headers = {"Accept-Language": "zh-CN"}
        if payload is not None:
            headers["Content-Type"] = content_type
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        return self.opener.open(Request(BASE + path, payload, headers), timeout=30)

    def json(self, path, body=None):
        payload = None if body is None else json.dumps(body).encode()
        with self.request(API + path, payload) as response:
            return json.load(response)

    def login(self, username):
        if not self.json("/auth/status")["enabled"]:
            return
        password = getpass.getpass("ArcReel 登录密码（输入不会显示）：")
        form = urlencode({"username": username, "password": password}).encode()
        with self.request(API + "/auth/token", form, "application/x-www-form-urlencoded") as response:
            self.token = json.load(response)["access_token"]

    def download(self, path, output):
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=output.parent) as staging:
            temporary = Path(staging) / "render.mp4"
            with self.request(path) as response, temporary.open("wb") as stream:
                if response.headers.get_content_type() != "video/mp4":
                    raise RuntimeError("ArcReel 返回的文件类型不是 MP4。")
                expected_size = response.headers.get("Content-Length")
                shutil.copyfileobj(response, stream)
            size = temporary.stat().st_size
            if not size or (expected_size is not None and size != int(expected_size)):
                raise RuntimeError("MP4 下载不完整，请重新导出。")
            temporary.replace(output)


def render(client, project, episode, timeline_id, timeout):
    project_route = "/projects/" + quote(project, safe="")
    if timeline_id is None:
        summaries = client.json(f"{project_route}/edit-timelines?episode={episode}")["timelines"]
        matching = [item["id"] for item in summaries if item["name"] == TIMELINE_NAME]
        if len(matching) > 1:
            raise RuntimeError("发现多条同名时间线，请在命令中用 --timeline 指定其中一条。")
        if matching:
            timeline_id = matching[0]
        elif len(summaries) == 1:
            timeline_id = summaries[0]["id"]
        elif summaries:
            raise RuntimeError("本集有多条剪辑时间线，请指定要导出的时间线 ID。")
        else:
            created = client.json(
                f"{project_route}/episodes/{episode}/edit-timelines",
                {"from": "script", "name": TIMELINE_NAME},
            )
            timeline_id = created["timeline"]["id"]
    print(f"时间线 {timeline_id}", flush=True)
    timeline_route = f"{project_route}/edit-timelines/{quote(timeline_id, safe='')}"
    timeline = client.json(timeline_route)
    if timeline["timeline"]["episode"] != episode:
        raise RuntimeError("指定的时间线不属于本集。")
    cut_route = timeline_route + "/final-cut"
    status = client.json(cut_route)
    if status["status"] != "current":
        task_id = client.json(cut_route, {"revision": timeline["revision"]})["task_id"]
        print(f"ArcReel 正在渲染，任务 {task_id}", flush=True)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            task = client.json("/tasks/" + quote(task_id, safe=""))["task"]
            if task["status"] == "succeeded":
                break
            if task["status"] in {"failed", "cancelled"}:
                raise RuntimeError(f"渲染任务 {task_id} {task['status']}，请在任务监控查看原因。")
            time.sleep(2)
        else:
            raise RuntimeError(f"等待超时。可在 ArcReel 查看任务 {task_id}，完成后重新导出。")
        status = client.json(cut_route)
    if status["status"] != "current" or not status["download_url"]:
        raise RuntimeError("当前时间线没有可下载的最新成片。")
    return status["download_url"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", help="ArcReel 项目标识，见项目页面地址")
    parser.add_argument("episode", type=int)
    parser.add_argument("output", type=Path)
    parser.add_argument("--timeline", help="指定已有剪辑时间线 ID")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--timeout", type=int, default=1800, help="渲染等待秒数")
    args = parser.parse_args()
    if args.episode < 1 or args.timeout < 1 or args.output.suffix.lower() != ".mp4":
        parser.error("集号和等待秒数必须为正整数，输出文件须以 .mp4 结尾。")
    try:
        client = ArcReel()
        client.login(args.username)
        url = render(client, args.project, args.episode, args.timeline, args.timeout)
        client.download(url, args.output)
    except HTTPError as error:
        sys.exit(f"ArcReel 返回 HTTP {error.code}。请在工作台检查登录、项目、素材和剪辑预检。")
    except URLError:
        sys.exit("无法连接本机 ArcReel。请确认工作台已启动。")
    except (OSError, RuntimeError, ValueError, KeyError) as error:
        sys.exit(str(error))
    print(f"已导出 {args.output.resolve()}")


if __name__ == "__main__":
    main()
