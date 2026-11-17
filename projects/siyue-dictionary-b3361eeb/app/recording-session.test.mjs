import assert from 'node:assert/strict';
import test from 'node:test';
import { AudioCapture, RecordingAttachments, MAX_RECORDING_BYTES, MAX_RECORDING_MS } from './recording-session.ts';
import { composeSurvey, SURVEY_QUESTIONS } from './survey-content.ts';

const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const flush = async () => { for (let index = 0; index < 12; index++) await Promise.resolve(); };
function clock() {
  const timers = new Set();
  return {
    timers,
    setTimer(callback, delay) { const timer = { callback, delay }; timers.add(timer); return timer; },
    clearTimer(timer) { timers.delete(timer); },
    fire(delay) { const timer = [...timers].find(timer => timer.delay === delay); assert.ok(timer, `missing timer ${delay}`); timers.delete(timer); timer.callback(); },
  };
}
function stream() { const tracks = [{ stopped: false, stop() { this.stopped = true; } }, { stopped: false, stop() { this.stopped = true; } }]; return { getTracks: () => tracks, tracks }; }
class Recorder {
  state = 'inactive'; mimeType = 'audio/webm'; ondataavailable = null; onstop = null; onerror = null;
  start() { this.state = 'recording'; }
  data(blob) { this.ondataavailable?.({ data: blob }); }
  stop() { this.state = 'inactive'; queueMicrotask(() => this.onstop?.()); }
}
function captureFixture(getStream) {
  const scheduler = clock(); const states = []; const blobs = []; const device = stream(); const recorder = new Recorder();
  const capture = new AudioCapture(state => states.push(state), blob => blobs.push(blob), {
    available: () => true, getUserMedia: getStream ?? (() => Promise.resolve(device)), createRecorder: () => recorder,
    now: () => 0, setTimer: scheduler.setTimer, clearTimer: scheduler.clearTimer,
  });
  return { capture, states, blobs, device, recorder, scheduler };
}
function attachmentFixture() {
  const scheduler = clock(); const posts = []; const deleted = []; const revoked = []; let items = [], key = 0;
  const attachments = new RecordingAttachments(value => { items = value; }, {
    fetch: (url, options) => {
      if (options?.method === 'POST') { const request = deferred(); posts.push({ ...request, options }); return request.promise; }
      if (options?.method === 'DELETE') { deleted.push(url); return Promise.resolve(new Response('{}')); }
      throw new Error(`unexpected fetch ${url}`);
    },
    createUrl: () => `blob:test-${key}`, revokeUrl: url => revoked.push(url), makeKey: () => `key-${++key}`,
    setTimer: scheduler.setTimer, clearTimer: scheduler.clearTimer,
  });
  const reply = (index, id) => posts[index].resolve(new Response(JSON.stringify({ id, mimeType: 'audio/webm', size: 5 }), { status: 200 }));
  return { attachments, posts, deleted, revoked, scheduler, reply, get items() { return items; } };
}
const audio = () => new Blob(['audio'], { type: 'audio/webm' });

