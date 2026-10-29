/**
 * 配置 Vue 编译、开发服务端口及 API 反向代理。
 * 关联模块：开发请求通过 /api 转发至 Spring Boot；环境变量可指定代理目标，package.json 的开发和构建命令使用本配置。
 */
import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target:
          loadEnv(mode, process.cwd(), "").VITE_API_PROXY_TARGET ||
          "http://127.0.0.1:8080",
        changeOrigin: true,
      },
    },
  },
  build: { chunkSizeWarningLimit: 1500 },
}));
