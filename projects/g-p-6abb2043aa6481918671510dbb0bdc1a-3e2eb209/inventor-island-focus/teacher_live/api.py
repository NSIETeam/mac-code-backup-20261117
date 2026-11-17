"""teacher-live 的 HTTP 层：教师授课视图与课堂会话控制的接口与权限矩阵。

本模块不修改 ``server.py``：``TeacherLiveHandler`` 继承 ``server.Handler``，只拦截
teacher-live 自己的路径，其它请求原样交给父类（登录、会话、静态资源、既有 API 全部复用）。

权限矩阵（服务端强制）
----------------------
教师接口 ``/api/identity/teacher/live/*``
  * 需已登录且角色为 teacher / super_admin，且已完成口令设置（must_reset=False）；
  * 写操作需 ``X-CSRF-Token`` 且同源（``protect_identity``）；
  * 只能操作"自己名下的教学班"（``teaching_classes.teacher_id`` 匹配），否则 403。
学生接口 ``/api/identity/student/live``
  * 需已登录且角色为 student；
  * 只返回"自己所在（active 名册）班级"的进行中课堂；
  * 只返回当前段的公开投影：无教师提示、无答案、无教师专属素材、无其它学生数据、无班级名册。
页面 ``/teacher-live.html`` / ``/student-live.html``
  * 服务端按角色重定向，避免直接裸链绕过。
"""
from __future__ import annotations

import json
from collections import OrderedDict
from threading import RLock
from urllib.parse import urlparse, parse_qs

import server

from . import flow as flow_mod
from . import session as session_mod

TEACHER_PREFIX = '/api/identity/teacher/live/'
STUDENT_LIVE = '/api/identity/student/live'
TEACHER_PAGE = '/teacher-live.html'
STUDENT_PAGE = '/student-live.html'
_SNAPSHOT_CACHE = OrderedDict()
_SNAPSHOT_CACHE_LOCK = RLock()


def _version_snapshot(row):
    """Decode immutable snapshots once per process; students poll this route every few seconds."""
    key = (row['id'], row['snapshot_sha256'])
    with _SNAPSHOT_CACHE_LOCK:
        snapshot = _SNAPSHOT_CACHE.get(key)
        if snapshot is not None:
            _SNAPSHOT_CACHE.move_to_end(key)
            return snapshot
    snapshot = json.loads(row['snapshot'])
    with _SNAPSHOT_CACHE_LOCK:
        _SNAPSHOT_CACHE[key] = snapshot
        _SNAPSHOT_CACHE.move_to_end(key)
        while len(_SNAPSHOT_CACHE) > 12:
            _SNAPSHOT_CACHE.popitem(last=False)
    return snapshot