test('cancel pending permission stops a late stream and never records', async () => {
  const permission = deferred(); const f = captureFixture(() => permission.promise);
  const start = f.capture.start(); f.capture.cancel(); const late = stream(); permission.resolve(late); await start;
  assert.ok(late.tracks.every(track => track.stopped)); assert.equal(f.recorder.state, 'inactive'); assert.equal(f.blobs.length, 0); assert.equal(f.scheduler.timers.size, 0);
});
test('unmount during permission also releases a stream arriving later', async () => {
  const permission = deferred(); const f = captureFixture(() => permission.promise);
  const start = f.capture.start(); f.capture.dispose(); const count = f.states.length; const late = stream(); permission.resolve(late); await start;
  assert.ok(late.tracks.every(track => track.stopped)); assert.equal(f.states.length, count); assert.equal(f.blobs.length, 0);
});
test('permission timeout is recoverable and releases a later stream', async () => {
  const permission = deferred(); const f = captureFixture(() => permission.promise); const start = f.capture.start();
  f.scheduler.fire(20_000); assert.match(f.states.at(-1).error, /超时/); const late = stream(); permission.resolve(late); await start;
  assert.ok(late.tracks.every(track => track.stopped)); assert.equal(f.states.at(-1).phase, 'idle');
});
test('stop returns raw audio, stops every track, and clears recording timers', async () => {
  const f = captureFixture(); await f.capture.start(); f.recorder.data(audio()); f.capture.stop(); await flush();
  assert.equal(f.blobs.length, 1); assert.equal(f.blobs[0].type, 'audio/webm'); assert.equal(await f.blobs[0].text(), 'audio');
  assert.ok(f.device.tracks.every(track => track.stopped)); assert.equal(f.scheduler.timers.size, 0); assert.equal(f.states.at(-1).phase, 'idle');
});
test('cancel recorded audio prevents upload/recording callback', async () => {
  const f = captureFixture(); await f.capture.start(); f.recorder.data(audio()); f.capture.cancel(); await flush();
  assert.equal(f.blobs.length, 0); assert.ok(f.device.tracks.every(track => track.stopped)); assert.equal(f.scheduler.timers.size, 0);
});
test('two-minute limit stops recording automatically', async () => {
  const f = captureFixture(); await f.capture.start(); f.recorder.data(audio()); f.scheduler.fire(MAX_RECORDING_MS); await flush();
  assert.equal(f.blobs.length, 1); assert.ok(f.device.tracks.every(track => track.stopped)); assert.equal(f.scheduler.timers.size, 0);
});
test('audio larger than eight MiB is rejected without keeping microphone open', async () => {
  const f = captureFixture(); await f.capture.start(); f.recorder.data(new Blob([new Uint8Array(MAX_RECORDING_BYTES + 1)], { type: 'audio/webm' })); await flush();
  assert.equal(f.blobs.length, 0); assert.match(f.states.at(-1).error, /8 MiB/); assert.ok(f.device.tracks.every(track => track.stopped)); assert.equal(f.scheduler.timers.size, 0);
});
test('disposing while upload returns late deletes the orphan and revokes preview', async () => {
  const f = attachmentFixture(); f.attachments.add(audio()); f.attachments.dispose();
  assert.equal(f.posts[0].options.signal.aborted, true); f.reply(0, 'late-id'); await flush();
  assert.deepEqual(f.deleted, ['/api/recordings/late-id']); assert.equal(f.revoked.length, 1); assert.equal(f.scheduler.timers.size, 0);
});
test('removing an uploading recording also deletes a late response', async () => {
  const f = attachmentFixture(); f.attachments.add(audio()); f.attachments.remove(f.items[0].key); f.reply(0, 'removed-id'); await flush();
  assert.equal(f.items.length, 0); assert.deepEqual(f.deleted, ['/api/recordings/removed-id']); assert.equal(f.revoked.length, 1);
});
test('failed upload preserves the original audio and can retry', async () => {
  const f = attachmentFixture(); const original = audio(); f.attachments.add(original); f.posts[0].reject(new TypeError('offline')); await flush();
  assert.equal(f.items[0].status, 'error'); assert.equal(f.items[0].blob, original); assert.equal(f.revoked.length, 0);
  f.attachments.retry(f.items[0].key); assert.equal(f.posts[1].options.body, original); f.reply(1, 'retry-id'); await flush();
  assert.equal(f.items[0].status, 'ready'); assert.equal(f.items[0].recording.id, 'retry-id');
  f.attachments.dispose(); assert.deepEqual(f.deleted, ['/api/recordings/retry-id']);
});
test('upload timeout keeps audio, deletes stale result, and accepts retry result', async () => {
  const f = attachmentFixture(); f.attachments.add(audio()); f.scheduler.fire(60_000); assert.equal(f.items[0].status, 'error');
  f.attachments.retry(f.items[0].key); f.reply(0, 'timed-out-id'); f.reply(1, 'current-id'); await flush();
  assert.equal(f.items[0].recording.id, 'current-id'); assert.deepEqual(f.deleted, ['/api/recordings/timed-out-id']); f.attachments.commit(); f.attachments.dispose();
});
test('commit before unmount keeps submitted server audio and releases local preview', async () => {
  const f = attachmentFixture(); f.attachments.add(audio()); f.reply(0, 'submitted-id'); await flush();
  f.attachments.commit(); f.attachments.dispose(); assert.deepEqual(f.deleted, []); assert.equal(f.revoked.length, 1); assert.equal(f.scheduler.timers.size, 0);
});
test('closing an unsubmitted ready recording deletes it', async () => {
  const f = attachmentFixture(); f.attachments.add(audio()); f.reply(0, 'pending-id'); await flush(); f.attachments.dispose();
  assert.deepEqual(f.deleted, ['/api/recordings/pending-id']);
});
test('recording list accepts at most five attachments', () => {
  const f = attachmentFixture(); for (let index = 0; index < 5; index++) assert.equal(f.attachments.add(audio()), true);
  assert.equal(f.attachments.add(audio()), false); assert.equal(f.posts.length, 5); f.attachments.dispose();
});
test('guided serialization preserves original text, excludes unanswered prompts, derives title from answer', () => {
  const free = '原先填写的内容\n第二行仍然保留'; const answers = ['', '需要和教务反复确认', '', '', '希望少重复录入'];
  const result = composeSurvey(free, answers);
  assert.ok(result.problem.startsWith(free)); assert.ok(result.problem.includes(`问：${SURVEY_QUESTIONS[1]}\n答：${answers[1]}`));
  assert.ok(!result.problem.includes(SURVEY_QUESTIONS[0])); assert.equal(result.title, '原先填写的内容'); assert.equal(result.answeredCount, 2);
  assert.equal(composeSurvey('', ['实际回答内容', '', '', '', '']).title, '实际回答内容'); assert.equal(composeSurvey('', ['', '', '', '', '']).problem, '');
});
test('combined-length calculation includes question labels and never silently truncates answers', () => {
  const free = '字'.repeat(2400); const answer = '另'.repeat(150); const result = composeSurvey(free, [answer, '', '', '', '']);
  assert.ok(result.problem.length > 2500); assert.ok(result.problem.includes(free)); assert.ok(result.problem.endsWith(answer)); assert.equal(result.answerLength, 2550);
});
test('recording context is captured with the draft and survives upload retry unchanged', async () => {
  const f = attachmentFixture(); const context = { questionIndex: 2, question: SURVEY_QUESTIONS[2] };
  f.attachments.add(audio(), context); context.question = '后续变更不能修改已捕获的问题';
  f.posts[0].reject(new TypeError('offline')); await flush();
  assert.deepEqual(f.items[0].guideContext, { questionIndex: 2, question: SURVEY_QUESTIONS[2] });
  f.attachments.retry(f.items[0].key); f.reply(1, 'question-three'); await flush();
  assert.equal(f.items[0].guideContext.question, SURVEY_QUESTIONS[2]); f.attachments.dispose();
});
test('text and recording contexts are explicit, follow recording ID order, and renumber after deletion', async () => {
  const f = attachmentFixture(); const emptyAnswers = ['', '', '', '', ''];
  f.attachments.add(audio());
  f.attachments.add(audio(), { questionIndex: 2, question: SURVEY_QUESTIONS[2] });
  f.attachments.add(audio(), { questionIndex: 4, question: SURVEY_QUESTIONS[4] });
  // Completion order differs from submission order.
  f.reply(2, 'fifth-question'); f.reply(0, 'free-recording'); f.reply(1, 'third-question'); await flush();
  const before = composeSurvey('文字补充仍保留', emptyAnswers, f.items);
  assert.deepEqual(f.items.map(item => item.recording.id), ['free-recording', 'third-question', 'fifth-question']);
  assert.ok(before.problem.includes(`录音2对应提问：${SURVEY_QUESTIONS[2]}`));
  assert.ok(before.problem.includes(`录音3对应提问：${SURVEY_QUESTIONS[4]}`));
  assert.equal(before.title, '文字补充仍保留');
  f.attachments.remove(f.items[0].key);
  const after = composeSurvey('文字补充仍保留', emptyAnswers, f.items);
  assert.deepEqual(f.items.map(item => item.recording.id), ['third-question', 'fifth-question']);
  assert.ok(after.problem.includes(`录音1对应提问：${SURVEY_QUESTIONS[2]}`));
  assert.ok(after.problem.includes(`录音2对应提问：${SURVEY_QUESTIONS[4]}`));
  assert.ok(!after.problem.includes('录音3')); f.attachments.dispose();
});
test('voice-only guidance keeps a voice title and never pretends the question is a transcript', () => {
  const result = composeSurvey('', ['', '', '', '', ''], [{ guideContext: { question: SURVEY_QUESTIONS[2] } }]);
  assert.equal(result.title, '语音反馈'); assert.equal(result.problem, `录音1对应提问：${SURVEY_QUESTIONS[2]}`);
  assert.equal(result.answerLength, 0); assert.equal(result.answeredCount, 0);
  const unguided = composeSurvey('', ['', '', '', '', ''], [{}]);
  assert.equal(unguided.title, '语音反馈'); assert.equal(unguided.problem, '');
});
test('the final length includes recording questions without truncating written answers', () => {
  const free = '字'.repeat(2490); const result = composeSurvey(free, ['', '', '', '', ''], [{ guideContext: { question: SURVEY_QUESTIONS[2] } }]);
  assert.ok(result.problem.length > 2500); assert.ok(result.problem.startsWith(free));
  assert.equal(result.problem.length, free.length + 2 + `录音1对应提问：${SURVEY_QUESTIONS[2]}`.length);
});
