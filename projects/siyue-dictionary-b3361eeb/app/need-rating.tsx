"use client";

import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import type { NeedRating, NeedRecord } from "../lib/dictionary-types";
import { appFetch } from "./app-paths.mjs";
import { mergeNeedRefresh, shouldSyncRatingDraft, validateRatingInput, visibleRating } from "./need-rating-data";

const ratingTime = (time: string) => new Date(time).toLocaleString("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false });
type Props = {
  need: NeedRecord; disabled: boolean;
  onBusy: (busy: boolean) => void;
  onSaved: (need: NeedRecord) => void;
  onReload: () => Promise<NeedRecord | undefined>;
  onUnauthorized: () => Promise<void>;
  isSessionCurrent: () => boolean;
};

export function NeedRatingPanel({ need, disabled, onBusy, onSaved, onReload, onUnauthorized, isSessionCurrent }: Props) {
  const rating = visibleRating(need);
  const [score, setScore] = useState(rating ? String(rating.score) : "");
  const [comment, setComment] = useState(rating?.comment ?? "");
  const [version, setVersion] = useState(rating?.version ?? 0);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [conflict, setConflict] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [history, setHistory] = useState<NeedRating[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState("");
  const [historyRetry, setHistoryRetry] = useState(0);
  const activeSave = useRef(false);
  const mounted = useRef(true);
  const currentNeedRef = useRef(need);currentNeedRef.current=need;
  const incomingVersion = rating?.version ?? 0;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    if (shouldSyncRatingDraft(dirty, version, incomingVersion)) { setScore(rating ? String(rating.score) : ""); setComment(rating?.comment ?? ""); setVersion(incomingVersion); setConflict(false); }
  }, [dirty, version, incomingVersion, rating]);
  useEffect(() => {
    if (!historyOpen || need.canViewRating !== true || !rating) { setHistory([]); setHistoryLoading(false); return; }
    const controller = new AbortController();
    setHistoryLoading(true); setHistoryError("");
    void (async () => {
      try {
        const response = await appFetch(`/api/needs/${encodeURIComponent(need.id)}/rating/history`, { cache: "no-store", signal: controller.signal });
        const data = await response.json() as { history?: NeedRating[]; error?: string };
        if (controller.signal.aborted || !isSessionCurrent()) return;
        if (response.status === 401) await onUnauthorized();
        if (!response.ok || !Array.isArray(data.history)) throw new Error(data.error ?? "评分记录暂时无法读取。");
        if (!controller.signal.aborted && isSessionCurrent()) setHistory(data.history);
      } catch (reason) { if (!controller.signal.aborted && isSessionCurrent()) setHistoryError(reason instanceof TypeError ? "暂时无法连接，请重试。" : reason instanceof Error ? reason.message : "评分记录读取失败。"); }
      finally { if (!controller.signal.aborted && isSessionCurrent()) setHistoryLoading(false); }
    })();
    return () => controller.abort();
  }, [historyOpen, need.id, need.canViewRating, incomingVersion, historyRetry, onUnauthorized]);

  function applyRating(next: NeedRating | null) { setScore(next ? String(next.score) : ""); setComment(next?.comment ?? ""); setVersion(next?.version ?? 0); setDirty(false); setConflict(false); setError(""); }
  async function reloadRating() {
    if (disabled || saving || !isSessionCurrent()) return;
    setSaving(true); onBusy(true); setError("");
    try { const latest = await onReload(); if (!latest) throw new Error("暂时无法刷新评分，请重试。"); if (mounted.current && isSessionCurrent()) applyRating(visibleRating(latest)); }
    catch (reason) { if (mounted.current && isSessionCurrent()) setError(reason instanceof Error ? reason.message : "刷新失败，请重试。"); }
    finally { if (mounted.current && isSessionCurrent()) setSaving(false); onBusy(false); }
  }
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (disabled || activeSave.current || !isSessionCurrent() || !need.canScore || need.canViewRating !== true) return;
    const invalid = validateRatingInput(score, comment); if (invalid) { setError(invalid); return; }
    activeSave.current = true; setSaving(true); onBusy(true); setError("");
    try {
      const response = await appFetch(`/api/needs/${encodeURIComponent(need.id)}/rating`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ score: Number(score), comment: comment.trim(), version }) });
      const data = await response.json() as { need?: NeedRecord; error?: string };
      if(!mounted.current||!isSessionCurrent())return;
      if (response.status === 401) await onUnauthorized();
      if (response.status === 409 && mounted.current) setConflict(true);
      if (!response.ok || !data.need) throw new Error(data.error ?? "评分未保存，请重试。");
      if (mounted.current && isSessionCurrent()) { const updated=mergeNeedRefresh(currentNeedRef.current,data.need);applyRating(visibleRating(updated));onSaved(updated); }
    } catch (reason) { if (mounted.current && isSessionCurrent()) setError(reason instanceof TypeError ? "暂时无法连接，评分草稿已保留。" : reason instanceof Error ? reason.message : "评分未保存，草稿已保留。"); }
    finally { activeSave.current = false; if (mounted.current && isSessionCurrent()) setSaving(false); onBusy(false); }
  }
  if (need.canViewRating !== true) return null;
  const staleDraft = dirty && version !== incomingVersion;
  return <section className="need-rating" aria-label="建议评分">
    <div className="rating-heading"><h4>评分</h4>{rating && <button type="button" className="text-button" onClick={() => setHistoryOpen(open => !open)}>{historyOpen ? "收起记录" : "改分记录"}</button>}</div>
    {need.canScore ? <form className="rating-form" onSubmit={save}>
      <label className="rating-score-input"><span>分数</span><input name="score" type="number" min={0} max={100} step={1} required value={score} onChange={event => { setScore(event.target.value); setDirty(true); setError(""); }} disabled={disabled || saving} aria-label="建议分数"/><span>/ 100</span></label>
      <label className="field rating-comment"><span>评语（选填）</span><textarea name="comment" rows={2} maxLength={1500} value={comment} onChange={event => { setComment(event.target.value); setDirty(true); setError(""); }} disabled={disabled || saving}/></label>
      <div className="rating-save-row">{rating && <p>{rating.reviewerName} · {ratingTime(rating.updatedAt)}</p>}<button type="submit" className="primary-button" disabled={disabled || saving || (!dirty && !!rating)}>{saving ? "正在保存…" : rating ? "保存修改" : "保存评分"}</button></div>
      {(conflict || staleDraft) && <div className="rating-conflict"><p>评分已有更新，当前草稿保留。</p><button type="button" className="text-button" disabled={disabled || saving} onClick={reloadRating}>载入最新评分</button></div>}
      {error && <p className="form-error" role="alert">{error}</p>}
    </form> : rating ? <div className="rating-result"><strong>{rating.score}<small>/ 100</small></strong>{rating.comment && <p className="rating-result-comment">{rating.comment}</p>}<p className="rating-meta">{rating.reviewerName} · {ratingTime(rating.updatedAt)}</p></div> : <p className="rating-pending">待评分</p>}
    {historyOpen && <div className="rating-history">{historyLoading ? <p>正在读取…</p> : historyError ? <div className="rating-history-error" role="alert"><p>{historyError}</p><button type="button" className="text-button" onClick={() => setHistoryRetry(value => value + 1)}>重试</button></div> : <ol>{history.map(item => <li key={item.version}><div><strong>{item.score}分</strong><span>{item.reviewerName} · {ratingTime(item.updatedAt)}</span></div>{item.comment && <p>{item.comment}</p>}</li>)}</ol>}</div>}
  </section>;
}
