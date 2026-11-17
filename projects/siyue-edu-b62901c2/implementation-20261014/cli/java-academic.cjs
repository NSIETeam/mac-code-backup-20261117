'use strict';
const auth = require("./auth-client.cjs");
const business = require("./business-client.cjs");
const operations = [
    { id: 'enrollment.cancel', name: 'enrollment-cancel', method: 'POST', endpoint: 'academic/enrollments/{enrollmentId}/cancel', path: ['enrollmentId'], permissions: ['enrollment.create'], fields: ['reason', 'expectedVersion'], required: ['reason', 'expectedVersion'] },
    { id: 'enrollment.withdraw', name: 'enrollment-withdraw', method: 'POST', endpoint: 'academic/enrollments/{enrollmentId}/withdraw', path: ['enrollmentId'], permissions: ['enrollment.withdraw'], fields: ['reason', 'expectedVersion'], required: ['reason', 'expectedVersion'] },
    { id: 'commerce.checkout.cancel', name: 'checkout-cancel', method: 'POST', endpoint: 'academic/commerce-checkouts/{checkoutId}/cancel', path: ['checkoutId'], permissions: ['enrollment.create'], fields: ['reason', 'expectedVersion'], required: ['reason', 'expectedVersion'] },
    { id: 'course.create', name: 'course-create', method: 'POST', endpoint: 'academic/courses', permissions: ['course.write'], permissionGroups: [['course.write', 'course.create']], fields: ['campusId', 'name', 'courseType', 'grade', 'subject', 'price', 'lessons', 'metadata'], required: ['name', 'courseType', 'grade', 'subject', 'price', 'lessons'] },
    { id: 'course.update', name: 'course-update', method: 'PUT', endpoint: 'academic/courses/{courseId}', path: ['courseId'], permissions: ['course.write'], permissionGroups: [['course.write', 'course.update']], fields: ['name', 'year', 'expectedVersion', 'lessons', 'price'], required: ['name', 'expectedVersion', 'lessons'] },
    { id: 'course.publish', name: 'course-publish', method: 'POST', endpoint: 'academic/courses/{courseId}/publish', path: ['courseId'], permissions: ['course.publish'], fields: ['expectedVersion'], required: ['expectedVersion'] },
    { id: 'course.status', name: 'course-status', method: 'PUT', endpoint: 'academic/courses/{courseId}/status', path: ['courseId'], permissions: ['course.write'], permissionGroups: [['course.write', 'course.update']], fields: ['active', 'expectedVersion'], required: ['active', 'expectedVersion'] },
    { id: 'class.create', name: 'class-create', method: 'POST', endpoint: 'academic/classes', permissions: ['class.write'], permissionGroups: [['class.write', 'class.create']], fields: ['courseId', 'campusId', 'teacherId', 'name', 'room', 'capacity', 'durationMinutes', 'schedule', 'fees'], required: ['courseId', 'campusId', 'teacherId', 'name', 'capacity', 'durationMinutes', 'schedule'] },
    { id: 'class.batchCreate', name: 'class-batch-create', method: 'POST', endpoint: 'academic/classes/batch', permissions: ['class.write'], permissionGroups: [['class.write', 'class.batchCreate']], fields: ['classes'], required: ['classes'] },
    { id: 'class.update', name: 'class-update', method: 'PUT', endpoint: 'academic/classes/{classId}', path: ['classId'], permissions: ['class.write'], permissionGroups: [['class.write', 'class.create']], fields: ['name', 'room', 'teacherId', 'capacity', 'expectedVersion'], required: ['name', 'teacherId', 'capacity', 'expectedVersion'] },
    { id: 'class.schedule', name: 'class-schedule', method: 'PUT', endpoint: 'academic/classes/{classId}/schedule', path: ['classId'], permissions: ['class.write', 'class.create'], permissionGroups: [['class.write', 'class.create']], fields: ['expectedVersion', 'sessions'], required: ['expectedVersion', 'sessions'] },
    { id: 'class.relocate', name: 'class-relocate', method: 'POST', endpoint: 'academic/classes/relocate', permissions: ['class.relocate'], fields: ['classes', 'campusId', 'room'], required: ['classes', 'campusId', 'room'] },
    { id: 'session.move', name: 'session-move', method: 'PUT', endpoint: 'academic/sessions/{sessionId}/schedule', path: ['sessionId'], permissions: ['session.move'], fields: ['startsAt', 'reason', 'expectedVersion'], required: ['startsAt', 'reason', 'expectedVersion'] },
    { id: 'session.substitute', name: 'session-substitute', method: 'PUT', endpoint: 'academic/sessions/{sessionId}/substitute', path: ['sessionId'], permissions: ['session.substitute'], fields: ['teacherId', 'reason', 'expectedVersion'], required: ['teacherId', 'reason', 'expectedVersion'] },
    { id: 'session.record', name: 'session-record', method: 'POST', endpoint: 'academic/sessions/{sessionId}/record', path: ['sessionId'], permissions: ['session.record'], fields: ['content', 'expectedVersion'], required: ['content', 'expectedVersion'] },
    { id: 'session.reschedule.request', name: 'session-reschedule-request', method: 'POST', endpoint: 'academic/session-reschedules', permissions: ['session.reschedule.request'], fields: ['classId', 'sessionId', 'date', 'time', 'reason', 'expectedVersion'], required: ['classId', 'sessionId', 'date', 'time', 'reason', 'expectedVersion'] },
    { id: 'session.reschedule.read', name: 'session-reschedule-list', method: 'GET', endpoint: 'academic/session-reschedules', permissions: ['session.reschedule.request', 'session.move'], permissionGroups: [['session.reschedule.request', 'session.move']], fields: ['status', 'page', 'size'], required: [], readOnly: true },
    { id: 'session.reschedule.review', name: 'session-reschedule-review', method: 'PUT', endpoint: 'academic/session-reschedules/{requestId}/review', path: ['requestId'], permissions: ['session.move'], fields: ['decision', 'note', 'expectedVersion'], required: ['decision', 'expectedVersion'] },
    { id: 'session.attendance.exceptions', name: 'session-attendance-exceptions', method: 'POST', endpoint: 'academic/sessions/{sessionId}/attendance/exceptions', path: ['sessionId'], permissions: ['attendance.manage', 'session.attendance'], permissionGroups: [['attendance.manage', 'session.attendance']], fields: ['classId', 'exceptions'], required: ['classId', 'exceptions'] },
    { id: 'attendance.record', name: 'attendance-record', method: 'POST', endpoint: 'academic/sessions/{sessionId}/students/{studentId}/attendance', path: ['sessionId', 'studentId'], permissions: ['attendance.manage'], fields: ['classId', 'status', 'note'], required: ['classId', 'status'] },
    { id: 'attendance.batch', name: 'attendance-batch', method: 'POST', endpoint: 'academic/sessions/{sessionId}/attendance', path: ['sessionId'], permissions: ['attendance.manage'], fields: ['classId', 'rows'], required: ['classId', 'rows'] },
    { id: 'attendance.correct', name: 'attendance-correct', method: 'POST', endpoint: 'academic/sessions/{sessionId}/students/{studentId}/attendance/corrections', path: ['sessionId', 'studentId'], permissions: ['attendance.manage'], fields: ['classId', 'expectedVersion', 'status', 'note', 'reason'], required: ['classId', 'expectedVersion', 'status', 'reason'] },
    { id: 'enrollment.create', name: 'class-enroll', method: 'POST', endpoint: 'academic/classes/{classId}/enrollments', path: ['classId'], permissions: ['enrollment.manage', 'crm.order.read'], permissionGroups: [['enrollment.manage', 'enrollment.create'], ['crm.order.read']], fields: ['studentId', 'orderId'], required: ['studentId', 'orderId'] },
    { id: 'enrollment.batchCreate', name: 'enrollment-batch-create', method: 'POST', endpoint: 'academic/enrollments/batch', permissions: ['enrollment.manage', 'crm.order.read'], permissionAlternatives: [['enrollment.batchCreate'], ['enrollment.manage', 'crm.order.read']], fields: ['classId', 'studentIds', 'rows', 'couponId', 'couponClaimToken', 'referrerStudentId'], required: ['classId'] },
    { id: 'makeup.assign', name: 'makeup-assign', method: 'POST', endpoint: 'academic/makeup/assign', permissions: ['session.move'], fields: ['enrollmentId', 'sourceSessionId', 'targetClassId', 'targetSessionId', 'kind', 'reason', 'enrollmentVersion', 'sourceSessionVersion', 'targetSessionVersion'], required: ['enrollmentId', 'sourceSessionId', 'targetClassId', 'targetSessionId', 'kind', 'reason', 'enrollmentVersion', 'sourceSessionVersion', 'targetSessionVersion'] },
    { id: 'makeup.waive', name: 'makeup-waive', method: 'POST', endpoint: 'academic/makeup/waive', permissions: ['session.move'], fields: ['enrollmentId', 'sourceSessionId', 'reason', 'enrollmentVersion', 'sourceSessionVersion'], required: ['enrollmentId', 'sourceSessionId', 'reason', 'enrollmentVersion', 'sourceSessionVersion'] },
    { id: 'makeup.cancel', name: 'makeup-cancel', method: 'POST', endpoint: 'academic/makeup/{adjustmentId}/cancel', path: ['adjustmentId'], permissions: ['session.move'], fields: ['reason', 'expectedVersion'], required: ['reason', 'expectedVersion'] },
    { id: 'student.create', name: 'student-create', method: 'POST', endpoint: 'academic/students', permissions: ['student.write', 'student.create'], permissionGroups: [['student.write', 'student.create']], fields: ['name', 'grade', 'phone', 'campusId', 'originLeadId'], required: ['name', 'phone'] },
    { id: 'student.update', name: 'student-update', method: 'PUT', endpoint: 'academic/students/{studentId}', path: ['studentId'], permissions: ['student.write', 'student.update'], permissionGroups: [['student.write', 'student.update']], fields: ['name', 'grade', 'phone', 'campusId', 'originLeadId', 'expectedVersion'], required: ['expectedVersion'] },
    { id: 'student.communicate', name: 'student-communicate', method: 'POST', endpoint: 'academic/students/{studentId}/communications', path: ['studentId'], permissions: ['student.communicate'], fields: ['classId', 'channel', 'content', 'nextFollowAt'], required: ['classId', 'channel', 'content'] },
    { id: 'enrollment.batchTransfer', name: 'enrollment-batch-transfer', method: 'POST', endpoint: 'academic/enrollments/batch-transfer', permissions: ['enrollment.manage'], permissionGroups: [['enrollment.manage', 'enrollment.transfer', 'enrollment.batchTransfer']], fields: ['items', 'targetClassId', 'reason'], required: ['items', 'targetClassId', 'reason'] },
];
function fail(code, message) { throw auth.failure(code, message); }
const id = (value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$/.test(value);
function instant(value) { if (typeof value !== 'string')
    return false; const m = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d{1,9})?(Z|([+-])(\d\d):(\d\d))$/.exec(value); if (!m)
    return false; const day = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])), oh = +(m[9] || 0), om = +(m[10] || 0); return day.toISOString().slice(0, 10) === value.slice(0, 10) && +m[4] < 24 && +m[5] < 60 && +m[6] < 60 && oh <= 18 && om < 60 && (oh !== 18 || om === 0) && Number.isFinite(Date.parse(value)); }
