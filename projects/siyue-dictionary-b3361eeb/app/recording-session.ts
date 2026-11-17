import { appPath } from "./app-paths.mjs";

export const MAX_RECORDING_BYTES = 8 * 1024 * 1024;
export const MAX_RECORDING_MS = 120_000;
export const MAX_RECORDINGS = 5;

type Timer = ReturnType<typeof setTimeout>;
export type CaptureState = { phase: "idle" | "requesting" | "recording" | "stopping"; seconds: number; error: string };
type CaptureDependencies = {
  available: () => boolean;
  getUserMedia: () => Promise<MediaStream>;
  createRecorder: (stream: MediaStream) => MediaRecorder;
  now: () => number;
  setTimer: (callback: () => void, delay: number) => Timer;
  clearTimer: (timer: Timer) => void;
};
type CaptureRun = { stream?: MediaStream; recorder?: MediaRecorder; chunks: Blob[]; size: number; startedAt: number; timers: Set<Timer> };

const browserCapture: CaptureDependencies = {
  available: () => typeof navigator !== "undefined" && !!navigator.mediaDevices?.getUserMedia && typeof MediaRecorder !== "undefined",
  getUserMedia: () => navigator.mediaDevices.getUserMedia({ audio: true, video: false }),
  createRecorder: stream => {
    const mimeType = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4", "audio/ogg;codecs=opus", "audio/ogg"].find(type => MediaRecorder.isTypeSupported(type));
    return new MediaRecorder(stream, { ...(mimeType ? { mimeType } : {}), audioBitsPerSecond: 64_000 });
  },
  now: () => Date.now(), setTimer: (callback, delay) => setTimeout(callback, delay), clearTimer: timer => clearTimeout(timer),
};

