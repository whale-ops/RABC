<script setup lang="ts">
/**
 * 展示使用说明、常见问题及与当前账号相关的帮助入口。
 * 关联模块：读取路由和 session.ts 区分展示场景，由 Shell.vue 的帮助菜单进入。
 */
import { ref } from "vue";
import { useRouter } from "vue-router";
import AppIcon from "../components/AppIcon.vue";
import { useMock } from "../api/request";
import { can } from "../api/session";
const router = useRouter();
const open = ref(0);
const questions = useMock
  ? [
      {
        q: "如何添加新成员？",
        a: "进入「系统管理 → 用户管理」，点击新增用户，填写账号、姓名、部门和角色后保存。点击表格中的编辑可更新已有成员。",
      },
      {
        q: "如何配置角色权限？",
        a: "在角色管理中点击「权限」，勾选可访问的模块，选择数据权限范围后保存。当前为前端配置演示，实际访问控制需要后端授权服务。",
      },
      {
        q: "文件上传后保存在哪里？",
        a: "文件内容保存在当前浏览器的 IndexedDB 中，业务记录保存在 localStorage。清除浏览器站点数据会丢失这些内容；其他浏览器无法访问。",
      },
      {
        q: "首页和监控是真实数据吗？",
        a: "首页用户与项目总数来自本地演示数据，趋势图、分布、运行时间和服务监控是原型演示值。项目未接入后端服务。",
      },
      {
        q: "如何搜索、排序和导出？",
        a: "管理页面输入关键字后点击查询。点击表头可排序；导出会下载当前筛选结果的 CSV 文件，可使用 Excel 打开。",
      },
      {
        q: "为什么登录后默认进入管理员视图？",
        a: "这是完整前端演示，默认可以浏览全部模块。退出后可使用 admin / admin123 重新体验登录流程，租户编码默认 star。",
      },
    ]
  : [
      {
        q: "如何添加新成员？",
        a: "进入系统管理 → 用户管理，填写账号、姓名、初始密码、部门，选择一个或多个角色及岗位。也可在登录页提交注册申请，由管理员在用户管理的账号申请中审核。密码可登录后在个人中心的安全设置中修改。",
      },
      {
        q: "如何配置角色权限？",
        a: "租户管理员可在角色管理中分配页面和操作权限，并选择全部数据、本部门及下级、本部门、自定义部门或仅本人。权限变更立即影响后续请求。",
      },
      {
        q: "为什么看不到某些菜单或按钮？",
        a: "可见菜单和操作取决于当前账号的有效角色。请联系租户管理员调整授权；不能通过修改地址绕过访问权限。",
      },
      {
        q: "为什么删除被拒绝？",
        a: "内置管理员受到保护。仍有成员、下级节点或授权引用的部门、岗位、角色不能直接删除，请先调整关联。",
      },
      {
        q: "如何搜索、排序和导出？",
        a: "输入关键字后点击查询。支持的表头可排序；导出下载当前筛选范围内的 CSV。单次导出最多一万条，超出时请缩小范围。",
      },
      {
        q: "密码重置后会发生什么？",
        a: "该用户现有会话立即失效，下次使用临时密码登录后必须改密。自己修改密码也会使所有会话失效。",
      },
    ];
</script>
<template>
  <div class="help-page">
    <div class="help-banner">
      <span class="notice-icon"><AppIcon name="book" :size="30" /></span>
      <h1>您好，有什么可以帮助您？</h1>
      <p>了解 StarAdmin 的功能，快速开启您的工作空间。</p>
    </div>
    <div class="help-cards">
      <button
        v-if="can('system:user:list')"
        class="panel"
        @click="router.push('/system/users')"
      >
        <AppIcon name="users" :size="26" /><strong>成员与组织</strong
        ><span>管理用户、部门与岗位</span
        ><AppIcon name="right" :size="18" /></button
      ><button
        v-if="can('system:role:list')"
        class="panel"
        @click="router.push('/system/roles')"
      >
        <AppIcon name="shield" :size="26" /><strong>角色与权限</strong
        ><span>灵活配置访问控制</span
        ><AppIcon name="right" :size="18" /></button
      ><button v-if="useMock" class="panel" @click="router.push('/files')">
        <AppIcon name="folder" :size="26" /><strong>文件与资源</strong
        ><span>上传、预览与下载文件</span><AppIcon name="right" :size="18" />
      </button>
    </div>
    <section class="panel faq-panel">
      <h2>常见问题</h2>
      <div v-for="(q, i) in questions" :key="q.q" class="faq-item">
        <button :aria-expanded="open === i" @click="open = open === i ? -1 : i">
          {{ q.q }}<AppIcon :name="open === i ? 'down' : 'right'" :size="17" />
        </button>
        <p v-if="open === i">{{ q.a }}</p>
      </div>
    </section>
    <div class="help-bottom">
      <img src="/favicon.svg" alt="" /><strong>StarAdmin</strong
      ><span>Vue 3 + Semi UI Vue · v1.0.0</span
      ><a
        href="https://storyset.com/illustration/dashboard/amico"
        target="_blank"
        rel="noopener noreferrer"
        >Login illustration by Storyset</a
      >
    </div>
  </div>
</template>