class TeacherLiveHandler(server.Handler):
    """在主服务 Handler 上叠加 teacher-live 路由的处理器。"""

    def do_GET(self):
        path = urlparse(self.path).path
        if path.startswith(TEACHER_PREFIX) or path == STUDENT_LIVE:
            return self._live_get(path)
        if path in (TEACHER_PAGE, STUDENT_PAGE):
            return self._live_page(path)
        return super().do_GET()

    def do_POST(self):
        path = urlparse(self.path).path
        if path.startswith(TEACHER_PREFIX):
            return self._live_post(path)
        return super().do_POST()

    # --- 页面门禁 ---------------------------------------------------------
    def _live_page(self, path):
        try:
            self.guard_host()
            with server.connection() as con:
                ident = self.identity_session(con, False)
                destination = None
                if not ident:
                    destination = '/identity.html'
                elif path == TEACHER_PAGE and (ident['role'] not in ('teacher', 'super_admin') or ident['must_reset']):
                    destination = '/' + {'student': 'student-space.html', 'teacher': 'teacher-space.html',
                                         'guardian': 'guardian-space.html', 'super_admin': 'admin-space.html'}[ident['role']]
                elif path == STUDENT_PAGE and ident['role'] != 'student':
                    destination = '/' + {'student': 'student-space.html', 'teacher': 'teacher-space.html',
                                         'guardian': 'guardian-space.html', 'super_admin': 'admin-space.html'}[ident['role']]
                if destination:
                    self.send_response(303)
                    self.headers_common()
                    self.send_header('Location', destination)
                    self.send_header('Cache-Control', 'no-store')
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
            return super().do_GET()
        except server.RequestError as error:
            return self.send_json({'error': error.message}, error.status)

    # --- GET --------------------------------------------------------------
    def _live_get(self, path):
        try:
            self.guard_host()
            query = parse_qs(urlparse(self.path).query)
            with server.connection() as con:
                if path == STUDENT_LIVE:
                    return self.send_json(self._student_payload(con))
                ident = self._teacher_ident(con)
                if path == TEACHER_PREFIX + 'classes':
                    return self.send_json(self._classes_payload(con, ident))
                if path == TEACHER_PREFIX + 'preview':
                    return self.send_json(self._teacher_payload(
                        con, ident, query.get('classId', [''])[0], query.get('lessonId', [''])[0],
                        query.get('versionId', [''])[0]))
                if path == TEACHER_PREFIX + 'session':
                    return self.send_json(self._teacher_payload(con, ident, query.get('classId', [''])[0],
                        query.get('lessonId', [''])[0], query.get('versionId', [''])[0]))
                raise server.RequestError(404, '接口不存在。')
        except server.RequestError as error:
            return self.send_json({'error': error.message}, error.status)

    # --- POST -------------------------------------------------------------
    def _live_post(self, path):
        self.cookie_to_set = None
        self.clear_cookie = False
        self.identity_cookie_to_set = None
        self.clear_identity_cookie = False
        guard = None
        try:
            self.guard_host()
            data = self.read_body()
            # 与主服务一致：离线恢复进行中不接受写入。
            guard = server.platform_operations.service_write_guard(server.DATA)
            if guard is None:
                raise server.RequestError(503, '平台正在维护（离线恢复进行中），写入暂不可用。')
            with server.connection() as con:
                ident = self._teacher_ident(con)
                self.protect_identity(ident)
                if path == TEACHER_PREFIX + 'open':
                    return self.send_json_after_commit(con, self._open(con, ident, data))
                if path == TEACHER_PREFIX + 'advance':
                    return self.send_json_after_commit(con, self._control(con, ident, data, index_key='index'))
                if path == TEACHER_PREFIX + 'pause':
                    return self.send_json_after_commit(con, self._control(con, ident, data, paused=True))
                if path == TEACHER_PREFIX + 'end':
                    return self.send_json_after_commit(con, self._end(con, ident, data))
                raise server.RequestError(404, '接口不存在。')
        except server.RequestError as error:
            return self.send_json({'error': error.message}, error.status)
        finally:
            server.platform_operations.release_guard(guard)

    # --- 权限与载荷 -------------------------------------------------------
    def _teacher_ident(self, con):
        ident = self.identity_session(con)
        if ident['must_reset']:
            raise server.RequestError(428, '请先修改临时口令。')
        if ident['role'] not in ('teacher', 'super_admin'):
            raise server.RequestError(403, '只有教师可以使用授课工作台。')
        return ident

    def _classes_payload(self, con, ident):
        classes = session_mod.teacher_classes(con, ident['principal_id'])
        for klass in classes:
            klass['courses'] = self._class_courses(con, ident, klass['classId'])
        return {'classes': classes, 'history': session_mod.teacher_history(con, ident['principal_id'])}

    def _class_courses(self, con, ident, class_id):
        """Only show frozen annual lessons on an active version granted to this exact class."""
        rows = con.execute('''SELECT v.id,v.label,v.band_id,v.snapshot_sha256,v.snapshot
            FROM class_course_grants g JOIN course_versions v ON v.id=g.version_id
            JOIN teaching_classes c ON c.id=g.class_id
            WHERE g.class_id=? AND g.active=1 AND c.teacher_id=? AND c.archived=0
              AND v.state IN ('published','preview') ORDER BY v.created,v.id''',
            (class_id, ident['principal_id']))
        courses = []
        for row in rows:
            try:
                snapshot = _version_snapshot(row)
            except (TypeError, ValueError):
                continue
            if snapshot.get('runtime') != 'annual-y1':
                continue
            deliveries = snapshot.get('annualTeacherDeliveries') or {}
            weeks = []
            for lesson in snapshot.get('lessons', []):
                if not isinstance(lesson, dict):
                    continue
                unit_id = lesson.get('unitId')
                delivery = deliveries.get(unit_id) if isinstance(unit_id, str) else None
                pack = delivery.get('teacherPack') if isinstance(delivery, dict) else None
                if (not isinstance(pack, dict) or delivery.get('state') != 'available' or
                        snapshot.get('bandId') != row['band_id'] or lesson.get('bandId') != row['band_id'] or
                        lesson.get('semester') != snapshot.get('semester') or
                        type(lesson.get('week')) is not int or not 1 <= lesson['week'] <= 18 or
                        lesson.get('state') == 'unavailable'):
                    continue
                weeks.append({'week': lesson.get('week'), 'lessonId': unit_id,
                              'title': lesson.get('title') or unit_id})
            weeks.sort(key=lambda item: (item['week'] if isinstance(item['week'], int) else 0, item['lessonId']))
            courses.append({'versionId': row['id'], 'label': row['label'],
                            'snapshotSha256': row['snapshot_sha256'], 'bandId': row['band_id'],
                            'semester': snapshot.get('semester'), 'weeks': weeks})
        return courses

    def _require_version_lesson(self, con, ident, class_id, version_id, lesson_id):
        klass = self._require_class(con, ident, class_id)
        row = con.execute('''SELECT v.* FROM course_versions v JOIN class_course_grants g ON g.version_id=v.id
            JOIN teaching_classes c ON c.id=g.class_id WHERE v.id=? AND g.class_id=? AND g.active=1
            AND c.teacher_id=? AND c.archived=0 AND v.state IN ('published','preview')''',
            (version_id, class_id, ident['principal_id'])).fetchone()
        if not row:
            raise server.RequestError(403, '该课程版本未获配给这个班级，或已撤回/退役。')
        snapshot = _version_snapshot(row)
        if (snapshot.get('runtime') != 'annual-y1' or snapshot.get('bandId') != row['band_id'] or
                row['band_id'] != klass['course_band'] or type(snapshot.get('semester')) is not int or
                snapshot.get('semester') not in (1, 2)):
            raise server.RequestError(409, '该版本不是本班年龄段的年度课程。')
        lesson = next((item for item in snapshot.get('lessons', [])
                       if isinstance(item, dict) and item.get('unitId') == lesson_id), None)
        delivery = (snapshot.get('annualTeacherDeliveries') or {}).get(lesson_id)
        pack = delivery.get('teacherPack') if isinstance(delivery, dict) else None
        if (not lesson or lesson.get('bandId') != klass['course_band'] or
                lesson.get('semester') != snapshot.get('semester') or type(lesson.get('week')) is not int or
                not 1 <= lesson['week'] <= 18 or
                not isinstance(pack, dict) or delivery.get('state') != 'available' or
                lesson.get('state') == 'unavailable'):
            raise server.RequestError(404, '该版本中没有可授课的冻结课件。')
        frozen_pack = dict(pack)
        frozen_pack['bandId'] = row['band_id']
        frozen_pack['title'] = lesson.get('title') or frozen_pack.get('title') or lesson_id
        return klass, dict(row), lesson, frozen_pack

    def _require_class(self, con, ident, class_id):
        klass = session_mod.teacher_class(con, ident['principal_id'], class_id)
        if not klass:
            raise server.RequestError(403, '该班级不在你的教学班内。')
        return klass

    def _require_lesson(self, band_id, lesson_id):
        weeks = flow_mod.lesson_catalog(server.ROOT).get(band_id, [])
        if not any(row['lessonId'] == lesson_id for row in weeks):
            raise server.RequestError(400, '该课程不在本班本学期的课程表内。')
        return lesson_id

    def _teacher_payload(self, con, ident, class_id, lesson_id='', version_id=''):
        klass = self._require_class(con, ident, class_id)
        live = session_mod.live_session(con, class_id)
        if live and (not live.get('course_version_id') or live.get('teacher_id') != ident['principal_id']):
            courses = self._class_courses(con, ident, class_id)
            message = ('这是旧版未绑定课程的课堂记录；选择已获配学期并重新开课后会替换当前记录。'
                       if not live.get('course_version_id') else
                       '此课堂由原任课教师开启；请选择当前获配课程并重新开课。')
            return {'class': {'classId': klass['id'], 'name': klass['name'], 'bandId': klass['course_band']},
                    'courses': courses, 'versionId': courses[0]['versionId'] if courses else '',
                    'versionLabel': None, 'weeks': courses[0]['weeks'] if courses else [],
                    'session': None, 'history': session_mod.teacher_history(con, ident['principal_id']),
                    'flow': None, 'current': None,
                    'message': message}
        selected_version = version_id or (live['course_version_id'] if live else '')
        lesson = lesson_id or (live['lesson_id'] if live else '')
        courses = self._class_courses(con, ident, class_id)
        course = next((item for item in courses if item['versionId'] == selected_version), None)
        if not selected_version and courses:
            course = courses[0]; selected_version = course['versionId']
        if course is None and selected_version:
            if live and live.get('course_version_id') == selected_version:
                return {'class': {'classId': klass['id'], 'name': klass['name'], 'bandId': klass['course_band']},
                        'courses': courses, 'versionId': courses[0]['versionId'] if courses else '',
                        'versionLabel': None, 'weeks': courses[0]['weeks'] if courses else [],
                        'session': None, 'history': session_mod.teacher_history(con, ident['principal_id']),
                        'flow': None, 'current': None,
                        'message': '当前课堂课程版本已撤回或退役，请选择一个仍获配的学期版本重新开课。'}
            raise server.RequestError(403, '该课程版本当前未分配给此班级。')
        weeks = course['weeks'] if course else []
        payload = {
            'class': {'classId': klass['id'], 'name': klass['name'], 'bandId': klass['course_band']},
            'courses': courses, 'versionId': selected_version, 'versionLabel': course['label'] if course else None,
            'weeks': weeks, 'session': session_mod.session_state(live),
            'history': session_mod.teacher_history(con, ident['principal_id']),
            'flow': None, 'current': None,
        }
        if not lesson:
            return payload
        _, _, lesson_row, frozen_pack = self._require_version_lesson(con, ident, class_id, selected_version, lesson)
        try:
            built = flow_mod.build_flow_from_teacher_pack(server.ROOT, lesson, frozen_pack, 'teacher')
        except flow_mod.FlowError as error:
            raise server.RequestError(error.status, error.message)
        index = (live['step_index'] if live and live['lesson_id'] == lesson
                 and live.get('course_version_id') == selected_version else 0)
        index = min(max(index, 0), len(built['steps']) - 1)
        payload['flow'] = built
        payload['current'] = self._teacher_current(built, lesson, index, frozen_pack)
        payload['week'] = lesson_row.get('week')
        return payload

    def _teacher_current(self, built, lesson_id, index, frozen_pack):
        step = built['steps'][index]
        assets = frozen_pack.get('assets') or {}
        return {
            'index': index, 'stepCount': len(built['steps']),
            'step': step, 'materials': flow_mod.step_materials(step, assets),
        }

    def _student_payload(self, con):
        ident = self.identity_session(con)
        if ident['must_reset']:
            raise server.RequestError(428, '请先修改临时口令。')
        if ident['role'] != 'student':
            raise server.RequestError(403, '只有学生账号可以查看课堂任务。')
        child = session_mod.student_child(con, ident['principal_id'])
        if not child:
            raise server.RequestError(403, '孩子账号尚未绑定学习者档案。')
        found = session_mod.student_live(con, child['id'])
        klass = {'name': found['class']['name']} if found and found['class'] else None
        if not found:
            return {'class': klass, 'session': None, 'task': None,
                    'message': '老师还没有开始课堂。'}
        live = found['session']
        state = session_mod.session_state(live)
        version = con.execute("SELECT id,snapshot,snapshot_sha256,label FROM course_versions WHERE id=? AND state IN ('published','preview')",
                              (live['course_version_id'],)).fetchone()
        if not version:
            return {'class': None, 'session': None, 'task': None, 'message': '老师当前课程版本暂不可用。'}
        snapshot = _version_snapshot(version)
        delivery = (snapshot.get('annualTeacherDeliveries') or {}).get(live['lesson_id'])
        pack = delivery.get('teacherPack') if isinstance(delivery, dict) and delivery.get('state') == 'available' else None
        lesson = next((item for item in snapshot.get('lessons', []) if isinstance(item, dict) and
                       item.get('unitId') == live['lesson_id']), None)
        if not isinstance(pack, dict) or not lesson:
            return {'class': None, 'session': None, 'task': None, 'message': '老师当前课件版本不完整。'}
        pack = dict(pack); pack['bandId'] = snapshot.get('bandId'); pack['title'] = lesson.get('title') or live['lesson_id']
        try:
            built = flow_mod.build_flow_from_teacher_pack(server.ROOT, live['lesson_id'], pack, 'student')
        except flow_mod.FlowError as error:
            raise server.RequestError(error.status, error.message)
        index = min(max(live['step_index'], 0), len(built['steps']) - 1)
        step = built['steps'][index]
        task = None
        message = '老师正在讲解，请等待下一步任务。'
        public_materials = []
        if step['public']:
            assets = pack.get('assets') or {}
            public_materials = [row for row in flow_mod.step_materials(step, assets) if not row['teacherOnly']]
        if step['public'] and (step['studentTask'] or step['teachingPoints'] or public_materials):
            task = {
                'index': index, 'stepCount': len(built['steps']),
                'title': step['title'], 'phase': step['phase'], 'phaseLabel': step['phaseLabel'],
                'window': step['window'], 'studentTask': step['studentTask'],
                'teachingPoints': step['teachingPoints'],
                'isExercise': step['isExercise'], 'materials': public_materials,
            }
            message = None
        return {'class': klass, 'session': state, 'courseVersionLabel': version['label'], 'task': task, 'message': message}

    # --- 写操作 -----------------------------------------------------------
    def _open(self, con, ident, data):
        class_id = data.get('classId')
        lesson_id = data.get('lessonId')
        version_id = data.get('versionId')
        if not isinstance(class_id, str) or not isinstance(lesson_id, str) or not isinstance(version_id, str):
            raise server.RequestError(400, '请选择班级、课程版本与本周课程。')
        klass, _, lesson_row, frozen_pack = self._require_version_lesson(con, ident, class_id, version_id, lesson_id)
        try:
            built = flow_mod.build_flow_from_teacher_pack(server.ROOT, lesson_id, frozen_pack, 'teacher')
        except flow_mod.FlowError as error:
            raise server.RequestError(error.status, error.message)
        row = session_mod.open_session(
            con, ident['principal_id'], class_id, lesson_id, klass['course_band'],
            built['lessonTitle'], len(built['steps']), server.now(), version_id=version_id)
        return self._teacher_payload_after(con, ident, class_id, version_id, built, frozen_pack, row)

    def _control(self, con, ident, data, index_key=None, paused=False):
        class_id = data.get('classId')
        if not isinstance(class_id, str):
            raise server.RequestError(400, '请选择班级。')
        self._require_class(con, ident, class_id)
        index = data.get(index_key) if index_key else None
        delta = data.get('delta')
        paused_value = data.get('paused') if paused else None
        try:
            row = session_mod.control(con, ident['principal_id'], class_id, server.now(),
                                      index=index, delta=delta, paused=paused_value)
        except session_mod.SessionError as error:
            raise server.RequestError(error.status, error.message)
        _, _, _, frozen_pack = self._require_version_lesson(con, ident, class_id, row['course_version_id'], row['lesson_id'])
        return self._teacher_payload_after(con, ident, class_id, row['course_version_id'], None, frozen_pack, row)

    def _end(self, con, ident, data):
        class_id = data.get('classId')
        if not isinstance(class_id, str):
            raise server.RequestError(400, '请选择班级。')
        self._require_class(con, ident, class_id)
        try:
            row = session_mod.end_session(con, ident['principal_id'], class_id, server.now())
        except session_mod.SessionError as error:
            raise server.RequestError(error.status, error.message)
        return {'session': session_mod.session_state(row),
                'history': session_mod.teacher_history(con, ident['principal_id'])}

    def _teacher_payload_after(self, con, ident, class_id, version_id, built, frozen_pack, row):
        """控制/开课后的返回：始终跟随持久化后的会话状态重建当前段。"""
        lesson = row['lesson_id']
        if built is None:
            try:
                built = flow_mod.build_flow_from_teacher_pack(server.ROOT, lesson, frozen_pack, 'teacher')
            except flow_mod.FlowError as error:
                raise server.RequestError(error.status, error.message)
        index = min(max(row['step_index'], 0), len(built['steps']) - 1)
        klass = session_mod.teacher_class(con, ident['principal_id'], class_id)
        return {
            'class': {'classId': klass['id'], 'name': klass['name'], 'bandId': klass['course_band']},
            'session': session_mod.session_state(row),
            'flow': built,
            'current': self._teacher_current(built, lesson, index, frozen_pack),
            'versionId': version_id,
            'history': session_mod.teacher_history(con, ident['principal_id']),
        }
