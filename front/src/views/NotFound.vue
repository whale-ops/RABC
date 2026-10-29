<script setup lang="ts">
/**
 * 展示路由未匹配时的页面不存在提示，并提供返回操作。
 * 关联模块：由 router.ts 的兜底路由加载，使用 Vue Router 和公共按钮组件完成导航。
 */
import { Button } from "../ui";
import { useRouter } from "vue-router";
const router = useRouter();
</script>
<template>
  <section class="panel not-found">
    <strong>404</strong>
    <h1>页面暂时迷路了</h1>
    <p>您访问的页面不存在，或已被移动。</p>
    <Button theme="solid" :onClick="() => router.push('/dashboard')"
      >返回首页</Button
    >
  </section>
</template>