function date(value) { if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value))
    return false; const parsed = new Date(value + 'T00:00:00Z'); return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value; }
function slot(value) { const match = typeof value === 'string' && /^(\d{2}):(\d{2})[–—-](\d{2}):(\d{2})$/.exec(value); if (!match)
    return false; const start = Number(match[1]) * 60 + Number(match[2]), end = match[3] === '24' && match[4] === '00' ? 1440 : Number(match[3]) * 60 + Number(match[4]); return Number(match[1]) < 24 && Number(match[2]) < 60 && (Number(match[3]) < 24 || Number(match[3]) === 24 && Number(match[4]) === 0) && Number(match[4]) < 60 && start < end; }
function money(value) {
    if (typeof value === 'string')
        return /^(?:0|[1-9]\d{0,15})(?:\.\d{1,2})?$/.test(value);
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 0)
        return false;
    // Validate the shortest decimal JSON representation instead of multiplying a
    // binary float by 100; values such as 0.29 are exact to cents in JSON, while
    // 0.1 + 0.2 remains visibly over-precise and is rejected without rounding.
    let decimal = String(value), match = /^(\d+)(?:\.(\d+))?(?:e([+-]?\d+))?$/i.exec(decimal);
    if (!match)
        return false;
    let whole = match[1], fraction = match[2] || '', exponent = Number(match[3] || 0);
    if (exponent > 0) {
        const digits = whole + fraction, point = whole.length + exponent;
        whole = digits.slice(0, point) || '0';
        fraction = digits.slice(point);
        if (point > digits.length)
            whole += '0'.repeat(point - digits.length);
    }
    else if (exponent < 0) {
        const point = whole.length + exponent;
        if (point > 0) {
            fraction = whole.slice(point) + fraction;
            whole = whole.slice(0, point);
        }
        else {
            fraction = '0'.repeat(-point) + whole + fraction;
            whole = '0';
        }
    }
    whole = whole.replace(/^0+(?=\d)/, '');
    return whole.length <= 16 && /^(?:0|[1-9]\d{0,15})$/.test(whole) && fraction.length <= 2;
}
function exact(value, allowed, label) { if (!value || typeof value !== 'object' || Array.isArray(value) || ![Object.prototype, null].includes(Object.getPrototypeOf(value)) || Object.getOwnPropertySymbols(value).length || Object.keys(value).some(k => !allowed.includes(k)))
    fail('INVALID_ACADEMIC_INPUT', `${label} 含无效字段或格式`); }
