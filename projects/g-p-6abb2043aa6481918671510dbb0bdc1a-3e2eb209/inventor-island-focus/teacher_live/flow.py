"""把原生课程包转成可逐段授课的课堂流程（只读课程包，不重造课件内容）。

数据来源与依据
--------------
* 课程包原件：``backend.annual_pack.read(root, lesson_id, audience)`` —— 平台已有的课程包
  读取与受众隔离路径。教师受众含教师提示/答案/教师专属素材；学生受众已剔除这些字段。
* 逐段结构：课程包里的 ``plan``（``slides-data.json`` 的 ``id/title/kind/medium/segment``），
  或（无 plan 时）课件分页 ``slides.items``。plan 条目与课件分页按"课号出现在标题或素材路径里"
  对齐（reveal 课程包的常见形态：``slide-4`` 标题含 ``W01-A1``、素材为 ``assets/task-W01-A1.svg``）。
* 50 分钟节奏：作者侧 ``authored-content.json.goalSegments``（若存在，形如
  ``{"A":[["15–27","正例…","A1","三预测都对"],…]}``）；无作者节奏时用课件自带的
  ``estimatedMinutes`` 累加得到时段；两者都没有时退回文档化模型
  ``docs/curriculum/DELIVERY_MODEL_50_50.md`` 的 A/B 段建议分钟表。

本模块只做"读取 + 归类 + 投影"，绝不新增/改写教学目标、例题或答案。
"""
from __future__ import annotations

import json
import re
from functools import lru_cache
from pathlib import Path

from backend import annual_pack

# --- 50 分钟交付模型：docs/curriculum/DELIVERY_MODEL_50_50.md 的建议节奏（文档化模型）---
# 仅在课程包既无作者节奏、也无逐页 estimatedMinutes 时用于标注节奏，不臆造具体分钟。
A_MODEL_WINDOWS = {
    '导入': '0–5', '目标': '5–13', '示范': '13–25',
    '对照': '25–35', '共做': '35–45', '独立': '45–50',
}
B_MODEL_WINDOWS = {
    '例题': '0–12', '示范': '0–12', '导入': '0–12', '目标': '0–12',
    '半完成': '12–20', '共做': '12–20',
    '练习': '20–36', '对照': '20–36',
    '个人检查': '36–38',
    '迁移': '38–46', '独立': '38–46',
    '反馈': '46–50', '进阶': '（机动）',
}
SEGMENT_LABELS = {
    'A': 'A 段 · 课堂授课（50 分钟）',
    'B': 'B 段 · 例题与习题（50 分钟）',
    'home': '家庭练习',
}
# plan.kind → 教学阶段。按"词干包含"匹配，用于给步骤归类，不改变作者给出的 kind。
# 顺序即优先级：更具体的词干必须排在更泛的词干之前。
KIND_PHASE_STEMS = (
    ('共同例题', '共做'), ('教师例题', '例题'), ('例题', '示范'),
    ('热身', '导入'), ('导入', '导入'), ('目标', '目标'),
    ('半完成', '共做'), ('错误例', '对照'), ('对照', '对照'), ('诊断', '对照'),
    ('变式', '对照'), ('偏离', '对照'),
    ('带练', '练习'), ('练习', '练习'),
    ('独立制作', '独立'), ('个人独立', '独立'),
    ('母本', '迁移'), ('个人检查', '迁移'), ('迁移', '迁移'),
    ('进阶', '进阶'), ('家庭', '家庭'), ('作业', '家庭'),
    ('worked', '示范'), ('partial', '共做'), ('error', '对照'), ('diagnostic', '对照'),
    ('individual', '独立'), ('transfer', '练习'), ('challenge', '进阶'),
)
# 例题/习题类阶段（投屏中的"例题练习"面板）
EXERCISE_PHASES = {'示范', '共做', '对照', '例题', '练习', '独立', '迁移', '进阶'}
# 无 plan / 仅角色名标题的课程包：从分页标题推断阶段（关键字映射，仅用于标注阶段，不改写内容）
TITLE_PHASE_HINTS = (
    ('目标', '目标'), ('先修', '目标'), ('约定', '目标'), ('导览', '目标'), ('支持', '导入'),
    ('熟悉', '导入'), ('呈现', '目标'), ('示范', '示范'), ('例题', '示范'),
    ('共同', '共做'), ('半成品', '共做'), ('半完成', '共做'), ('综合', '共做'), ('integrated', '共做'),
    ('错误', '对照'), ('对照', '对照'), ('变式', '对照'), ('测前', '对照'), ('偏离', '对照'), ('诊断', '对照'),
    ('带练', '练习'), ('练习', '练习'), ('独立', '独立'), ('个人', '独立'),
    ('检验', '迁移'), ('检查', '迁移'), ('退出', '迁移'), ('收束', '反馈'), ('收指令', '反馈'),
    ('进阶', '进阶'), ('家庭', '家庭'), ('作业', '家庭'), ('热身', '导入'),
    ('worked', '示范'), ('partial', '共做'), ('error', '对照'), ('diagnostic', '对照'),
    ('individual', '独立'), ('transfer', '练习'), ('challenge', '进阶'), ('teacher', '例题'),
)
# 步骤课号尾部字母 → 段（P 表示"例题与习题"段，即 B 段）
SEGMENT_TOKEN_MAP = {'A': 'A', 'B': 'B', 'H': 'home', 'P': 'B', 'E': 'A',
                     'I': 'B', 'G': 'B', 'C': 'B', 'T': 'B', 'V': 'B'}
