/**
 * 集中定义原型导航、管理表格列和表单字段，统一各资源的展示配置。
 * 关联模块：Management.vue 与 SystemManagement.vue 读取模块配置；sidebar-navigation.ts 复用导航生成分组和待开放入口。
 */
export interface Field {
  key: string;
  label: string;
  type?: "select" | "textarea" | "number";
  options?: string[];
  required?: boolean;
}
export interface Column {
  key: string;
  label: string;
  kind?: "status" | "tag" | "avatar" | "progress";
  width?: string;
}
export interface ModuleConfig {
  title: string;
  singular: string;
  description: string;
  icon: string;
  columns: Column[];
  fields: Field[];
  readonly?: boolean;
}
const status: Field = {
  key: "status",
  label: "状态",
  type: "select",
  options: ["启用", "停用"],
};
const name = (label = "名称"): Field => ({
  key: "name",
  label,
  required: true,
});
const code: Field = { key: "code", label: "编码", required: true };
const remark: Field = { key: "remark", label: "备注", type: "textarea" };
const statusCol: Column = { key: "status", label: "状态", kind: "status" };
const created: Column = { key: "createdAt", label: "创建时间" };
export const modules: Record<string, ModuleConfig> = {
  users: {
    title: "用户管理",
    singular: "用户",
    description: "管理企业成员，分配角色与访问权限",
    icon: "users",
    columns: [
      { key: "username", label: "用户账号", kind: "avatar" },
      { key: "name", label: "用户姓名" },
      { key: "department", label: "所属部门" },
      { key: "phone", label: "手机号码" },
      { key: "role", label: "角色", kind: "tag" },
      statusCol,
      created,
    ],
    fields: [
      { key: "username", label: "用户账号", required: true },
      name("用户姓名"),
      { key: "phone", label: "手机号码" },
      { key: "email", label: "电子邮箱" },
      {
        key: "department",
        label: "所属部门",
        type: "select",
        options: [
          "总经办",
          "研发部",
          "产品设计部",
          "市场营销部",
          "财务部",
          "人力资源部",
        ],
        required: true,
      },
      {
        key: "role",
        label: "角色",
        type: "select",
        options: [
          "超级管理员",
          "系统管理员",
          "运维人员",
          "普通用户",
          "审计人员",
        ],
        required: true,
      },
      {
        key: "post",
        label: "岗位",
        type: "select",
        options: ["部门经理", "开发工程师", "产品经理", "设计师", "专员"],
      },
      status,
      remark,
    ],
  },
  roles: {
    title: "角色管理",
    singular: "角色",
    description: "灵活配置角色权限，建立安全有序的访问体系",
    icon: "shield",
    columns: [
      name("角色名称"),
      { key: "code", label: "角色标识" },
      { key: "members", label: "关联用户" },
      { key: "scope", label: "数据权限", kind: "tag" },
      statusCol,
      created,
    ],
    fields: [
      name("角色名称"),
      code,
      {
        key: "scope",
        label: "数据权限",
        type: "select",
        options: ["全部数据", "本部门及下级", "本部门", "自定义部门", "仅本人"],
      },
      status,
      remark,
    ],
  },
  menus: {
    title: "菜单管理",
    singular: "菜单",
    description: "配置导航结构、页面路由与操作权限",
    icon: "list-tree",
    columns: [
      name("菜单名称"),
      { key: "type", label: "类型", kind: "tag" },
      { key: "path", label: "路由地址" },
      { key: "permission", label: "权限标识" },
      { key: "sort", label: "排序" },
      statusCol,
    ],
    fields: [
      name("菜单名称"),
      {
        key: "parent",
        label: "上级菜单",
        type: "select",
        options: ["顶级菜单", "系统管理", "业务管理", "日志管理", "系统监控"],
      },
      {
        key: "type",
        label: "菜单类型",
        type: "select",
        options: ["目录", "菜单", "按钮"],
      },
      { key: "path", label: "路由地址" },
      { key: "permission", label: "权限标识" },
      { key: "sort", label: "显示排序", type: "number" },
      status,
    ],
  },
  departments: {
    title: "部门管理",
    singular: "部门",
    description: "维护组织架构，让团队协作井然有序",
    icon: "network",
    columns: [
      name("部门名称"),
      { key: "parent", label: "上级部门" },
      { key: "leader", label: "负责人" },
      { key: "phone", label: "联系电话" },
      { key: "members", label: "成员人数" },
      statusCol,
    ],
    fields: [
      name("部门名称"),
      {
        key: "parent",
        label: "上级部门",
        type: "select",
        options: [
          "Star 科技有限公司",
          "总经办",
          "研发部",
          "产品设计部",
          "市场营销部",
        ],
      },
      { key: "leader", label: "负责人", required: true },
      { key: "phone", label: "联系电话" },
      { key: "sort", label: "排序", type: "number" },
      status,
      remark,
    ],
  },
  posts: {
    title: "岗位管理",
    singular: "岗位",
    description: "统一维护岗位信息，明确团队分工",
    icon: "briefcase",
    columns: [
      name("岗位名称"),
      code,
      { key: "sort", label: "排序" },
      statusCol,
      created,
      { key: "remark", label: "备注" },
    ],
    fields: [
      name("岗位名称"),
      code,
      { key: "sort", label: "显示排序", type: "number" },
      status,
      remark,
    ],
  },
  projects: {
    title: "项目管理",
    singular: "项目",
    description: "从计划到交付，掌握每个项目的进展",
    icon: "folder",
    columns: [
      name("项目名称"),
      { key: "code", label: "项目编号" },
      { key: "type", label: "业务类型", kind: "tag" },
      { key: "leader", label: "负责人" },
      { key: "progress", label: "项目进度", kind: "progress" },
      { key: "status", label: "状态", kind: "status" },
      { key: "deadline", label: "截止日期" },
    ],
    fields: [
      name("项目名称"),
      code,
      {
        key: "type",
        label: "业务类型",
        type: "select",
        options: ["系统管理", "业务管理", "文件管理", "日志管理", "其他"],
      },
      { key: "leader", label: "负责人", required: true },
      {
        key: "department",
        label: "所属部门",
        type: "select",
        options: ["研发部", "产品设计部", "市场营销部"],
      },
      { key: "progress", label: "完成进度（%）", type: "number" },
      { key: "deadline", label: "截止日期", required: true },
      {
        key: "status",
        label: "项目状态",
        type: "select",
        options: ["进行中", "已完成", "待开始", "已暂停"],
      },
      remark,
    ],
  },
  operations: {
    title: "操作日志",
    singular: "日志",
    description: "完整记录操作轨迹，让每一次变更都有迹可循",
    icon: "file-text",
    readonly: true,
    columns: [
      { key: "id", label: "ID" },
      { key: "operator", label: "操作人" },
      { key: "action", label: "操作类型", kind: "tag" },
      { key: "name", label: "操作内容" },
      { key: "createdAt", label: "操作时间" },
      { key: "ip", label: "IP 地址" },
      { key: "status", label: "结果", kind: "status" },
    ],
    fields: [],
  },
  logins: {
    title: "登录日志",
    singular: "记录",
    description: "查看账号登录活动，及时发现安全风险",
    icon: "log-in",
    readonly: true,
    columns: [
      { key: "username", label: "登录账号", kind: "avatar" },
      { key: "ip", label: "IP 地址" },
      { key: "location", label: "登录地点" },
      { key: "browser", label: "浏览器" },
      { key: "os", label: "操作系统" },
      statusCol,
      { key: "createdAt", label: "登录时间" },
    ],
    fields: [],
  },
  sessions: {
    title: "在线用户",
    singular: "会话",
    description: "实时查看活跃会话，管理账号登录状态",
    icon: "users",
    readonly: true,
    columns: [
      { key: "username", label: "用户账号", kind: "avatar" },
      { key: "name", label: "姓名" },
      { key: "department", label: "部门" },
      { key: "ip", label: "登录 IP" },
      { key: "browser", label: "浏览器" },
      { key: "createdAt", label: "登录时间" },
      statusCol,
    ],
    fields: [],
  },
};
export const navigation = [
  { label: "首页", icon: "home", path: "/dashboard" },
  {
    label: "系统管理",
    icon: "settings",
    children: [
      { label: "用户管理", icon: "user", path: "/system/users" },
      { label: "角色管理", icon: "shield", path: "/system/roles" },
      { label: "菜单管理", icon: "list-tree", path: "/system/menus" },
      { label: "部门管理", icon: "network", path: "/system/departments" },
      { label: "岗位管理", icon: "briefcase", path: "/system/posts" },
    ],
  },
  {
    label: "业务管理",
    icon: "briefcase",
    children: [
      { label: "订单管理", icon: "list", path: "/business/orders" },
      { label: "产品管理", icon: "package", path: "/business/products" },
    ],
  },
  {
    label: "日志管理",
    icon: "file-text",
    children: [
      { label: "操作日志", icon: "file-text", path: "/audit/operations" },
      { label: "登录日志", icon: "log-in", path: "/audit/logins" },
    ],
  },
  {
    label: "文件管理",
    icon: "folder",
    children: [
      { label: "图片管理", icon: "image", path: "/images" },
      { label: "视频管理", icon: "video", path: "/videos" },
      { label: "附件管理", icon: "paperclip", path: "/attachments" },
    ],
  },
  {
    label: "系统监控",
    icon: "monitor",
    children: [
      { label: "服务监控", icon: "activity", path: "/monitor/overview" },
      { label: "在线用户", icon: "users", path: "/monitor/sessions" },
      { label: "缓存监控", icon: "database", path: "/monitor/cache" },
    ],
  },
];
export const allLinks = navigation
  .flatMap((n) => n.children ?? [n])
  .filter((n) => n.path);