function required(value, keys, label) { for (const k of keys)
    if (!Object.hasOwn(value, k))
        fail('INVALID_ACADEMIC_INPUT', `${label} 缺少 ${k}`); }
function text(value, key, max, optional = false) { if (optional && (value === undefined || value === null))
    return; if (typeof value !== 'string' || (!optional && !value.trim()) || value.length > max || /[\u0000-\u001f\u007f]/.test(value))
    fail('INVALID_ACADEMIC_INPUT', `${key} 无效`); }
function pos(value, key, max = Number.MAX_SAFE_INTEGER) { if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 1 || value > max)
    fail('INVALID_ACADEMIC_INPUT', `${key} 无效`); }
const attendanceStates = ['attended', 'late', 'left_early', 'leave', 'absent'];
function course(value, update = false) {
    const keys = update ? ['name', 'year', 'expectedVersion', 'lessons', 'price'] : ['campusId', 'name', 'courseType', 'grade', 'subject', 'price', 'lessons', 'metadata'];
    exact(value, keys, 'course');
    required(value, update ? ['name', 'expectedVersion', 'lessons'] : ['name', 'courseType', 'grade', 'subject', 'price', 'lessons'], 'course');
    text(value.name, 'name', 100);
    if (update) {
        pos(value.expectedVersion, 'expectedVersion');
        if (value.year !== undefined && value.year !== null)
            pos(value.year, 'year', 2100);
        if (value.year !== undefined && value.year !== null && value.year < 2000)
            fail('INVALID_ACADEMIC_INPUT', 'year 无效');
    }
    else {
        text(value.campusId, 'campusId', 120, true);
        for (const k of ['grade', 'subject'])
            text(value[k], k, 40);
        if (!['onsite', 'live'].includes(value.courseType) || !money(value.price))
            fail('INVALID_ACADEMIC_INPUT', 'courseType 或 price 无效');
    }
    if (!Array.isArray(value.lessons) || !value.lessons.length || value.lessons.length > 200)
        fail('INVALID_ACADEMIC_INPUT', 'lessons 须为 1–200 项');
    for (const lesson of value.lessons) {
        exact(lesson, ['title'], 'lesson');
        text(lesson.title, 'lesson.title', update || value.metadata ? 30 : 100);
    }
    if (!update && value.metadata != null) {
        const m = value.metadata;
        exact(m, ['year', 'term', 'classType', 'courseKind', 'format'], 'metadata');
        required(m, ['year', 'term', 'courseKind', 'format'], 'metadata');
        pos(m.year, 'metadata.year', 2100);
        if (m.year < 2000)
            fail('INVALID_ACADEMIC_INPUT', 'metadata.year 无效');
        text(m.term, 'metadata.term', 40);
        text(m.classType, 'metadata.classType', 40, true);
        if (!['long_term', 'short_term', 'trial'].includes(m.courseKind) || !['face', 'intention', 'one_to_one', 'one_to_many'].includes(m.format))
            fail('INVALID_ACADEMIC_INPUT', 'metadata 枚举无效');
    }
}
function fees(value) { if (value == null)
    return; exact(value, ['courseFee', 'materialFee', 'courseRefundRule', 'refundLessonCount', 'materialRefundRule'], 'fees'); required(value, ['courseFee', 'materialFee', 'courseRefundRule', 'materialRefundRule'], 'fees'); if (!money(value.courseFee) || !money(value.materialFee) || !['unused_lessons', 'first_n_full', 'last_n_none'].includes(value.courseRefundRule) || !['after_enrollment', 'after_start'].includes(value.materialRefundRule))
    fail('INVALID_ACADEMIC_INPUT', 'fees 无效'); if (value.refundLessonCount != null)
    pos(value.refundLessonCount, 'refundLessonCount', 200); if ((value.courseRefundRule === 'unused_lessons') !== (value.refundLessonCount == null))
    fail('INVALID_ACADEMIC_INPUT', 'refundLessonCount 与 courseRefundRule 不匹配'); }
