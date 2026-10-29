/**
 * 维护原型演示记录、界面资料和本地持久化，并提供公共下载等展示工具。
 * 关联模块：mock.ts 与原型页面使用演示数据；真实模式不恢复演示业务记录，auth.ts 负责同步真实个人资料。
 */
import { reactive, watch } from "vue";
const demoMode = import.meta.env.VITE_USE_MOCK === "true";
export type Row = Record<string, any> & { id: string };
const date = "2025-09-16";
const people = [
  "管理员",
  "张三",
  "李四",
  "王五",
  "赵六",
  "陈晨",
  "林晓",
  "刘洋",
  "周悦",
  "吴桐",
  "徐佳",
  "孙宁",
];
const usernames = [
  "admin",
  "zhangsan",
  "lisi",
  "wangwu",
  "zhaoliu",
  "chenchen",
  "linxiao",
  "liuyang",
  "zhouyue",
  "wutong",
  "xujia",
  "sunning",
];
const depts = [
  "总经办",
  "研发部",
  "产品设计部",
  "市场营销部",
  "财务部",
  "人力资源部",
];
const roles = ["超级管理员", "系统管理员", "运维人员", "普通用户", "审计人员"];
const times = ["16:32:10", "15:20:45", "14:58:22", "14:32:11", "13:45:09"];
const seed: Record<string, Row[]> = {
  users: Array.from({ length: 1248 }, (_, i) => ({
    id: String(1001 + i),
    username: usernames[i % 12] + (i < 12 ? "" : i),
    name: people[i % 12],
    department: depts[i % 6],
    role: roles[i === 0 ? 0 : (i % 4) + 1],
    post: ["部门经理", "开发工程师", "产品经理", "设计师", "专员"][i % 5],
    phone: "138" + String(10000000 + i * 113),
    email: `${usernames[i % 12]}${i || ""}@star.com`,
    status: i && i % 9 === 0 ? "停用" : "启用",
    createdAt: `${date} ${times[i % 5]}`,
    remark: i === 0 ? "系统内置管理员" : "",
  })),
  roles: roles.map((name, i) => ({
    id: String(i + 1),
    name,
    code: ["super_admin", "admin", "operator", "user", "auditor"][i],
    members: [1, 12, 26, 1198, 11][i],
    scope: ["全部数据", "全部数据", "本部门及下级", "仅本人", "全部数据"][i],
    status: "启用",
    createdAt: "2025-08-15 09:00:00",
    remark: [
      "拥有平台全部管理权限",
      "负责日常系统管理",
      "系统运行与维护",
      "企业普通成员",
      "查看审计与安全日志",
    ][i],
    permissions:
      i < 2
        ? [
            "用户管理",
            "角色管理",
            "菜单管理",
            "部门管理",
            "岗位管理",
            "项目管理",
            "图片管理",
            "操作日志",
            "系统监控",
          ]
        : ["项目管理", "图片管理"],
  })),
  menus: [
    { name: "首页", path: "/dashboard", parent: "顶级菜单", type: "菜单" },
    { name: "系统管理", path: "/system", parent: "顶级菜单", type: "目录" },
    {
      name: "用户管理",
      path: "/system/users",
      parent: "系统管理",
      permission: "system:user:list",
    },
    {
      name: "角色管理",
      path: "/system/roles",
      parent: "系统管理",
      permission: "system:role:list",
    },
    {
      name: "菜单管理",
      path: "/system/menus",
      parent: "系统管理",
      permission: "system:menu:list",
    },
    {
      name: "部门管理",
      path: "/system/departments",
      parent: "系统管理",
      permission: "system:dept:list",
    },
    {
      name: "岗位管理",
      path: "/system/posts",
      parent: "系统管理",
      permission: "system:post:list",
    },
    { name: "业务管理", path: "/business", parent: "顶级菜单", type: "目录" },
    { name: "订单管理", path: "/business/orders", parent: "业务管理" },
    { name: "产品管理", path: "/business/products", parent: "业务管理" },
    { name: "日志管理", path: "/audit", parent: "顶级菜单", type: "目录" },
    { name: "文件管理", path: "/files", parent: "顶级菜单", type: "目录" },
    {
      name: "图片管理",
      path: "/images",
      parent: "文件管理",
      permission: "storage:file:list",
    },
    {
      name: "视频管理",
      path: "/videos",
      parent: "文件管理",
      permission: "storage:file:list",
    },
    {
      name: "附件管理",
      path: "/attachments",
      parent: "文件管理",
      permission: "storage:file:list",
    },
    { name: "系统监控", path: "/monitor", parent: "顶级菜单", type: "目录" },
  ].map((menu, i) => ({
    id: String(i + 1),
    type: "菜单",
    permission: "",
    ...menu,
    sort: i + 1,
    status: "启用",
    createdAt: "2025-08-15 09:00:00",
  })),
  departments: depts.map((name, i) => ({
    id: String(i + 1),
    name,
    parent: "Star 科技有限公司",
    leader: people[i],
    phone: `0571-8800120${i}`,
    members: [24, 586, 168, 230, 96, 144][i],
    status: "启用",
    sort: i + 1,
    createdAt: "2025-08-15 09:00:00",
  })),
  posts: [
    "部门经理",
    "开发工程师",
    "产品经理",
    "UI 设计师",
    "测试工程师",
    "运维工程师",
    "人事专员",
    "财务专员",
  ].map((name, i) => ({
    id: String(i + 1),
    name,
    code: [
      "manager",
      "developer",
      "product",
      "designer",
      "tester",
      "devops",
      "hr",
      "finance",
    ][i],
    sort: i + 1,
    status: "启用",
    createdAt: "2025-08-15 09:00:00",
    remark: [
      "负责部门日常管理",
      "产品研发与技术支持",
      "产品规划与需求分析",
      "产品体验与视觉设计",
    ][i % 4],
  })),
  projects: Array.from({ length: 86 }, (_, i) => ({
    id: String(2001 + i),
    name:
      [
        "企业数字化管理平台",
        "客户关系管理系统",
        "数据可视化大屏",
        "移动端工作台",
        "统一身份认证平台",
        "智能报表中心",
        "资产管理系统",
        "企业协同办公",
      ][i % 8] + (i < 8 ? "" : ` · 第 ${Math.floor(i / 8) + 1} 期`),
    code: `PRJ-${String(i + 1).padStart(4, "0")}`,
    type: ["系统管理", "业务管理", "文件管理", "日志管理", "其他"][i % 5],
    leader: people[(i + 1) % 12],
    department: "研发部",
    progress: [75, 42, 100, 28, 90, 0, 60, 35][i % 8],
    status: [
      "进行中",
      "进行中",
      "已完成",
      "进行中",
      "进行中",
      "待开始",
      "已暂停",
      "进行中",
    ][i % 8],
    deadline: `2025-${i % 2 ? "11" : "10"}-${String(10 + (i % 20)).padStart(2, "0")}`,
    createdAt: "2025-09-01 09:00:00",
    remark: "持续提升企业管理效率，打造一体化数字工作空间。",
  })),
  operations: Array.from({ length: 25 }, (_, i) => ({
    id: String(1001 - i),
    operator: usernames[i % 5],
    action: ["登录", "新增", "修改", "删除", "设置"][i % 5],
    name: [
      "用户登录系统",
      "新建用户：李四",
      "修改角色：运维人员",
      "删除文件：test.zip",
      "更新系统配置",
    ][i % 5],
    createdAt: `${date} ${times[i % 5]}`,
    ip: `192.168.1.${100 + (i % 5)}`,
    status: "成功",
    duration: `${28 + i * 7}ms`,
    method: i % 5 === 3 ? "DELETE" : "POST",
    url:
      "/api/v1/system/" + ["auth", "users", "roles", "files", "configs"][i % 5],
  })),
  logins: Array.from({ length: 18 }, (_, i) => ({
    id: String(3001 + i),
    username: usernames[i % 12],
    name: people[i % 12],
    ip: `192.168.1.${100 + i}`,
    location: ["浙江省 杭州市", "上海市", "北京市"][i % 3],
    browser: ["Chrome 128", "Edge 128", "Safari 17"][i % 3],
    os: i % 3 === 2 ? "macOS" : "Windows 11",
    status: i === 7 ? "失败" : "成功",
    createdAt: `${date} ${times[i % 5]}`,
  })),
  sessions: Array.from({ length: 8 }, (_, i) => ({
    id: String(4001 + i),
    username: usernames[i],
    name: people[i],
    department: depts[i % 6],
    ip: `192.168.1.${100 + i}`,
    browser: "Chrome 128",
    createdAt: `${date} ${times[i % 5]}`,
    status: "在线",
  })),
  files: [
    "企业管理平台产品说明.pdf",
    "2025年度项目规划.xlsx",
    "品牌视觉设计规范.fig",
    "系统架构设计.png",
    "前端项目源码.zip",
    "员工入职手册.docx",
    "季度经营数据.xlsx",
    "平台使用指南.pdf",
  ].map((name, i) => ({
    id: String(5001 + i),
    name,
    extension: name.split(".").pop(),
    size: [
      2450000, 1280000, 6800000, 3450000, 12500000, 890000, 560000, 1890000,
    ][i],
    owner: people[i % 4],
    createdAt: `${date} ${times[i % 5]}`,
    category: [
      "文档",
      "文档",
      "其他",
      "图片",
      "压缩包",
      "文档",
      "文档",
      "文档",
    ][i],
    business: ["企业数字化管理平台", "客户关系管理系统", "移动端工作台"][i % 3],
    remark: "示例文件，上传本地文件后可预览或下载",
    demo: true,
  })),
};
const storageKey = "star-admin-demo-v1";
/** 仅演示模式恢复本地示例记录；真实模式返回空业务集合，由接口加载真实数据。 */
function restore() {
  if (!demoMode)
    return Object.fromEntries(Object.keys(seed).map((key) => [key, []]));
  try {
    const raw = localStorage.getItem(storageKey);
    if (raw) {
      const restored: Record<string, Row[]> = {
        ...structuredClone(seed),
        ...JSON.parse(raw),
      };
      // 清理旧演示缓存中的已删除模块，避免刷新后在菜单和角色授权中再次出现。
      delete restored.dictionaries;
      delete restored.settings;
      restored.menus = restored.menus.filter(
        (row) =>
          row.path !== "/applications" &&
          row.path !== "/data" &&
          !row.path?.startsWith("/data/"),
      );
      for (const role of restored.roles)
        if (Array.isArray(role.permissions))
          role.permissions = role.permissions.filter(
            (name: string) =>
              !["部门应用", "数据管理", "字典管理", "参数设置"].includes(name),
          );
      return restored;
    }
  } catch {
    /* 浏览器存储损坏或不可用时，演示模式回退到初始演示记录。 */
  }
  return structuredClone(seed);
}
export const db = reactive<Record<string, Row[]>>(restore());
export const ui = reactive({
  profile: {
    name: "管理员",
    email: "admin@star.com",
    phone: "13800138000",
    department: "总经办",
    bio: "让企业管理更简单。",
  },
  unread: 3,
  storageError: false,
});
try {
  if (demoMode)
    Object.assign(
      ui.profile,
      JSON.parse(localStorage.getItem("star-profile") || "{}"),
    );
} catch {}
watch(
  db,
  () => {
    if (!demoMode) return;
    try {
      localStorage.setItem(storageKey, JSON.stringify(db));
      ui.storageError = false;
    } catch {
      ui.storageError = true;
    }
  },
  { deep: true },
);
watch(
  () => ui.profile,
  (p) => {
    if (!demoMode) return;
    try {
      localStorage.setItem("star-profile", JSON.stringify(p));
    } catch {
      ui.storageError = true;
    }
  },
  { deep: true },
);
export function timestamp() {
  return new Date().toLocaleString("sv-SE", { hour12: false });
}
export function audit(action: string, name: string) {
  db.operations.unshift({
    id: String(Date.now()),
    operator: "admin",
    action,
    name,
    createdAt: timestamp(),
    ip: "127.0.0.1",
    status: "成功",
    duration: "18ms",
  });
}
export function saveRow(key: string, value: Row, isNew: boolean) {
  if (isNew)
    db[key].unshift({
      ...value,
      id: crypto.randomUUID(),
      createdAt: timestamp(),
    });
  else {
    const i = db[key].findIndex((r) => r.id === value.id);
    if (i >= 0) db[key][i] = { ...value };
  }
  audit(
    isNew ? "新增" : "修改",
    `${isNew ? "新增" : "修改"}${value.name || value.username}`,
  );
}
/** 模拟删除行为：角色只解除用户绑定，删除用户时不影响其他用户和角色。 */
export function removeRows(key: string, ids: string[]) {
  if (key === "roles") {
    const removedNames = new Set(
      db.roles.filter((r) => ids.includes(r.id)).map((r) => r.name),
    );
    for (const user of db.users) {
      if (typeof user.role === "string")
        user.role = user.role
          .split("、")
          .filter((name) => !removedNames.has(name))
          .join("、");
    }
  }
  db[key] = db[key].filter((r) => !ids.includes(r.id));
  audit("删除", `删除 ${ids.length} 条${key === "users" ? "用户" : ""}记录`);
}
export function exportCsv(
  rows: Row[],
  columns: { key: string; label: string }[],
  filename: string,
) {
  const cell = (v: unknown) => {
    let s = String(v ?? "");
    if (/^[=+@\-\t\r]/.test(s)) s = "'" + s;
    return '"' + s.replaceAll('"', '""') + '"';
  };
  const content =
    "\uFEFF" +
    [
      columns.map((c) => cell(c.label)).join(","),
      ...rows.map((r) => columns.map((c) => cell(r[c.key])).join(",")),
    ].join("\r\n");
  downloadBlob(
    new Blob([content], { type: "text/csv;charset=utf-8;" }),
    filename + ".csv",
  );
}
/** 统一触发浏览器下载，并在触发后释放临时对象地址。 */
export function downloadBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
