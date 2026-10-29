<script setup lang="ts">
/**
 * 按业务状态选择颜色，统一展示启停、成功失败及操作类型标签。
 * 关联模块：系统管理、日志和原型页面共同使用；只负责展示，不改变状态或权限。
 */
import { computed } from "vue";
const props = defineProps<{ value: string; dot?: boolean }>();
const tone = computed(() =>
  ["启用", "成功", "在线", "登录", "已完成"].includes(props.value)
    ? "green"
    : ["停用", "失败", "删除", "移除文件记录", "已暂停"].includes(props.value)
      ? "red"
      : ["修改", "待开始", "目录"].includes(props.value)
        ? "orange"
        : ["设置", "审计人员", "按钮"].includes(props.value)
          ? "purple"
          : "blue",
);
</script>
<template>
  <span class="status-pill" :class="tone"><i v-if="dot" />{{ value }}</span>
</template>
