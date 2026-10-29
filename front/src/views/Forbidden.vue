<script setup lang="ts">
/**
 * 展示已登录但没有当前页面权限时的提示，并提供返回入口。
 * 关联模块：router.ts 根据授权菜单跳转到此页，使用公共按钮组件帮助用户离开无权访问的路径。
 */
import { Button } from "../ui";
</script>
<template>
  <section class="panel empty-state">
    <h1>当前页面不可访问</h1>
    <p>该页面尚未接入后台，或当前账号没有访问权限。</p>
    <RouterLink to="/profile"
      ><Button theme="solid">前往个人中心</Button></RouterLink
    >
  </section>
</template>
