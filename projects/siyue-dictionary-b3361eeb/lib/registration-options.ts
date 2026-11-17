import type { RegistrationRole } from './catalog-types';

// Public registration labels only. Internal reference content stays server-side.
export const registrationRoles: RegistrationRole[] = [
  {
    "id": "academic",
    "name": "教务",
    "group": "教学与服务",
    "aliases": [
      "教务老师",
      "校区教务",
      "教务组长"
    ]
  },
  {
    "id": "admissions",
    "name": "招生顾问",
    "group": "招生与运营",
    "aliases": [
      "招生老师",
      "顾问",
      "招生"
    ]
  },
  {
    "id": "grade-ops",
    "name": "年级运营",
    "group": "招生与运营",
    "aliases": [
      "私域运营",
      "年级私域运营"
    ]
  },
  {
    "id": "new-media",
    "name": "新媒体运营",
    "group": "招生与运营",
    "aliases": [
      "新媒体运营部",
      "内容运营",
      "外部获客"
    ]
  },
  {
    "id": "teacher",
    "name": "任课教师",
    "group": "教学与服务",
    "aliases": [
      "教师",
      "授课老师",
      "年级教师"
    ]
  },
  {
    "id": "manager",
    "name": "店长",
    "group": "校区管理",
    "aliases": [
      "校区店长",
      "校长",
      "校区校长"
    ]
  },
  {
    "id": "grade-lead",
    "name": "年级负责人",
    "group": "年级管理",
    "aliases": [
      "年级主管"
    ]
  },
  {
    "id": "admissions-lead",
    "name": "招生负责人",
    "group": "招生与运营",
    "aliases": [
      "招生管理负责人"
    ]
  },
  {
    "id": "subject-lead",
    "name": "学科主管",
    "group": "教学与服务",
    "aliases": [
      "学科负责人"
    ]
  },
  {
    "id": "one-to-one",
    "name": "一对一部门负责人",
    "group": "教学与服务",
    "aliases": [
      "一对一负责人"
    ]
  },
  {
    "id": "dept-head",
    "name": "部门负责人",
    "group": "管理与协同",
    "aliases": [
      "集团负责人",
      "老板",
      "负责人"
    ]
  },
  {
    "id": "ai-dept",
    "name": "AI部",
    "group": "职能支持",
    "aliases": [
      "AI 部",
      "人工智能部门",
      "AI部门"
    ]
  },
  {
    "id": "finance",
    "name": "财务",
    "group": "职能支持",
    "aliases": [
      "财务室",
      "财务端"
    ]
  },
  {
    "id": "hr",
    "name": "HR",
    "group": "职能支持",
    "aliases": [
      "人事",
      "人事经理",
      "HR人员"
    ]
  },
  {
    "id": "admin",
    "name": "行政人员",
    "group": "职能支持",
    "aliases": [
      "行政",
      "行政部"
    ]
  },
  {
    "id": "wechat-admin",
    "name": "微信账号管理",
    "group": "运营支持",
    "aliases": [
      "账号资产管理员"
    ]
  }
];