SEGMENT_TOKEN_RE = re.compile(r'(?:^|[-_ 　·])([ABHPEGCITV])(\d+)?(?![A-Za-z0-9])')

LESSON_ID_RE = re.compile(r'^(?:(?:AY1-)?S(?:46|79|1012|1315|1618)-T[12]-U\d{2}|S(?:46|79|1012|1315|1618)-\d{2})$')
STEP_TOKEN_RE = re.compile(r'\b([A-Z]\d+)\b')
WINDOW_RE = re.compile(r'^(\d+)\s*[–\-~]\s*(\d+)$')
MANIFEST_RELATIVE = 'docs/SEMESTER_ACCEPTANCE_MANIFEST.json'


class FlowError(ValueError):
    """面向用户的流程构建错误（与 annual_pack.PackError 分开，便于 HTTP 层映射状态码）。"""

    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


@lru_cache(maxsize=4)
def _manifest(root):
    try:
        document = json.loads((Path(root) / MANIFEST_RELATIVE).read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return {}
    return document if isinstance(document, dict) else {}


def lesson_catalog(root):
    """学期课程目录：{band_id: [ {week, lessonId, title}, … ]}，来自学期验收清单（只读）。"""
    bands = {}
    for row in _manifest(root).get('lessons', []):
        if not isinstance(row, dict):
            continue
        band, lesson_id, title = row.get('band_id'), row.get('id'), row.get('title')
        if not band or not isinstance(lesson_id, str):
            continue
        bands.setdefault(band, []).append({
            'week': row.get('week'), 'lessonId': lesson_id,
            'title': title if isinstance(title, str) else lesson_id,
        })
    for rows in bands.values():
        rows.sort(key=lambda r: (r['week'] if isinstance(r['week'], int) else 0, r['lessonId']))
    return bands


def lesson_meta(root, lesson_id):
    """返回课程的 (band_id, week, title)。未知课号时退回由课号推断的班型与周次。"""
    for band, rows in lesson_catalog(root).items():
        for row in rows:
            if row['lessonId'] == lesson_id:
                return band, row['week'], row['title']
    band = annual_pack.lesson_band(lesson_id) if LESSON_ID_RE.match(lesson_id or '') else None
    suffix = re.search(r'(\d{2})$', lesson_id or '')
    return band, (int(suffix.group(1)) if suffix else None), lesson_id


def _parse_minutes(window):
    if not isinstance(window, str):
        return None
    match = WINDOW_RE.match(window.strip())
    if not match:
        return None
    start, end = int(match.group(1)), int(match.group(2))
    return end - start if end >= start else None


def _window_end(window):
    match = WINDOW_RE.match((window or '').strip()) if isinstance(window, str) else None
    return int(match.group(2)) if match else None


def _phase_for_kind(kind):
    text = kind or ''
    for stem, phase in KIND_PHASE_STEMS:
        if stem in text:
            return phase
    return '讲解'


def _infer_segment(identifier, title):
    """从课号或标题里解析段标记：``W01-A1``→A，``S1012-01-P1``→B（例题习题段），``H1``→home。"""
    for text in (identifier or '', title or ''):
        for match in SEGMENT_TOKEN_RE.finditer(text.strip()):
            segment = SEGMENT_TOKEN_MAP.get(match.group(1))
            if segment:
                return segment
    return 'A'


def _infer_phase(title):
    lowered = (title or '').lower()
    for keyword, phase in TITLE_PHASE_HINTS:
        if keyword in (title or '') or keyword in lowered:
            return phase
    return '讲解'


def _phase_window(segment, phase):
    """文档化模型里某阶段的分钟窗口；未知阶段返回 None（不臆造分钟）。"""
    if segment == 'A':
        return A_MODEL_WINDOWS.get(phase)
    if segment == 'B':
        return B_MODEL_WINDOWS.get(phase)
    return '课后' if segment == 'home' else None


def _authored_pacing(source_dir):
    """读取作者侧 goalSegments（若课程包提供）。

    返回 ``{segment: [ {window, action, evidence, refs} ]}``，保留作者给出的顺序，
    以便既能把节奏挂到对应的活动步骤上，也能把没有活动引用的纯教学阶段（如"0–5 导入"）
    作为独立可授课段落保留下来。
    """
    file = Path(source_dir) / 'authored-content.json'
    if not file.is_file():
        return None
    try:
        document = json.loads(file.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return None
    segments = document.get('goalSegments') if isinstance(document, dict) else None
    if not isinstance(segments, dict):
        return None
    table = {}
    for segment, rows in segments.items():
        if not isinstance(rows, list):
            continue
        collected = []
        for row in rows:
            if not isinstance(row, (list, tuple)) or len(row) < 2:
                continue
            text = row[2] if len(row) > 2 and isinstance(row[2], str) else ''
            collected.append({
                'window': row[0] if isinstance(row[0], str) else None,
                'action': row[1] if isinstance(row[1], str) else '',
                'evidence': row[3] if len(row) > 3 and isinstance(row[3], str) else '',
                'refs': STEP_TOKEN_RE.findall(text),
            })
        if collected:
            table[segment] = collected
    return table or None


def _sentences(text):
    if not isinstance(text, str):
        return []
    return [part.strip() for part in re.split(r'[。；\n]', text) if part.strip()]


def _match_item(entry_id, entry_title, items):
    """把 plan 条目对齐到课件分页：课号出现在分页标题/素材路径，或分页标题与 plan 标题一致。"""
    if isinstance(entry_id, str) and entry_id:
        for item in items:
            if entry_id in (item.get('title') or ''):
                return item
            if any(entry_id in path for path in (item.get('assetPaths') or [])):
                return item
    title = (entry_title or '').strip()
    if len(title) >= 6:
        for item in items:
            if (item.get('title') or '').strip() == title:
                return item
    return None


def _step_from_plan(entry, item):
    kind = entry.get('kind') if isinstance(entry.get('kind'), str) else ''
    title = entry.get('title') if isinstance(entry.get('title'), str) else ''
    segment = entry.get('segment') if entry.get('segment') in SEGMENT_LABELS else _infer_segment(entry.get('id'), title)
    item = item or {}
    return {
        'id': entry.get('id'), 'segment': segment,
        'phase': _phase_for_kind(kind) if kind else _infer_phase(title), 'kind': kind,
        'medium': entry.get('medium') if isinstance(entry.get('medium'), str) else '',
        'title': title,
        'teachingPoints': _sentences(entry.get('body'))[:4],
        'studentTask': item.get('studentContent') or '',
        'teacherNotes': item.get('teacherNotes') or (entry.get('reveal') if isinstance(entry.get('reveal'), str) else '') or '',
        'visibility': item.get('visibility', 'public'),
        'answerIncluded': bool(item.get('answerIncluded', False)),
        'materialPaths': list(item.get('assetPaths') or []),
        'teacherMaterialPaths': list(item.get('teacherAssets') or []),
        'estimatedMinutes': item.get('estimatedMinutes'),
    }


def _step_from_item(item):
    title = item.get('title') or ''
    identifier = item.get('id') or ''
    kind = ''
    return {
        'id': identifier or title, 'segment': _infer_segment(identifier, title),
        'phase': _infer_phase(title), 'kind': kind,
        'medium': '', 'title': title,
        'teachingPoints': _sentences(item.get('studentContent'))[:4],
        'studentTask': item.get('studentContent') or '',
        'teacherNotes': item.get('teacherNotes') or '',
        'visibility': item.get('visibility', 'public'),
        'answerIncluded': bool(item.get('answerIncluded', False)),
        'materialPaths': list(item.get('assetPaths') or []),
        'teacherMaterialPaths': list(item.get('teacherAssets') or []),
        'estimatedMinutes': item.get('estimatedMinutes'),
    }


SEGMENT_ORDER = {'A': 0, 'B': 1, 'home': 2}


def _authored_phase_steps(steps, authored):
    """把有节奏但未引用任何活动的教学阶段（如"0–5 导入/熟悉"）补成可授课段落。

    仅补"完全没有活动引用"的行，避免与 plan 活动步骤重复；按窗口起点与原顺序合并。
    """
    extra = []
    used = {step['segment'] for step in steps}
    for segment, rows in authored.items():
        if segment not in SEGMENT_LABELS:
            continue
        for row in rows:
            if row['refs'] or not row['window'] or not row['action']:
                continue
            extra.append({
                'id': 'phase-' + row['window'], 'segment': segment,
                'phase': _infer_phase(row['action']), 'kind': '教学阶段',
                'medium': '', 'title': row['action'],
                'teachingPoints': [row['action']], 'studentTask': '',
                'teacherNotes': '', 'visibility': 'public', 'answerIncluded': False,
                'materialPaths': [], 'teacherMaterialPaths': [], 'estimatedMinutes': None,
                'window': row['window'], 'minutes': _parse_minutes(row['window']),
                'pacingSource': 'authored', 'evidence': row['evidence'],
                '_order': rows.index(row),
            })
    if not extra:
        return steps
    position = {step['id']: i for i, step in enumerate(steps)}
    merged = steps + extra
    merged.sort(key=lambda step: _authored_sort_key(step, position))
    return merged


def _authored_sort_key(step, position):
    """作者节奏下的顺序：先按段，再按窗口起点，最后保留课程包给出的原顺序。"""
    window = step.get('window')
    if window is None:
        rank = (3, 0)
    elif window == '课后':
        rank = (0, 0)
    else:
        match = re.match(r'^(\d+)', window)
        rank = (1, int(match.group(1))) if match else (2, 0)
    fallback = position.get(step['id'], 10_000 + step.get('_order', 0))
    return (SEGMENT_ORDER.get(step['segment'], 9), rank[0], rank[1], fallback)


def _apply_timing(step, authored, cursors):
    if step.get('pacingSource') == 'authored' and step.get('window'):
        end = _window_end(step['window'])
        if end is not None:
            cursors[step['segment']] = max(cursors.get(step['segment'], 0), end)
        step['minutes'] = _parse_minutes(step['window']) or step.get('estimatedMinutes')
        return
    row = None
    for candidate in (authored or {}).get(step['segment'], []):
        if step['id'] in candidate['refs']:
            row = candidate
            break
    if row and row['window']:
        step.update(window=row['window'], pacingSource='authored', evidence=row['evidence'])
        if row['action']:
            step['teachingPoints'] = ([row['action']] + step['teachingPoints'])[:4]
    elif step['segment'] == 'home':
        step.update(window='课后', pacingSource='model', evidence='')
    elif step.get('estimatedMinutes'):
        # 课件自带的逐页时长比通用模型更具体：优先用它累加出真实时段。
        start = cursors.get(step['segment'], 0)
        step.update(window=f'{start}–{start + step["estimatedMinutes"]}',
                    pacingSource='slides', evidence='')
    else:
        model = _phase_window(step['segment'], step['phase'])
        if model:
            step.update(window=model, pacingSource='model', evidence='')
        else:
            step.update(window=None, pacingSource='model', evidence='')
    end = _window_end(step.get('window'))
    if end is not None:
        cursors[step['segment']] = max(cursors.get(step['segment'], 0), end)
    step['minutes'] = _parse_minutes(step.get('window'))
    if step['minutes'] is None:
        step['minutes'] = step.get('estimatedMinutes')


def _assign_timing(steps, authored):
    """为每段标注时段：作者节奏 > 逐页 estimatedMinutes 累加 > 文档化模型。"""
    cursors = {}
    for step in steps:
        _apply_timing(step, authored, cursors)


def build_flow(root, lesson_id, audience='teacher'):
    """构建一节可授课的逐段课堂流程。

    ``audience='teacher'`` 保留教师提示与教师专属素材；``audience='student'`` 去除教师提示、
    答案与教师专属素材，并把非公开/含答案的步骤标记为不可见。

    结构只读一次课程包（教师受众），再按受众投影：这样教师端与学生端的**段落数量与索引始终
    一致**——教师停在第 N 段时，学生拿到的就是同一条任务（公开则给内容，非公开则只显示等待）。
    """
    if not isinstance(lesson_id, str) or not LESSON_ID_RE.match(lesson_id):
        raise FlowError(400, '课程编号无效。')
    if audience not in ('teacher', 'student'):
        raise FlowError(400, '预览对象无效。')
    try:
        pack = annual_pack.read(root, lesson_id, audience='teacher')
    except annual_pack.PackError as error:
        raise FlowError(error.status, error.message)
    return build_flow_from_teacher_pack(root, lesson_id, pack, audience)


def build_flow_from_teacher_pack(root, lesson_id, pack, audience='teacher'):
    """Build the same audience-safe flow from an immutable registered teacher-pack snapshot."""
    if not isinstance(lesson_id, str) or not LESSON_ID_RE.match(lesson_id):
        raise FlowError(400, '课程编号无效。')
    if audience not in ('teacher', 'student'):
        raise FlowError(400, '预览对象无效。')
    if not isinstance(pack, dict) or not isinstance(pack.get('slides'), dict):
        raise FlowError(409, '当前课程版本没有冻结可用的教师课件。')
    items = pack['slides']['items']
    plan = pack.get('plan') or []
    band, week, title = lesson_meta(root, lesson_id)
    band = pack.get('bandId') or pack.get('ageBand') or band
    title = pack.get('title') or title

    if plan:
        steps = [_step_from_plan(entry, _match_item(entry.get('id'), entry.get('title'), items)) for entry in plan]
    else:
        steps = [_step_from_item(item) for item in items]

    authored = pack.get('authoredPacing')
    if not isinstance(authored, dict):
        authored = _authored_pacing(str(Path(root) / pack['source'])) if pack.get('source') else None
    _assign_timing(steps, authored)
    if authored:
        steps = _authored_phase_steps(steps, authored)

    for index, step in enumerate(steps):
        step['index'] = index
        step['isExercise'] = step['phase'] in EXERCISE_PHASES
        step['public'] = step['visibility'] == 'public' and not step['answerIncluded']
        step['phaseLabel'] = SEGMENT_LABELS.get(step['segment'], step['segment'])

    flow = {
        'lessonId': lesson_id, 'resolvedPackageLessonId': pack.get('resolvedPackageLessonId') or lesson_id,
        'bandId': band, 'week': week, 'lessonTitle': title,
        'source': pack.get('source') or 'frozen-course-version', 'status': pack.get('status'),
        'slideFormat': pack['slides'].get('format'),
        'pacingSource': next((s['pacingSource'] for s in steps if s['pacingSource'] != 'model'), 'model'),
        'materials': sorted((pack.get('materials') or {})) if audience == 'teacher' else [],
        'audience': audience,
    }
    if audience == 'student':
        flow['steps'] = [_student_projection(step) for step in steps]
    else:
        flow['steps'] = steps
        flow['exercises'] = step_exercises(steps)
    return flow


def _student_projection(step):
    """学生投影：只留公开的"当前任务"，去掉教师提示、答案与教师专属素材。"""
    public = step['public']
    return {
        'index': step['index'], 'id': step['id'], 'segment': step['segment'], 'phase': step['phase'],
        'phaseLabel': step['phaseLabel'], 'window': step['window'], 'minutes': step['minutes'],
        'pacingSource': step['pacingSource'],
        'title': step['title'], 'kind': step['kind'], 'medium': step['medium'],
        'public': public, 'isExercise': step['isExercise'],
        'studentTask': step['studentTask'] if public else '',
        'teachingPoints': step['teachingPoints'] if public else [],
        'materialPaths': list(step['materialPaths']) if public else [],
        'teacherNotes': '', 'teacherMaterialPaths': [],
    }


def step_materials(step, assets):
    """把某段的素材路径解析为可投屏的素材描述（含 base64 data URL）。"""
    rows = []
    wanted = list(step.get('materialPaths') or []) + list(step.get('teacherMaterialPaths') or [])
    for path in dict.fromkeys(wanted):
        row = assets.get(path)
        if not row:
            continue
        rows.append({'path': path, 'mime': row.get('mime'), 'sha256': row.get('sha256'),
                     'teacherOnly': bool(row.get('teacherOnly')), 'dataUrl': _data_url(row)})
    return rows


def _data_url(row):
    base64_text = row.get('base64')
    mime = row.get('mime') or 'application/octet-stream'
    return f'data:{mime};base64,{base64_text}' if base64_text else None


def load_assets(root, lesson_id, audience='teacher'):
    """取课程包的原始素材映射（教师受众含 teacherOnly 素材）。"""
    try:
        pack = annual_pack.read(root, lesson_id, audience=audience)
    except annual_pack.PackError as error:
        raise FlowError(error.status, error.message)
    return pack.get('assets') or {}


def step_exercises(steps):
    """整节课的例题/习题清单（用于教师备课面板与投屏进度）。"""
    return [{'id': step['id'], 'title': step['title'], 'kind': step['kind'],
             'segment': step['segment'], 'phase': step['phase'], 'index': step['index'],
             'answerIncluded': step.get('answerIncluded', False),
             'public': step.get('public', True)} for step in steps if step.get('isExercise')]