export class AudioCapture {
  private run: CaptureRun | null = null;
  private disposed = false;
  private state: CaptureState = { phase: "idle", seconds: 0, error: "" };
  constructor(private onState: (state: CaptureState) => void, private onRecording: (blob: Blob) => void, private deps: CaptureDependencies = browserCapture) {}
  private emit(update: Partial<CaptureState>) { this.state = { ...this.state, ...update }; if (!this.disposed) this.onState(this.state); }
  private active(run: CaptureRun) { return !this.disposed && this.run === run; }
  private stopTracks(stream?: MediaStream) { stream?.getTracks().forEach(track => track.stop()); }
  private clearTimers(run: CaptureRun) { run.timers.forEach(timer => this.deps.clearTimer(timer)); run.timers.clear(); }
  private timer(run: CaptureRun, callback: () => void, delay: number) {
    const timer = this.deps.setTimer(() => { run.timers.delete(timer); if (this.active(run)) callback(); }, delay);
    run.timers.add(timer); return timer;
  }
  private release(run: CaptureRun) {
    this.clearTimers(run);
    if (this.run === run) this.run = null;
    if (run.recorder) {
      run.recorder.ondataavailable = null; run.recorder.onstop = null; run.recorder.onerror = null;
      try { if (run.recorder.state !== "inactive") run.recorder.stop(); } catch { /* Tracks are stopped independently below. */ }
    }
    this.stopTracks(run.stream);
  }
  private fail(run: CaptureRun, message: string) { if (!this.active(run)) return; this.release(run); this.emit({ phase: "idle", error: message }); }
  get busy() { return this.run !== null; }
  async start() {
    if (this.disposed || this.run) return;
    if (!this.deps.available()) { this.emit({ error: "当前浏览器不能录音，请继续打字，或用支持麦克风的浏览器打开。" }); return; }
    const run: CaptureRun = { chunks: [], size: 0, startedAt: 0, timers: new Set() };
    this.run = run; this.emit({ phase: "requesting", seconds: 0, error: "" });
    const permissionTimer = this.timer(run, () => this.fail(run, "等待麦克风超时，请允许麦克风后重试；也可以继续打字。"), 20_000);
    try {
      const stream = await this.deps.getUserMedia();
      // getUserMedia cannot be aborted: release any stream arriving after close or cancel.
      if (!this.active(run)) { this.stopTracks(stream); return; }
      this.deps.clearTimer(permissionTimer); run.timers.delete(permissionTimer); run.stream = stream;
      const recorder = this.deps.createRecorder(stream); run.recorder = recorder;
      recorder.ondataavailable = event => {
        if (!this.active(run) || !event.data.size) return;
        run.size += event.data.size;
        if (run.size > MAX_RECORDING_BYTES) { this.fail(run, "这段录音超过 8 MiB，请缩短后重新录制。文字内容仍保留。" ); return; }
        run.chunks.push(event.data);
      };
      recorder.onerror = () => this.fail(run, "录音中断，请检查麦克风后重试；也可以继续打字。" );
      recorder.onstop = () => {
        if (!this.active(run)) return;
        const mimeType = recorder.mimeType || run.chunks[0]?.type || "";
        const blob = new Blob(run.chunks, { type: mimeType });
        if (!blob.size) { this.fail(run, "没有录到声音，请重新录制。" ); return; }
        if (!/^audio\//i.test(mimeType)) { this.fail(run, "当前浏览器的录音格式不受支持，请换一个浏览器或继续打字。" ); return; }
        this.release(run); this.emit({ phase: "idle", error: "" }); this.onRecording(blob);
      };
      recorder.start(1000); run.startedAt = this.deps.now(); this.emit({ phase: "recording" });
      const tick = () => { this.emit({ seconds: Math.min(120, Math.floor((this.deps.now() - run.startedAt) / 1000)) }); this.timer(run, tick, 1000); };
      this.timer(run, tick, 1000);
      this.timer(run, () => this.stop(), MAX_RECORDING_MS);
    } catch (error) {
      const name = error && typeof error === "object" && "name" in error ? String(error.name) : "";
      const message = name === "NotAllowedError" || name === "SecurityError" ? "麦克风未获允许，请在浏览器中开启权限；也可以继续打字。"
        : name === "NotFoundError" ? "没有找到麦克风，请连接设备后重试；也可以继续打字。"
        : name === "NotReadableError" ? "麦克风暂时无法使用，可能被其他应用占用；也可以继续打字。"
        : "无法开始录音，请检查麦克风后重试；也可以继续打字。";
      this.fail(run, message);
    }
  }
  stop() {
    const run = this.run;
    if (!run || !run.recorder || this.state.phase !== "recording") return;
    this.clearTimers(run); this.emit({ phase: "stopping" });
    try {
      run.recorder.stop(); this.stopTracks(run.stream);
      // A broken recorder must not leave submission blocked forever.
      if (this.active(run)) this.timer(run, () => this.fail(run, "录音未能完成，请重新录制。文字内容仍保留。"), 10_000);
    } catch { this.fail(run, "录音未能完成，请重新录制。文字内容仍保留。" ); }
  }
  cancel() { if (this.run) this.release(this.run); this.emit({ phase: "idle", seconds: 0, error: "" }); }
  dispose() { this.disposed = true; if (this.run) this.release(this.run); }
}

export type RecordingMetadata = { id: string; mimeType: string; size: number };
export type RecordingGuideContext = { questionIndex: number; question: string };
export type RecordingDraft = { guideContext?: RecordingGuideContext; key: string; blob: Blob; url: string; status: "uploading" | "ready" | "error"; recording?: RecordingMetadata; error?: string };
type AttachmentDependencies = {
  fetch: typeof fetch; createUrl: (blob: Blob) => string; revokeUrl: (url: string) => void; makeKey: () => string;
  setTimer: (callback: () => void, delay: number) => Timer; clearTimer: (timer: Timer) => void;
};
type UploadRun = { controller: AbortController; timer?: Timer; cancelled: boolean };
const browserAttachments: AttachmentDependencies = {
  fetch: (...args) => fetch(...args), createUrl: blob => URL.createObjectURL(blob), revokeUrl: url => URL.revokeObjectURL(url), makeKey: () => crypto.randomUUID(),
  setTimer: (callback, delay) => setTimeout(callback, delay), clearTimer: timer => clearTimeout(timer),
};

export class RecordingAttachments {
  private items: RecordingDraft[] = [];
  private uploads = new Map<string, UploadRun>();
  private disposed = false;
  private committed = false;
  constructor(private onChange: (items: RecordingDraft[]) => void, private deps: AttachmentDependencies = browserAttachments) {}
  private emit() { if (!this.disposed) this.onChange([...this.items]); }
  private deletePending(id: string) {
    // Server expiry is the fallback if close/navigation interrupts this best-effort deletion.
    void this.deps.fetch(appPath(`/api/recordings/${encodeURIComponent(id)}`), { method: "DELETE", keepalive: true }).catch(() => {});
  }
  add(blob: Blob, guideContext?: RecordingGuideContext) {
    if (this.disposed || this.committed || this.items.length >= MAX_RECORDINGS || !blob.size || blob.size > MAX_RECORDING_BYTES) return false;
    const item: RecordingDraft = { key: this.deps.makeKey(), blob, url: this.deps.createUrl(blob), status: "uploading", ...(guideContext ? { guideContext: { ...guideContext } } : {}) };
    this.items.push(item); this.emit(); void this.upload(item.key); return true;
  }
  private async upload(key: string) {
    const item = this.items.find(entry => entry.key === key);
    if (!item || this.disposed || this.committed || this.uploads.has(key)) return;
    const run: UploadRun = { controller: new AbortController(), cancelled: false };
    this.uploads.set(key, run); this.update(key, { status: "uploading", error: undefined });
    const active = () => !this.disposed && !run.cancelled && this.uploads.get(key) === run && this.items.some(entry => entry.key === key);
    run.timer = this.deps.setTimer(() => {
      if (!active()) return;
      run.cancelled = true; run.controller.abort(); this.uploads.delete(key);
      this.update(key, { status: "error", error: "上传超时，录音仍保留，请重试。" });
    }, 60_000);
    try {
      const response = await this.deps.fetch(appPath("/api/recordings"), { method: "POST", headers: { "Content-Type": item.blob.type }, body: item.blob, signal: run.controller.signal });
      const data = await response.json() as Partial<RecordingMetadata> & { error?: string };
      const metadata = typeof data.id === "string" && typeof data.mimeType === "string" && typeof data.size === "number" ? { id: data.id, mimeType: data.mimeType, size: data.size } : null;
      // Even an aborted request can finish on the server and return an ID later.
      if (!active()) { if (metadata) this.deletePending(metadata.id); return; }
      if (!response.ok || !metadata) {
        if (metadata) this.deletePending(metadata.id);
        throw new Error(typeof data.error === "string" ? data.error : "录音未能上传，请重试。");
      }
      this.update(key, { recording: metadata, status: "ready", error: undefined });
    } catch (error) {
      if (active()) this.update(key, { status: "error", error: error instanceof TypeError ? "暂时无法连接，录音仍保留，请重试。" : error instanceof Error && error.name !== "AbortError" ? error.message : "录音未能上传，请重试。" });
    } finally {
      if (run.timer) this.deps.clearTimer(run.timer);
      if (this.uploads.get(key) === run) this.uploads.delete(key);
    }
  }
  private update(key: string, values: Partial<RecordingDraft>) { this.items = this.items.map(item => item.key === key ? { ...item, ...values } : item); this.emit(); }
  retry(key: string) { const item = this.items.find(entry => entry.key === key); if (item?.status === "error") void this.upload(key); }
  remove(key: string) {
    if (this.committed) return;
    const run = this.uploads.get(key); if (run) { run.cancelled = true; run.controller.abort(); if (run.timer) this.deps.clearTimer(run.timer); this.uploads.delete(key); }
    const item = this.items.find(entry => entry.key === key); if (!item) return;
    if (item.recording) this.deletePending(item.recording.id); this.deps.revokeUrl(item.url);
    this.items = this.items.filter(entry => entry.key !== key); this.emit();
  }
  commit() { this.committed = true; }
  dispose() {
    this.disposed = true;
    this.uploads.forEach(run => { run.cancelled = true; run.controller.abort(); if (run.timer) this.deps.clearTimer(run.timer); }); this.uploads.clear();
    this.items.forEach(item => { this.deps.revokeUrl(item.url); if (!this.committed && item.recording) this.deletePending(item.recording.id); }); this.items = [];
  }
}
