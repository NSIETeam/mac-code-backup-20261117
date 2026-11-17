#!/usr/bin/env python3
"""Read-only legacy migration preflight. Emits aggregates, never record identifiers or PII."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone, timedelta
import re
from zoneinfo import ZoneInfo
import json
from pathlib import Path
import sqlite3


def split_minor(total, count):
    # Matches Java enrollment allocation: remainder cents go to the earliest lessons.
    if type(total) is not int or total < 0 or type(count) is not int or not 1 <= count <= 200:
        raise ValueError('Invalid minor-unit allocation')
    base, remainder = divmod(total, count)
    return [base + (i < remainder) for i in range(count)]


def order_origin(row):
    """Preserve direct enrollment; absence of CRM links is not itself corruption."""
    opportunity, customer = bool(row.get('opportunityId')), bool(row.get('customerId'))
    if opportunity and customer:
        return 'crm_opportunity'
    if not opportunity and not customer and row.get('enrollmentId'):
        return 'direct_enrollment'
    return 'incomplete_origin'


def session_utc(session):
    # Legacy calendar strings are China local time; never use the machine timezone.
    day = datetime.strptime(session['date'], '%Y-%m-%d')
    match = re.fullmatch(r'(\d{2}):(\d{2})[–—-](\d{2}):(\d{2})', session['time'])
    if match is None:
        raise ValueError('Invalid session time format')
    sh, sm, eh, em = map(int, match.groups())
    if not (sh < 24 and sm < 60 and em < 60 and (eh < 24 or eh == 24 and em == 0)):
        raise ValueError('Invalid session clock time')
    start, end = sh * 60 + sm, eh * 60 + em
    if end <= start:
        raise ValueError('Invalid session range')
    zone = ZoneInfo('Asia/Shanghai')
    return tuple((day + timedelta(minutes=m)).replace(tzinfo=zone).astimezone(timezone.utc)
                 for m in (start, end))


def inspect(state, users):
    errors = Counter()
    gaps = Counter()
    tables = {}
    for name in ('campuses', 'courses', 'classes', 'students', 'orders', 'enrollments',
                 'receipts', 'refunds', 'attendance', 'consumptions', 'customers', 'opportunities'):
        rows = state.get(name, [])
        if not isinstance(rows, list):
            errors[name + '.invalid_collection'] += 1
            rows = []
        clean = []
        for row in rows:
            if not isinstance(row, dict) or not isinstance(row.get('id'), str) or not row['id']:
                errors[name + '.invalid_identity'] += 1
            else:
                clean.append(row)
        tables[name] = {r['id']: r for r in clean}
        errors[name + '.duplicate_identity'] += len(clean) - len(tables[name])
    user_ids = {u.get('id') for u in users if isinstance(u, dict) and u.get('id')}

    def ref(row, field, target, label, required=True):
        value = row.get(field)
        if not value:
            if required:
                errors[label + '.missing_' + field] += 1
            return None
        found = tables[target].get(value)
        if found is None:
            errors[label + '.unresolved_' + field] += 1
        return found

    def money(row, field, label):
        value = row.get(field)
        if type(value) is not int or value < 0:
            errors[label + '.invalid_' + field] += 1
            return None
        return value

    lessons = {}
    sessions = {}
    for course in tables['courses'].values():
        for lesson in course.get('lessons', []):
            key = lesson.get('id')
            if not key or key in lessons:
                errors['lessons.invalid_or_duplicate_identity'] += 1
            else:
                lessons[key] = (course['id'], lesson)
    for row in tables['classes'].values():
        ref(row, 'courseId', 'courses', 'classes')
        ref(row, 'campusId', 'campuses', 'classes')
        teachers = row.get('teacherIds')
        if teachers is not None and (not isinstance(teachers, list) or teachers):
            gaps['classes.teacherIds_unmapped'] += 1
        class_lessons = set()
        for session in row.get('schedule', []):
            if session.get('record') is not None:
                gaps['sessions.teaching_record_content_actor_time_unmapped'] += 1
            for field in ('substituteReason', 'substituteTeacherId'):
                value = session.get(field)
                if value is not None and (not isinstance(value, str) or value.strip()):
                    gaps['sessions.' + field + '_unmapped'] += 1
            key = session.get('id')
            lesson = lessons.get(session.get('lessonId'))
            if not key or key in sessions:
                errors['sessions.invalid_or_duplicate_identity'] += 1
            else:
                sessions[key] = (row['id'], session)
            if lesson is None or lesson[0] != row.get('courseId'):
                errors['sessions.lesson_course_mismatch'] += 1
            if session.get('lessonId') in class_lessons:
                gaps['sessions.repeated_class_lesson'] += 1
            class_lessons.add(session.get('lessonId'))
            try:
                start, end = session_utc(session)
                if end <= start:
                    raise ValueError()
            except (ValueError, TypeError, KeyError):
                errors['sessions.invalid_time_range'] += 1
            if session.get('teacherId', row.get('teacherId')) not in user_ids:
                errors['sessions.unresolved_teacherId'] += 1
        if row.get('teacherId') not in user_ids:
            errors['classes.unresolved_teacherId'] += 1
    for row in tables['students'].values():
        ref(row, 'campusId', 'campuses', 'students')
        if row.get('ownerId') and row['ownerId'] not in user_ids:
            errors['students.unresolved_ownerId'] += 1
    receipt_sum = defaultdict(int)
    for row in tables['receipts'].values():
        order = ref(row, 'orderId', 'orders', 'receipts')
        ref(row, 'enrollmentId', 'enrollments', 'receipts')
        amount = money(row, 'amountCents', 'receipts')
        if amount is not None:
            receipt_sum[row.get('orderId')] += amount
            if amount == 0:
                gaps['receipts.zero_amount_event'] += 1
        if row.get('confirmedBy') not in user_ids:
            errors['receipts.unresolved_confirmedBy'] += 1
        if order and (row.get('studentId') != order.get('studentId')
                      or row.get('enrollmentId') != order.get('enrollmentId')):
            errors['receipts.order_relationship_mismatch'] += 1
    totals = Counter()
    origins = Counter()
    entitlement_amounts = {}
    for row in tables['orders'].values():
        ref(row, 'studentId', 'students', 'orders')
        ref(row, 'campusId', 'campuses', 'orders')
        ref(row, 'classId', 'classes', 'orders')
        ref(row, 'courseId', 'courses', 'orders')
        enrollment = ref(row, 'enrollmentId', 'enrollments', 'orders')
        if enrollment and (enrollment.get('orderId') != row['id']
                           or enrollment.get('studentId') != row.get('studentId')):
            errors['orders.enrollment_relationship_mismatch'] += 1
        if row.get('ownerId') not in user_ids:
            gaps['orders.unresolved_or_missing_owner'] += 1
        origin = order_origin(row)
        origins[origin] += 1
        if origin == 'incomplete_origin':
            errors['orders.incomplete_origin'] += 1
        if row.get('opportunityId'):
            ref(row, 'opportunityId', 'opportunities', 'orders')
        if row.get('customerId'):
            ref(row, 'customerId', 'customers', 'orders')
        amounts = {field: money(row, field, 'orders') for field in
                   ('amountCents', 'paidCents', 'refundedCents')}
        for field, value in amounts.items():
            if value is not None:
                totals[field] += value
        snapshots = row.get('lessonSnapshot', [])
        snapshot_ids = [lesson.get('id') for lesson in snapshots]
        if not snapshots or None in snapshot_ids or len(set(snapshot_ids)) != len(snapshot_ids):
            errors['orders.invalid_lesson_snapshot'] += 1
        elif amounts['amountCents'] is not None:
            units = split_minor(amounts['amountCents'], len(snapshots))
            for lesson, unit in zip(snapshots, units):
                key = lesson['id']
                entitlement_amounts[(row['id'], key)] = unit
                original = lessons.get(key)
                if original is None:
                    gaps['orders.historical_lesson_missing_from_catalog'] += 1
                elif original[0] != row.get('courseId'):
                    errors['orders.lesson_course_mismatch'] += 1
                elif original[1].get('title') != lesson.get('title'):
                    gaps['orders.historical_lesson_title_changed'] += 1
        amount, paid, refunded = (amounts[k] for k in ('amountCents', 'paidCents', 'refundedCents'))
        if paid is not None and paid != receipt_sum[row['id']]:
            errors['orders.receipt_total_mismatch'] += 1
        if amount == 0:
            gaps['orders.zero_price'] += 1
        if None not in (amount, paid, refunded) and not 0 <= refunded <= paid <= amount:
            errors['orders.invalid_balance'] += 1
    for row in tables['enrollments'].values():
        ref(row, 'studentId', 'students', 'enrollments')
        ref(row, 'classId', 'classes', 'enrollments')
        ref(row, 'orderId', 'orders', 'enrollments')
        if row.get('initialClassId', row.get('classId')) != row.get('classId'):
            gaps['enrollments.transfer_history_required'] += 1
        for old, new in row.get('lessonMap', {}).items():
            if old != new:
                gaps['enrollments.lesson_delivery_mapping_required'] += 1
    for name in ('attendance', 'consumptions'):
        for row in tables[name].values():
            order = ref(row, 'orderId', 'orders', name)
            ref(row, 'studentId', 'students', name)
            ref(row, 'enrollmentId', 'enrollments', name)
            cls = ref(row, 'classId', 'classes', name)
            if cls and row.get('sessionId') not in {s.get('id') for s in cls.get('schedule', [])}:
                errors[name + '.unresolved_sessionId'] += 1
            if order and row.get('lessonId') not in {s.get('id') for s in order.get('lessonSnapshot', [])}:
                errors[name + '.unresolved_order_lesson'] += 1
            if name == 'consumptions':
                ref(row, 'attendanceId', 'attendance', name)
                value = money(row, 'amountCents', name)
                if value is not None:
                    totals['consumptionCents'] += value
                    unit = entitlement_amounts.get((row.get('orderId'), row.get('lessonId')))
                    if unit is not None and unit != value:
                        gaps['consumptions.snapshot_allocation_differs'] += 1
                attendance = tables['attendance'].get(row.get('attendanceId'))
                if attendance and any(row.get(k) != attendance.get(k) for k in
                                      ('studentId', 'sessionId', 'orderId', 'enrollmentId', 'lessonId')):
                    errors['consumptions.attendance_relationship_mismatch'] += 1
    unassessed = {key: len(value) for key, value in state.items()
                  if key not in tables and key != 'schemaVersion'
                  and isinstance(value, (list, dict)) and value}
    return {'nestedCounts': {'lessons': len(lessons), 'sessions': len(sessions)}, 'counts': {k: len(v) for k, v in tables.items()}, 'totalsMinor': dict(totals),
            'orderOrigins': dict(origins), 'unassessedSourceSections': unassessed,
            'sourceErrors': {k: v for k, v in sorted(errors.items()) if v},
            'javaMappingGaps': dict(sorted(gaps.items())),
            'safeToImport': not any(errors.values()) and not any(gaps.values()) and not unassessed,
            'coverage': 'Identity, primary academic references and order receipt totals only; not complete migration acceptance.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--sqlite')
    source.add_argument('--snapshot')
    parser.add_argument('--auth')
    args = parser.parse_args()
    if args.snapshot:
        envelope = json.loads(Path(args.snapshot).read_text())
        if envelope.get('schema') != 'siyue.legacy-business/v1' or type(envelope.get('revision')) is not int:
            parser.error('Invalid snapshot envelope')
        revision, state = envelope['revision'], envelope['state']
    else:
        db = sqlite3.connect(Path(args.sqlite).resolve().as_uri() + '?mode=ro', uri=True)
        db.execute('PRAGMA query_only=ON')
        with db:
            revision, raw = db.execute('SELECT revision,data FROM business_state WHERE id=1').fetchone()
        db.close()
        state = json.loads(raw)
    auth = json.loads(Path(args.auth).read_text()) if args.auth else {'users': []}
    result = inspect(state, auth.get('users', []))
    result['identityVerification'] = 'checked' if args.auth else 'not_checked_no_auth_snapshot'
    if not args.auth:
        # Missing authentication evidence must never produce a safe-to-import verdict.
        result['safeToImport'] = False
        pending = {}
        for group in ('sourceErrors', 'javaMappingGaps'):
            for key in list(result[group]):
                if key.endswith(('.unresolved_teacherId', '.unresolved_ownerId',
                                 '.unresolved_confirmedBy', '.unresolved_or_missing_owner')):
                    pending[key] = result[group].pop(key)
        result['unverifiedIdentityReferences'] = pending
    result.update({'sourceRevision': revision, 'checkedAt': datetime.now(timezone.utc).isoformat(),
                   'snapshotNote': 'Read-only business snapshot; auth, when supplied, read separately. No write freeze or cutover snapshot.'})
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