function oneClass(value) { exact(value, ['courseId', 'campusId', 'teacherId', 'name', 'room', 'capacity', 'durationMinutes', 'schedule', 'fees'], 'class'); required(value, ['courseId', 'campusId', 'teacherId', 'name', 'capacity', 'durationMinutes', 'schedule'], 'class'); for (const k of ['courseId', 'campusId', 'teacherId'])
    if (!id(value[k]))
        fail('INVALID_ACADEMIC_INPUT', `${k} 无效`); text(value.name, 'name', 100); text(value.room, 'room', 100, true); pos(value.capacity, 'capacity', 10000); pos(value.durationMinutes, 'durationMinutes', 1440); if (!Array.isArray(value.schedule) || !value.schedule.length || value.schedule.length > 200)
    fail('INVALID_ACADEMIC_INPUT', 'schedule 须为 1–200 项'); for (const row of value.schedule) {
    exact(row, ['lessonId', 'startsAt'], 'schedule');
    required(row, ['lessonId', 'startsAt'], 'schedule');
    if (!id(row.lessonId) || !instant(row.startsAt))
        fail('INVALID_ACADEMIC_INPUT', 'schedule 中 lessonId 或 startsAt 无效');
} fees(value.fees); }
function validate(name, input) {
    const op = operations.find(x => x.name === name);
    if (!op)
        fail('INVALID_COMMAND', '教务命令无效');
    exact(input, op.fields, name);
    required(input, op.required, name);
    if (name === 'student-create') {
        text(input.name, 'name', 80);
        text(input.grade, 'grade', 40, true);
        text(input.phone, 'phone', 20);
        if (!/^1[3-9][0-9]{9}$/.test(input.phone.trim()))
            fail('INVALID_ACADEMIC_INPUT', 'phone 无效');
        text(input.campusId, 'campusId', 120, true);
        text(input.originLeadId, 'originLeadId', 120, true);
    }
    if (name === 'student-update') {
        text(input.name, 'name', 80, true);
        text(input.grade, 'grade', 40, true);
        text(input.phone, 'phone', 20, true);
        if (input.phone != null && !/^1[3-9][0-9]{9}$/.test(input.phone.trim()))
            fail('INVALID_ACADEMIC_INPUT', 'phone 无效');
        text(input.campusId, 'campusId', 120, true);
        text(input.originLeadId, 'originLeadId', 120, true);
        pos(input.expectedVersion, 'expectedVersion');
    }
    if (name === 'student-communicate') {
        if (!id(input.classId) || !['phone', 'wecom', 'text'].includes(input.channel))
            fail('INVALID_ACADEMIC_INPUT', 'classId 或 channel 无效');
        text(input.content, 'content', 2000);
        if (input.nextFollowAt != null && !instant(input.nextFollowAt))
            fail('INVALID_ACADEMIC_INPUT', 'nextFollowAt 须为有效的带时区时间');
    }
    if (['enrollment-cancel', 'enrollment-withdraw', 'checkout-cancel'].includes(name)) {
        text(input.reason, 'reason', 500);
        pos(input.expectedVersion, 'expectedVersion');
    }
    if (name === 'enrollment-batch-transfer') {
        if (!Array.isArray(input.items) || !input.items.length || input.items.length > 500 || !id(input.targetClassId))
            fail('INVALID_ACADEMIC_INPUT', 'items 或 targetClassId 无效');
        text(input.reason, 'reason', 500);
        const seen = new Set();
        for (const item of input.items) {
            exact(item, ['enrollmentId', 'expectedVersion'], 'transfer item');
            required(item, ['enrollmentId', 'expectedVersion'], 'transfer item');
            if (!id(item.enrollmentId) || typeof item.expectedVersion !== 'number' || !Number.isSafeInteger(item.expectedVersion) || item.expectedVersion < 1 || seen.has(item.enrollmentId))
                fail('INVALID_ACADEMIC_INPUT', '报名编号重复或版本无效');
            seen.add(item.enrollmentId);
        }
    }
    if (name === 'enrollment-batch-create') {
        if (!id(input.classId) || (input.studentIds === undefined) === (input.rows === undefined))
            fail('INVALID_ACADEMIC_INPUT', 'classId 与 studentIds/rows 参数无效');
        if (input.couponId != null && !id(input.couponId))
            fail('INVALID_ACADEMIC_INPUT', 'couponId 无效');
        if (input.referrerStudentId != null && !id(input.referrerStudentId))
            fail('INVALID_ACADEMIC_INPUT', 'referrerStudentId 无效');
        const token = (value) => { if (typeof value !== 'string' || value.length < 24 || value.length > 128 || /[\u0000-\u001f\u007f]/.test(value))
            fail('INVALID_ACADEMIC_INPUT', 'couponClaimToken 无效'); };
        if (input.couponClaimToken != null)
            token(input.couponClaimToken);
        if (input.couponId != null && input.couponClaimToken != null)
            fail('INVALID_ACADEMIC_INPUT', '优惠券编号与兑换码不能同时使用');
        if (input.studentIds !== undefined && (!Array.isArray(input.studentIds) || !input.studentIds.length || input.studentIds.length > 500 || input.studentIds.some(x => !id(x)) || new Set(input.studentIds).size !== input.studentIds.length))
            fail('INVALID_ACADEMIC_INPUT', 'studentIds 须为1–500个不重复学生编号');
        if (input.rows !== undefined) {
            if (!Array.isArray(input.rows) || !input.rows.length || input.rows.length > 500)
                fail('INVALID_ACADEMIC_INPUT', 'rows 须为1–500项');
            const seen = new Set();
            for (const row of input.rows) {
                exact(row, ['opportunityId', 'studentId', 'studentDraft', 'couponId', 'couponClaimToken', 'referrerStudentId'], 'enrollment row');
                if ((row.studentId === undefined) === (row.studentDraft === undefined))
                    fail('INVALID_ACADEMIC_INPUT', '报名行必须且只能填写已有学生或新生资料');
                for (const key of ['opportunityId', 'studentId', 'couponId', 'referrerStudentId'])
                    if (row[key] != null && !id(row[key]))
                        fail('INVALID_ACADEMIC_INPUT', `${key} 无效`);
                if (row.studentId !== undefined) {
                    if (!id(row.studentId) || seen.has(row.studentId))
                        fail('INVALID_ACADEMIC_INPUT', '学生编号无效或重复');
                    seen.add(row.studentId);
                }
                if (row.studentDraft !== undefined) {
                    const draft = row.studentDraft;
                    exact(draft, ['name', 'phone', 'grade', 'campusId', 'originLeadId'], 'studentDraft');
                    required(draft, ['name', 'phone'], 'studentDraft');
                    text(draft.name, 'studentDraft.name', 80);
                    text(draft.phone, 'studentDraft.phone', 20);
                    if (!/^1[3-9][0-9]{9}$/.test(draft.phone.trim()))
                        fail('INVALID_ACADEMIC_INPUT', 'studentDraft.phone 无效');
                    for (const key of ['grade', 'campusId', 'originLeadId']) {
                        text(draft[key], `studentDraft.${key}`, key === 'grade' ? 40 : 120, true);
                        if (draft[key] != null && key !== 'grade' && !id(draft[key]))
                            fail('INVALID_ACADEMIC_INPUT', `studentDraft.${key} 无效`);
                    }
                }
                if (row.couponClaimToken != null)
                    token(row.couponClaimToken);
                const effectiveCoupon = row.couponId ?? input.couponId, effectiveToken = row.couponClaimToken ?? input.couponClaimToken;
                if (effectiveCoupon != null && effectiveToken != null)
                    fail('INVALID_ACADEMIC_INPUT', '单条报名不能同时使用优惠券和兑换码');
            }
        }
    }
    if (name === 'makeup-assign') {
        for (const field of ['enrollmentId', 'sourceSessionId', 'targetClassId', 'targetSessionId'])
            if (!id(input[field]))
                fail('INVALID_ACADEMIC_INPUT', `${field} 无效`);
        if (!['makeup', 'temporary'].includes(input.kind))
            fail('INVALID_ACADEMIC_INPUT', 'kind 无效');
        text(input.reason, 'reason', 500);
        for (const field of ['enrollmentVersion', 'sourceSessionVersion', 'targetSessionVersion'])
            pos(input[field], field);
    }
    if (name === 'makeup-waive') {
        for (const field of ['enrollmentId', 'sourceSessionId'])
            if (!id(input[field]))
                fail('INVALID_ACADEMIC_INPUT', `${field} 无效`);
        text(input.reason, 'reason', 500);
        for (const field of ['enrollmentVersion', 'sourceSessionVersion'])
            pos(input[field], field);
    }
    if (name === 'makeup-cancel') {
        text(input.reason, 'reason', 500);
        pos(input.expectedVersion, 'expectedVersion');
    }
    if (['course-create', 'course-update'].includes(name))
        course(input, name === 'course-update');
    if (name === 'course-update' && input.price != null && !money(input.price))
        fail('INVALID_ACADEMIC_INPUT', 'price 无效');
    if (name === 'course-publish')
        pos(input.expectedVersion, 'expectedVersion');
    if (name === 'course-status') {
        if (typeof input.active !== 'boolean')
            fail('INVALID_ACADEMIC_INPUT', 'active 须为布尔值');
        pos(input.expectedVersion, 'expectedVersion');
    }
    if (name === 'class-create' || name === 'class-batch-create') {
        if (name === 'class-create')
            oneClass(input);
        else {
            if (!Array.isArray(input.classes) || !input.classes.length || input.classes.length > 50)
                fail('INVALID_ACADEMIC_INPUT', 'classes 须为 1–50 项');
            input.classes.forEach(oneClass);
            if (input.classes.reduce((count, row) => count + row.schedule.length, 0) > 400)
                fail('INVALID_ACADEMIC_INPUT', '单批排课总课次不能超过400次');
        }
    }
    if (name === 'class-update') {
        text(input.name, 'name', 100);
        text(input.room, 'room', 100, true);
        if (!id(input.teacherId))
            fail('INVALID_ACADEMIC_INPUT', 'teacherId 无效');
        pos(input.capacity, 'capacity', 10000);
        pos(input.expectedVersion, 'expectedVersion');
    }
    if (name === 'class-schedule') {
        pos(input.expectedVersion, 'expectedVersion');
        if (!Array.isArray(input.sessions) || input.sessions.length > 200)
            fail('INVALID_ACADEMIC_INPUT', 'sessions 须为 0–200 项');
        const ids = new Set(), lessons = new Set();
        for (const row of input.sessions) {
            exact(row, ['sessionId', 'lessonId', 'startsAt'], 'schedule row');
            required(row, ['lessonId', 'startsAt'], 'schedule row');
            if (!id(row.lessonId) || !instant(row.startsAt) || lessons.has(row.lessonId) || (row.sessionId != null && !id(row.sessionId)) || (row.sessionId != null && ids.has(row.sessionId)))
                fail('INVALID_ACADEMIC_INPUT', 'sessions 中课次编号、讲次或时间无效/重复');
            if (row.sessionId != null)
                ids.add(row.sessionId);
            lessons.add(row.lessonId);
        }
    }
    if (name === 'class-relocate') {
        if (!Array.isArray(input.classes) || !input.classes.length || input.classes.length > 100 || !id(input.campusId))
            fail('INVALID_ACADEMIC_INPUT', 'classes 或 campusId 无效');
        text(input.room, 'room', 100);
        const seen = new Set();
        for (const row of input.classes) {
            exact(row, ['classId', 'expectedVersion'], 'relocate class');
            required(row, ['classId', 'expectedVersion'], 'relocate class');
            if (!id(row.classId) || seen.has(row.classId))
                fail('INVALID_ACADEMIC_INPUT', '班级编号无效或重复');
            seen.add(row.classId);
            pos(row.expectedVersion, 'expectedVersion');
        }
    }
    if (name === 'session-move') {
        if (!instant(input.startsAt))
            fail('INVALID_ACADEMIC_INPUT', 'startsAt 须含时区且有效');
        text(input.reason, 'reason', 500);
        pos(input.expectedVersion, 'expectedVersion');
    }
    if (name === 'session-substitute') {
        if (!id(input.teacherId))
            fail('INVALID_ACADEMIC_INPUT', 'teacherId 无效');
        text(input.reason, 'reason', 500);
        pos(input.expectedVersion, 'expectedVersion');
    }
    if (name === 'session-record') {
        text(input.content, 'content', 10000);
        pos(input.expectedVersion, 'expectedVersion');
    }
    if (name === 'session-reschedule-request') {
        if (!id(input.classId) || !id(input.sessionId) || !date(input.date) || !slot(input.time))
            fail('INVALID_ACADEMIC_INPUT', 'classId、sessionId、日期或时段无效');
        text(input.reason, 'reason', 500);
        pos(input.expectedVersion, 'expectedVersion');
    }
    if (name === 'session-reschedule-list') {
        if (input.status !== undefined && !['pending', 'approved', 'rejected'].includes(input.status))
            fail('INVALID_ACADEMIC_INPUT', 'status 无效');
        for (const field of ['page', 'size'])
            if (input[field] !== undefined)
                pos(input[field], field, field === 'page' ? 100000 : 100);
    }
    if (name === 'session-reschedule-review') {
        if (!['approve', 'reject'].includes(input.decision))
            fail('INVALID_ACADEMIC_INPUT', 'decision 只能为 approve 或 reject');
        text(input.note, 'note', 500, true);
        pos(input.expectedVersion, 'expectedVersion');
        if (input.decision === 'reject' && (!input.note || !input.note.trim()))
            fail('INVALID_ACADEMIC_INPUT', 'reject 必须填写 note');
    }
    if (name === 'session-attendance-exceptions') {
        if (!id(input.classId) || !Array.isArray(input.exceptions) || input.exceptions.length > 1000)
            fail('INVALID_ACADEMIC_INPUT', 'classId 或 exceptions 无效');
        const seen = new Set();
        for (const exception of input.exceptions) {
            exact(exception, ['studentId', 'status', 'note'], 'attendance exception');
            required(exception, ['studentId', 'status'], 'attendance exception');
            if (!id(exception.studentId) || !['late', 'left_early', 'leave', 'absent'].includes(exception.status) || seen.has(exception.studentId))
                fail('INVALID_ACADEMIC_INPUT', 'exceptions 中学生编号重复或状态无效');
            seen.add(exception.studentId);
            text(exception.note, 'note', 500, true);
        }
    }
    if (name === 'attendance-record' || name === 'attendance-correct') {
        if (!id(input.classId) || !attendanceStates.includes(input.status))
            fail('INVALID_ACADEMIC_INPUT', 'classId 或 status 无效');
        text(input.note, 'note', name === 'attendance-correct' ? 1000 : 500, true);
        if (name === 'attendance-correct') {
            text(input.reason, 'reason', 500);
            pos(input.expectedVersion, 'expectedVersion');
        }
    }
    if (name === 'attendance-batch') {
        if (!id(input.classId) || !Array.isArray(input.rows) || !input.rows.length || input.rows.length > 100)
            fail('INVALID_ACADEMIC_INPUT', 'classId 或 rows 无效');
        const seen = new Set();
        for (const row of input.rows) {
            exact(row, ['studentId', 'status', 'note'], 'attendance row');
            required(row, ['studentId', 'status'], 'attendance row');
            if (!id(row.studentId) || !attendanceStates.includes(row.status) || seen.has(row.studentId))
                fail('INVALID_ACADEMIC_INPUT', 'rows 中学生编号无效或重复');
            seen.add(row.studentId);
            text(row.note, 'note', 500, true);
        }
    }
    if (name === 'class-enroll') {
        if (!id(input.studentId) || !id(input.orderId))
            fail('INVALID_ACADEMIC_INPUT', 'studentId 或 orderId 无效');
    }
    return input;
}
function allowed(op, permissions = []) { const granted = new Set(Array.isArray(permissions) ? permissions : []); if (op.permissionAlternatives)
    return op.permissionAlternatives.some(alternative => alternative.every(permission => granted.has(permission))); const groups = op.permissionGroups || op.permissions.map(p => [p]); return groups.every(group => group.some(p => granted.has(p))); }
