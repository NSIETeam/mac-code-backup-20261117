"""课堂会话（teacher-live session）的持久化状态与授权。

只负责"教师授课视图 + 课堂会话控制"的最小状态：教师选了哪个班/哪节课、当前停在第几段、
是否暂停、是否已结束。状态落在主库自己的表里（模块自带 schema，不改 server.py）。
学生端只通过 ``student_live`` 读取"自己所在班级的进行中课堂"，且只拿得到当前段的公开投影。
"""
from __future__ import annotations

import secrets

SCHEMA = '''
CREATE TABLE IF NOT EXISTS teacher_live_sessions(
 id TEXT PRIMARY KEY,
 class_id TEXT NOT NULL REFERENCES teaching_classes(id) ON DELETE CASCADE,
 teacher_id TEXT NOT NULL REFERENCES principals(id) ON DELETE CASCADE,
 course_version_id TEXT REFERENCES course_versions(id) ON DELETE RESTRICT,
 lesson_id TEXT NOT NULL,
 band_id TEXT NOT NULL,
 lesson_title TEXT NOT NULL,
 step_count INTEGER NOT NULL,
 step_index INTEGER NOT NULL DEFAULT 0,
 paused INTEGER NOT NULL DEFAULT 0 CHECK(paused IN (0,1)),
 status TEXT NOT NULL DEFAULT 'live' CHECK(status IN ('live','ended')),
 revision INTEGER NOT NULL DEFAULT 1,
 created INTEGER NOT NULL,
 updated INTEGER NOT NULL,
 ended INTEGER
);
CREATE UNIQUE INDEX IF NOT EXISTS teacher_live_one_live_per_class
 ON teacher_live_sessions(class_id) WHERE status='live';
CREATE INDEX IF NOT EXISTS teacher_live_class_status
 ON teacher_live_sessions(class_id,status,updated DESC);
CREATE INDEX IF NOT EXISTS teacher_live_teacher_status
 ON teacher_live_sessions(teacher_id,status,updated DESC);
'''

_LIVE_COLUMNS = ('id','class_id','teacher_id','lesson_id','band_id','lesson_title',
                 'course_version_id','step_count','step_index','paused','status','revision','created','updated','ended')


class SessionError(ValueError):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


def ensure_schema(con):
    con.executescript(SCHEMA)
    columns = {row['name'] for row in con.execute('PRAGMA table_info(teacher_live_sessions)')}
    if 'course_version_id' not in columns:
        con.execute('ALTER TABLE teacher_live_sessions ADD COLUMN course_version_id TEXT REFERENCES course_versions(id) ON DELETE RESTRICT')


def _row(row):
    return {key: row[key] for key in _LIVE_COLUMNS} if row else None


def session_state(row):
    """对外的会话状态（教师与学生共用的稳定结构）。"""
    if not row:
        return None
    return {
        'sessionId': row['id'], 'classId': row['class_id'], 'lessonId': row['lesson_id'],
        'courseVersionId': row['course_version_id'],
        'bandId': row['band_id'], 'lessonTitle': row['lesson_title'],
        'stepIndex': row['step_index'], 'stepCount': row['step_count'],
        'paused': bool(row['paused']), 'status': row['status'], 'revision': row['revision'],
        'updatedAt': row['updated'], 'endedAt': row['ended'],
    }


def teacher_class(con, teacher_id, class_id):
    """教师自己的教学班（archived=0）。非本班返回 None，由调用方映射为 403。"""
    if not isinstance(class_id, str) or not class_id:
        return None
    return con.execute(
        'SELECT id,name,course_band FROM teaching_classes WHERE id=? AND teacher_id=? AND archived=0',
        (class_id, teacher_id)).fetchone()


def teacher_classes(con, teacher_id):
    return [{'classId': row['id'], 'name': row['name'], 'bandId': row['course_band']}
            for row in con.execute(
                'SELECT id,name,course_band FROM teaching_classes WHERE teacher_id=? AND archived=0 ORDER BY created',
                (teacher_id,))]


def live_session(con, class_id):
    return _row(con.execute(
        'SELECT * FROM teacher_live_sessions WHERE class_id=? AND status=\'live\'', (class_id,)).fetchone())


def session_by_id(con, session_id):
    return _row(con.execute('SELECT * FROM teacher_live_sessions WHERE id=?', (session_id,)).fetchone())


def teacher_history(con, teacher_id, limit=20):
    rows = con.execute(
        'SELECT * FROM teacher_live_sessions WHERE teacher_id=? ORDER BY updated DESC LIMIT ?',
        (teacher_id, max(1, min(int(limit), 100))))
    return [session_state(_row(row)) for row in rows]


