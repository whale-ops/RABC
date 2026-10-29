<script setup lang="ts">
/**
 * 应用根组件，为所有路由页面统一提供 Semi UI 上下文。
 * 关联模块：main.ts 挂载本组件，router.ts 决定展示登录页或 Shell 布局。
 */
import { ConfigProvider } from "./ui";
</script>
<template>
  <ConfigProvider><RouterView /></ConfigProvider>
</template>