async function run(args, client, identity) {
    const [name, ...rest] = args;
    const op = operations.find(x => x.name === name);
    if (!op)
        fail('INVALID_ARGUMENT', '用法：academic course-create|course-update|course-publish|course-status|class-create|class-batch-create|class-update|class-schedule|class-relocate|session-move|session-substitute|session-record|session-reschedule-request|session-reschedule-list|session-reschedule-review|session-attendance-exceptions|attendance-record|attendance-batch|attendance-correct|class-enroll|student-create|student-update|student-communicate|enrollment-batch-create|enrollment-batch-transfer|makeup-assign|makeup-waive|makeup-cancel|enrollment-cancel|enrollment-withdraw|checkout-cancel ...');
    if (!allowed(op, identity?.permissions))
        fail('FORBIDDEN', `当前员工缺少所需权限 ${op.permissions.join(' + ')}`);
    if (op.readOnly) {
        const flags = {};
        for (let index = 0; index < rest.length; index += 2) {
            const flag = rest[index], value = rest[index + 1];
            if (!['--input'].includes(flag) || !value || Object.hasOwn(flags, flag))
                fail('INVALID_ARGUMENT', '用法：session-reschedule-list [--input <JSON文件>]');
            flags[flag] = value;
        }
        const query = validate(name, flags['--input'] ? business.read(flags['--input']) : {});
        const params = new URLSearchParams();
        for (const [field, value] of Object.entries(query))
            params.set(field, String(value));
        return client.request(op.endpoint + (params.size ? `?${params}` : ''));
    }
    let targets = [], tail = rest;
    if (op.path) {
        targets = rest.splice(0, op.path.length);
        if (targets.some(x => !id(x)))
            fail('INVALID_ARGUMENT', '目标编号无效');
    }
    const f = {};
    for (let i = 0; i < tail.length; i += 2) {
        const k = tail[i], v = tail[i + 1];
        if (!['--input', '--key'].includes(k) || !v || f[k])
            fail('INVALID_ARGUMENT', '命令需要 --input <JSON文件> 和 --key <幂等键>');
        f[k] = v;
    }
    if (!f['--input'] || !f['--key'] || !/^[A-Za-z0-9_-]{16,100}$/.test(f['--key']))
        fail('INVALID_ARGUMENT', '必须提供符合 Java 教务 API 格式的幂等键（16–100 位字母、数字、下划线或连字符）');
    const body = validate(name, business.read(f['--input']));
    let endpoint = op.endpoint;
    for (const target of targets)
        endpoint = endpoint.replace(/\{[^}]+\}/, encodeURIComponent(target));
    return client.request(endpoint, { method: op.method, idempotencyKey: f['--key'], body });
}
module.exports = { operations, validate, run, allowed };
