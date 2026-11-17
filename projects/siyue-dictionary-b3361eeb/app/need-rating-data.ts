import type { NeedRating, NeedRecord } from "../lib/dictionary-types";

type RatingView = Pick<NeedRecord, "rating" | "canViewRating">;
export function visibleRating(need: RatingView): NeedRating | null { return need.canViewRating === true ? need.rating ?? null : null; }
export function ratingCellText(need: RatingView) { return need.canViewRating === true ? visibleRating(need) ? `${visibleRating(need)!.score}分` : "待评分" : "—"; }
export function ratingExportFields(need: RatingView) {
  const rating = visibleRating(need);
  return rating ? [rating.score, rating.comment, rating.reviewerName, rating.updatedAt] : ["", "", "", ""];
}
export function isOwnNeed(need: Pick<NeedRecord, "ownerId">, employeeId: string | null | undefined) { return !!employeeId && need.ownerId === employeeId; }
export function defaultNeedScope(canScoreNeeds: boolean | undefined): "mine" | "all" { return canScoreNeeds === true ? "all" : "mine"; }
export function shouldSyncRatingDraft(dirty: boolean, draftVersion: number, incomingVersion: number) { return !dirty && draftVersion !== incomingVersion; }
export function validateRatingInput(scoreText: string, comment: string) {
  if (!scoreText.trim()) return "请填写 0–100 的整数分数。";
  const score = Number(scoreText);
  if (!Number.isInteger(score) || score < 0 || score > 100) return "分数须为 0–100 的整数。";
  if (comment.trim().length > 1500) return "评语最多 1500 字。";
  return "";
}

export function mergeNeedRefresh(current: NeedRecord | undefined, incoming: NeedRecord): NeedRecord {
  const previousRating = current && visibleRating(current);
  const incomingRating = visibleRating(incoming);
  if (current?.id === incoming.id && incoming.canViewRating === true && previousRating && previousRating.version > (incomingRating?.version ?? 0)) {
    return { ...incoming, rating: previousRating, canDelete: false };
  }
  return incoming;
}
