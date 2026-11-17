export type SourceRef = { id: string; file: string; locator: string; excerpt: string; note?: string };
export type Requirement = { title: string; text: string; refs: string[]; flag?: string };
export type RoleEntry = {
  id: string; name: string; aliases: string[]; group: string; summary: string;
  status: '有书面依据' | '组织描述' | '资料待补'; scope: string;
  duties: Requirement[]; schedule: Requirement[]; checks: Requirement[];
  handoffs: { from: string; to: string; deliverable: string; acceptance: string; refs: string[] }[];
  questions: string[]; related: string[]; processIds: string[];
};
export type ProcessEntry = { id: string; name: string; summary: string; roles: string[]; steps: {title:string;text:string;owner:string;refs:string[]}[]; questions:string[] };
export type Candidate = {id:string;title:string;roleId:string;processId:string;category:string;problem:string;proposal:string;measure:string;boundary:string;refs:string[]};
export type RecordingAttachment = {id:string; mimeType:string; size:number; duration?:number};
export type NeedRating = {score:number; comment:string; reviewerId:string; reviewerName:string; updatedAt:string; version:number};
export type NeedRecord = {
  id:string; title:string; roleId:string; processId:string; campus:string; grade:string;
  category:string; problem:string; currentMethod:string; desiredOutcome:string; evidence:string;
  frequencyPerWeek:number|null; minutesPerOccurrence:number|null; affectedPeople:number|null;
  urgency:'一般'|'较急'|'紧急'; status:'待核实'|'已确认'|'试点中'|'已完成'|'暂缓';
  ownerId:string; author:string; createdAt:string; updatedAt:string; version:number;
  canEdit:boolean; recordings:RecordingAttachment[];
  rating:NeedRating|null; canViewRating:boolean; canScore:boolean; canDelete:boolean;
};
export const needCategories = ['待分类','流程与职责','系统与数据','重复劳动','信息查询','跨部门交接','家长沟通','教学服务','其他'];
export const needStatuses = ['待核实','已确认','试点中','已完成','暂缓'];

export const departments = ['AI部','新媒体运营部','年级运营','招生','教务','教学教研','一对一','HR','行政','校区管理','集团'];