def open_session(con, teacher_id, class_id, lesson_id, band_id, lesson_title, step_count, ts, version_id=None):
    """开课/换课：同一班级只保留一个进行中的课堂；换课即在同一行上重置到第 0 段并落库。"""
    step_count = max(1, int(step_count))
    existing = live_session(con, class_id)
    if existing:
        con.execute(
            '''UPDATE teacher_live_sessions SET teacher_id=?,course_version_id=?,lesson_id=?,band_id=?,lesson_title=?,step_count=?,
                   step_index=0,paused=0,revision=revision+1,updated=? WHERE id=?''',
            (teacher_id, version_id, lesson_id, band_id, lesson_title, step_count, ts, existing['id']))
        return _row(con.execute('SELECT * FROM teacher_live_sessions WHERE id=?', (existing['id'],)).fetchone())
    session_id = 'tl-' + secrets.token_urlsafe(12)
    con.execute(
        '''INSERT INTO teacher_live_sessions
               (id,class_id,teacher_id,course_version_id,lesson_id,band_id,lesson_title,step_count,step_index,
                paused,status,revision,created,updated,ended)
           VALUES(?,?,?,?,?,?,?,?,0,0,'live',1,?,?,NULL)''',
        (session_id, class_id, teacher_id, version_id, lesson_id, band_id, lesson_title, step_count, ts, ts))
    return session_by_id(con, session_id)


def control(con, teacher_id, class_id, ts, index=None, delta=None, paused=None):
    """推进/回退当前段，或暂停/继续。只有本班教师可控制；结束后不再接受控制。"""
    row = live_session(con, class_id)
    if not row or row['teacher_id'] != teacher_id:
        raise SessionError(404, '该班级当前没有进行中的课堂，请先开课。')
    index_before = row['step_index']
    if index is not None:
        if not isinstance(index, int):
            raise SessionError(400, '段落序号必须是整数。')
        target = index
    elif delta:
        if not isinstance(delta, int):
            raise SessionError(400, '步进必须是整数。')
        target = index_before + delta
    else:
        target = index_before
    if not 0 <= target <= row['step_count'] - 1:
        raise SessionError(409, '已到课堂段落边界。')
    paused_value = row['paused']
    if paused is not None:
        if not isinstance(paused, bool):
            raise SessionError(400, '暂停状态必须是布尔值。')
        paused_value = 1 if paused else 0
    changed = (target != index_before) or (paused_value != row['paused'])
    if changed:
        con.execute(
            'UPDATE teacher_live_sessions SET step_index=?,paused=?,revision=revision+1,updated=? WHERE id=?',
            (target, paused_value, ts, row['id']))
    return _row(con.execute('SELECT * FROM teacher_live_sessions WHERE id=?', (row['id'],)).fetchone())


def end_session(con, teacher_id, class_id, ts):
    """结束课堂：状态与结束时间落库（幂等：已结束则原样返回）。"""
    row = live_session(con, class_id)
    if not row:
        ended = con.execute(
            'SELECT * FROM teacher_live_sessions WHERE class_id=? AND teacher_id=? ORDER BY updated DESC LIMIT 1',
            (class_id, teacher_id)).fetchone()
        if not ended:
            raise SessionError(404, '该班级没有可结束的课堂。')
        return _row(ended)
    if row['teacher_id'] != teacher_id:
        raise SessionError(403, '只能结束自己班级的课堂。')
    con.execute(
        "UPDATE teacher_live_sessions SET status='ended',paused=1,ended=?,revision=revision+1,updated=? WHERE id=?",
        (ts, ts, row['id']))
    return _row(con.execute('SELECT * FROM teacher_live_sessions WHERE id=?', (row['id'],)).fetchone())


def student_child(con, principal_id):
    return con.execute(
        'SELECT c.id,c.family_id,c.name FROM student_principals sp JOIN children c ON c.id=sp.child_id '
        'WHERE sp.principal_id=?', (principal_id,)).fetchone()


def student_live(con, child_id):
    """学生能看到的课堂：自己所在（且未撤销）的班级里，最近一次进行中的课堂。"""
    row = con.execute(
        '''SELECT s.* FROM teacher_live_sessions s
             JOIN class_enrollments e ON e.class_id=s.class_id AND e.status='active'
             JOIN teaching_classes c ON c.id=s.class_id AND c.archived=0 AND c.teacher_id=s.teacher_id AND c.course_band=s.band_id
             JOIN principals p ON p.id=c.teacher_id AND p.role='teacher' AND p.disabled=0
             JOIN class_course_grants g ON g.class_id=s.class_id AND g.version_id=s.course_version_id AND g.active=1
             JOIN course_versions v ON v.id=s.course_version_id AND v.state IN ('published','preview')
            WHERE e.child_id=? AND s.status='live'
            ORDER BY s.updated DESC LIMIT 1''', (child_id,)).fetchone()
    if not row:
        return None
    klass = con.execute('SELECT id,name,course_band FROM teaching_classes WHERE id=?', (row['class_id'],)).fetchone()
    return {'session': _row(row), 'class': dict(klass) if klass else None}
