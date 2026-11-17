"use client";

import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import type { FormEvent } from "react";
import { ChevronLeft, ChevronRight, LoaderCircle, Mic, Square, Trash2 } from "lucide-react";
import { AudioCapture, MAX_RECORDINGS, RecordingAttachments } from "./recording-session";
import type { CaptureState, RecordingDraft, RecordingMetadata, RecordingGuideContext } from "./recording-session";
import { composeSurvey, SURVEY_QUESTIONS } from "./survey-content";
import { appPath } from "./app-paths.mjs";

type SurveyProps = {
  draft: { roleId: string; processId: string; problem?: string; desiredOutcome?: string };
  profile: { campus: string; grade: string } | null;
  saving: boolean; error: string;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onClose: () => void;
};
export type SurveyComposerHandle = { markSubmitted: () => void };
const timeText = (seconds: number) => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;

export const SurveyComposer = forwardRef<SurveyComposerHandle, SurveyProps>(function SurveyComposer({ draft, profile, saving, error, onSubmit, onClose }, ref) {
  const [freeText, setFreeText] = useState(() => [draft.problem ?? "", draft.desiredOutcome ? `希望变化：${draft.desiredOutcome}` : ""].filter(Boolean).join("\n\n"));
  const [answers, setAnswers] = useState<string[]>(SURVEY_QUESTIONS.map(() => ""));
  const [guided, setGuided] = useState(false);
  const [questionIndex, setQuestionIndex] = useState(0);
  const [captureState, setCaptureState] = useState<CaptureState>({ phase: "idle", seconds: 0, error: "" });
  const [recordings, setRecordings] = useState<RecordingDraft[]>([]);
  const [localError, setLocalError] = useState("");
  const captureRef = useRef<AudioCapture | null>(null);
  const attachmentsRef = useRef<RecordingAttachments | null>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const replacementKeyRef = useRef<string | null>(null);
  const recordingContextRef = useRef<RecordingGuideContext | undefined>(undefined);
  useEffect(() => {
    const attachments = new RecordingAttachments(setRecordings);
    const capture = new AudioCapture(setCaptureState, blob => {
      if (replacementKeyRef.current) attachments.remove(replacementKeyRef.current);
      replacementKeyRef.current = null;
      const guideContext = recordingContextRef.current; recordingContextRef.current = undefined;
      if (!attachments.add(blob, guideContext)) setLocalError("最多保留 5 段录音，每段不超过 2 分钟、8 MiB。请删除一段后再录。");
    });
    captureRef.current = capture; attachmentsRef.current = attachments;
    return () => { capture.dispose(); attachments.dispose(); captureRef.current = null; attachmentsRef.current = null; };
  }, []);
  useImperativeHandle(ref, () => ({ markSubmitted: () => attachmentsRef.current?.commit() }), []);

  const readyDrafts = recordings.filter(recording => recording.status === "ready" && recording.recording);
  const readyRecordings = readyDrafts.flatMap(recording => recording.recording ? [recording.recording] : []);
  const content = composeSurvey(freeText, answers, readyDrafts);
  const uploading = recordings.some(recording => recording.status === "uploading");
  const incompleteUpload = recordings.some(recording => recording.status !== "ready");
  const busy = captureState.phase !== "idle" || uploading;
  const locked = saving || busy;
  const tooLong = content.problem.length > 2500;
  const value = guided ? answers[questionIndex] : freeText;
  const question = guided ? SURVEY_QUESTIONS[questionIndex] : "最近工作中，有什么想改进的？";
  function changeText(text: string) {
    setLocalError("");
    if (guided) setAnswers(previous => previous.map((answer, index) => index === questionIndex ? text : answer));
    else setFreeText(text);
  }
  function changeMode(next: boolean) { if (locked) return; setGuided(next); setLocalError(""); inputRef.current?.focus(); }
  function moveQuestion(direction: number) { if (locked) return; setQuestionIndex(index => Math.max(0, Math.min(SURVEY_QUESTIONS.length - 1, index + direction))); setLocalError(""); inputRef.current?.focus(); }
  function beginRecording(replaceKey?: string) {
    if (locked || captureRef.current?.busy) return;
    replacementKeyRef.current = replaceKey ?? null;
    recordingContextRef.current = guided ? { questionIndex, question: SURVEY_QUESTIONS[questionIndex] } : recordings.find(recording => recording.key === replaceKey)?.guideContext;
    setLocalError(""); void captureRef.current?.start();
  }
  function cancelRecording() { replacementKeyRef.current = null; recordingContextRef.current = undefined; captureRef.current?.cancel(); }
  function submit(event: FormEvent<HTMLFormElement>) {
    if (locked || incompleteUpload || captureRef.current?.busy) { event.preventDefault(); return; }
    if (tooLong) { event.preventDefault(); setLocalError(`内容合计超出 ${content.problem.length - 2500} 字，请修改后提交。`); return; }
    if (!readyRecordings.length && content.answerLength < 5) { event.preventDefault(); setLocalError("请至少填写 5 个字，或录一段声音后提交。"); inputRef.current?.focus(); return; }
    setLocalError(""); onSubmit(event);
  }
  return <form onSubmit={submit}>
    <input type="hidden" name="roleId" value={draft.roleId}/><input type="hidden" name="processId" value={draft.processId}/>
    <input type="hidden" name="campus" value={profile?.campus ?? ""}/><input type="hidden" name="grade" value={profile?.grade ?? ""}/>
    <input type="hidden" name="category" value="待分类"/><input type="hidden" name="urgency" value="一般"/>
    <input type="hidden" name="title" value={content.title}/><input type="hidden" name="problem" value={content.problem}/>
    <input type="hidden" name="recordingIds" value={JSON.stringify(readyRecordings.map(recording => recording.id))}/>
    <div className="form-body survey-composer-body">
      <div className="survey-mode-line"><button type="button" className="text-button" disabled={locked} onClick={() => changeMode(!guided)}>{guided ? "自由填写" : content.answeredCount ? "继续引导" : "问我几个问题"}</button><span>{guided ? `${questionIndex + 1} / ${SURVEY_QUESTIONS.length}` : content.answeredCount ? `已保留 ${content.answeredCount} 条回答` : ""}</span></div>
      <label className="survey-question" htmlFor="survey-answer">{question}</label>
      <div className="survey-input-shell">
        <textarea id="survey-answer" ref={inputRef} value={value} onChange={event => changeText(event.target.value)} readOnly={saving} rows={6} autoFocus placeholder={guided ? questionIndex === 2 ? "不确定也可以直接说不确定。" : "可以打字，也可以录音。" : "经过、目前怎么处理、花了多久、希望有什么变化，都可以写在这里，也可以直接录音。"}/>
        {recordings.length > 0 && <div className="draft-recordings">{recordings.map((recording, index) => <div className="draft-recording" key={recording.key}>
          <div className="recording-label"><span title={recording.guideContext?.question}>录音 {index + 1}{recording.guideContext && ` · 第${recording.guideContext.questionIndex + 1}问`}{recording.status === "uploading" && " · 上传中…"}</span><div>{recording.status !== "uploading" && <button type="button" className="text-button" disabled={locked} onClick={() => beginRecording(recording.key)}>重录</button>}{recording.status === "error" && <button type="button" className="text-button" disabled={locked} onClick={() => attachmentsRef.current?.retry(recording.key)}>重试上传</button>}<button type="button" className="icon-button" disabled={saving || captureState.phase !== "idle"} onClick={() => attachmentsRef.current?.remove(recording.key)} aria-label={recording.status === "uploading" ? `取消上传录音 ${index + 1}` : `删除录音 ${index + 1}`}><Trash2 size={14}/></button></div></div>
          <audio src={recording.url} controls preload="metadata" aria-label={`试听录音 ${index + 1}`}/>
          {recording.error && <p className="recording-error" role="alert">{recording.error}</p>}
        </div>)}</div>}
        <div className="survey-input-tools">
          {captureState.phase === "idle" ? <button type="button" className="voice-button" disabled={saving || uploading || recordings.length >= MAX_RECORDINGS} onClick={() => beginRecording()} aria-label="开始录音，每段最多2分钟"><Mic size={17}/>{recordings.length >= MAX_RECORDINGS ? "已达5段" : "录音"}</button>
            : captureState.phase === "recording" ? <><span className="recording-clock" role="status"><span/>{timeText(captureState.seconds)} / 2:00</span><button type="button" className="voice-button" onClick={() => captureRef.current?.stop()}><Square size={13}/>停止</button><button type="button" className="text-button" onClick={cancelRecording}>取消录音</button></>
            : <><span className="voice-progress" role="status"><LoaderCircle size={15} className="spin"/>{captureState.phase === "requesting" ? "等待麦克风…" : "正在保存录音…"}</span><button type="button" className="text-button" onClick={cancelRecording}>取消录音</button></>}
          {content.problem.length >= 2200 && <span className={`survey-character-count ${tooLong ? "over-limit" : ""}`}>{tooLong ? `超出 ${content.problem.length - 2500} 字` : `剩余 ${2500 - content.problem.length} 字`}</span>}
        </div>
      </div>
      {guided && <div className="survey-guide-navigation"><button type="button" className="text-button" disabled={locked || questionIndex === 0} onClick={() => moveQuestion(-1)}><ChevronLeft size={14}/>上一问</button>{freeText.trim() && <span>已保留自由填写内容</span>}<button type="button" className="text-button" disabled={locked} onClick={() => questionIndex === SURVEY_QUESTIONS.length - 1 ? changeMode(false) : moveQuestion(1)}>{questionIndex === SURVEY_QUESTIONS.length - 1 ? "完成引导" : "下一问"}<ChevronRight size={14}/></button></div>}
      {(localError || tooLong || captureState.error || error) && <div className="form-error" role="alert">{localError || (tooLong ? `内容合计超出 ${content.problem.length - 2500} 字，请修改后提交。` : "") || captureState.error || error}</div>}
    </div>
    <footer className="form-footer simple-need-footer"><button type="button" className="text-button" disabled={saving} onClick={onClose}>取消</button><button type="submit" className="primary-button" disabled={locked || incompleteUpload || tooLong}>{saving && <LoaderCircle size={16} className="spin"/>}{saving ? "正在提交…" : "提交问题"}</button></footer>
  </form>;
});

export function SubmittedRecordings({ recordings }: { recordings: RecordingMetadata[] }) {
  if (!recordings.length) return null;
  return <section className="submitted-recordings" aria-label="随附录音">{recordings.map((recording, index) => <div className="submitted-recording" key={recording.id}><p>录音 {index + 1}</p><audio controls preload="metadata" src={appPath(`/api/recordings/${encodeURIComponent(recording.id)}`)} aria-label={`播放录音 ${index + 1}`}/></div>)}</section>;
}
