/**
 * 创建 Vue 应用，装配路由和全局样式，并响应统一的登录失效事件。
 * 关联模块：App.vue 为根组件；request.ts 发出认证失效事件，session.ts 和 router.ts 清理身份并返回登录页。
 */
import { createApp } from "vue";
import App from "./App.vue";
import { router } from "./router";
import "./styles.css";
import { clearSession } from "./api/session";
createApp(App).use(router).mount("#app");
window.addEventListener("star:unauthorized", () => {
  clearSession();
  sessionStorage.setItem("star-signed-out", "true");
  router.replace("/login");
});
