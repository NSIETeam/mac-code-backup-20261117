#!/usr/bin/env python3
"""Siyue dictionary read client. Python 3.9+, standard library only."""
import argparse
import getpass
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

BASE_URL = 'https://www.siyuejn.com/work'
DEFAULT_SESSION = Path.home() / '.config' / 'siyue-dictionary' / 'session.json'
TOKEN = re.compile(r'^[A-Za-z0-9_-]{48}$')

class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward an employee credential to another origin or route.
        raise ApiError(code, '服务发生重定向，已停止；请核实接口地址。')

class Client:
    def __init__(self, session_file=None, token=None):
        self.session_file = Path(session_file) if session_file else DEFAULT_SESSION
        self.token = token or os.environ.get('SIYUE_DICTIONARY_SESSION')
        self.opener = urllib.request.build_opener(NoRedirect())

    def _token(self):
        token = self.token
        if token is None:
            try:
                if self.session_file.is_symlink():
                    raise ApiError(0, '会话文件不能是符号链接。')
                mode = self.session_file.stat().st_mode
                if os.name == 'posix' and stat.S_IMODE(mode) & 0o077:
                    raise ApiError(0, '会话文件权限过宽，请设置为仅当前用户可读写（0600）。')
                state = json.loads(self.session_file.read_text(encoding='utf-8'))
                if state.get('origin') != BASE_URL:
                    raise ApiError(0, '会话绑定的服务地址不匹配。')
                token = state.get('token')
            except FileNotFoundError:
                raise ApiError(401, '请由用户先运行 login 完成授权；不要把密码发到聊天里。') from None
            except (OSError, ValueError, TypeError):
                raise ApiError(0, '无法读取本地会话文件。') from None
        if not isinstance(token, str) or not TOKEN.fullmatch(token):
            raise ApiError(401, '会话无效，请由用户重新运行 login。')
        return token

    def _request(self, path, body=None, authenticated=True):
        if not path.startswith('/api/') or '\\' in path or '\r' in path or '\n' in path:
            raise ApiError(0, '接口路径无效。')
        headers = {'Accept': 'application/json', 'User-Agent': 'SiyueDictionaryReadClient/1.0'}
        if authenticated:
            headers['Cookie'] = 'siyue_work_session=' + self._token()
        payload = None
        if body is not None:
            payload = json.dumps(body, ensure_ascii=False).encode('utf-8')
            headers.update({'Content-Type': 'application/json', 'Origin': 'https://www.siyuejn.com'})
        request = urllib.request.Request(BASE_URL + path, data=payload, headers=headers)
        try:
            with self.opener.open(request, timeout=15) as response:
                data = response.read(2 * 1024 * 1024 + 1)
                if len(data) > 2 * 1024 * 1024:
                    raise ApiError(0, '响应过大，请缩小查询范围。')
                return json.loads(data), response.headers
        except urllib.error.HTTPError as error:
            try:
                message = json.loads(error.read(8192)).get('error', '接口请求失败。')
            except (ValueError, TypeError, AttributeError):
                message = '接口请求失败。'
            raise ApiError(error.code, message) from None
        except (urllib.error.URLError, TimeoutError):
            raise ApiError(0, '无法连接思跃词典，请检查网络后重试。') from None
        except (ValueError, UnicodeError):
            raise ApiError(0, '服务返回的内容不是有效JSON。') from None

    def me(self):
        return self._request('/api/agent/v1/me')[0]

    def search(self, query, limit=5, offset=0):
        if not isinstance(query, str) or not query.strip() or len(query) > 200:
            raise ApiError(400, 'query必须为1至200字。')
        if type(limit) is not int or not 1 <= limit <= 20 or type(offset) is not int or not 0 <= offset <= 10000:
            raise ApiError(400, 'limit应为1至20，offset应为0至10000。')
        params = urllib.parse.urlencode({'q': query, 'limit': limit, 'offset': offset})
        return self._request('/api/agent/v1/search?' + params)[0]

    def read(self, key):
        match = re.fullmatch(r'(role|policy|process|document):([A-Za-z0-9_-]{1,100})', key)
        if not match:
            raise ApiError(400, 'key须直接使用搜索结果的key字段。')
        return self._request('/api/agent/v1/entries/' + match[1] + '/' + match[2])[0]

    def login(self, name, password):
        _, headers = self._request('/api/account/login', {'name': name, 'password': password}, authenticated=False)
        from http.cookies import SimpleCookie
        cookies = SimpleCookie()
        cookies.load(headers.get('Set-Cookie', ''))
        token = cookies['siyue_work_session'].value if 'siyue_work_session' in cookies else ''
        if not TOKEN.fullmatch(token):
            raise ApiError(0, '登录响应未提供有效会话。')
        parent = self.session_file.parent
        parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.session_file.is_symlink() or parent.is_symlink():
            raise ApiError(0, '会话存储路径不能是符号链接。')
        if os.name == 'posix' and stat.S_IMODE(parent.stat().st_mode) & 0o077:
            raise ApiError(0, '会话目录须设为仅当前用户可访问（0700）。')
        fd, temporary = tempfile.mkstemp(prefix='.session-', dir=parent)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as file:
                json.dump({'origin': BASE_URL, 'token': token}, file)
            os.replace(temporary, self.session_file)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        self.token = token
        return self.me()

    def logout(self):
        result = self._request('/api/account/logout', {})[0]
        self.token = None
        if self.session_file.exists() and not self.session_file.is_symlink():
            self.session_file.unlink()
        return result

def main():
    parser = argparse.ArgumentParser(description='思跃词典：用户授权后，agent只读查询。')
    parser.add_argument('--session-file', help='会话文件位置；不要传密码或Cookie值')
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('login', help='由用户交互输入账号密码，密码不回显、不保存')
    commands.add_parser('me', help='检查授权和资料范围')
    search = commands.add_parser('search', help='查询资料')
    search.add_argument('query');search.add_argument('--limit', type=int, default=5);search.add_argument('--offset', type=int, default=0)
    read = commands.add_parser('read', help='读取搜索结果key对应的原文和依据');read.add_argument('key')
    commands.add_parser('logout', help='撤销此会话并删除本地会话文件')
    args = parser.parse_args()
    client = Client(args.session_file)
    try:
        if args.command == 'login':
            if not sys.stdin.isatty():
                raise ApiError(0, 'login须由用户在交互终端运行，不能通过管道传入密码。')
            result = client.login(input('员工姓名：').strip(), getpass.getpass('工作密码（不显示、不保存）：'))
        elif args.command == 'search':
            result = client.search(args.query, args.limit, args.offset)
        elif args.command == 'read':
            result = client.read(args.key)
        else:
            result = getattr(client, args.command)()
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (ApiError, OSError, EOFError) as error:
        message = str(error) if isinstance(error, ApiError) else '本地授权或文件操作未完成。'
        print(json.dumps({'error': message, 'status': getattr(error, 'status', 0)}, ensure_ascii=False), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    return 0

if __name__ == '__main__':
    sys.exit(main())
