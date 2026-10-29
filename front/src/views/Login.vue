<script setup lang="ts">
/**
 * 展示登录、注册申请和管理员协助找回密码表单，处理校验与登录后的跳转。
 * 关联模块：调用 api/auth.ts；注册审核由 AccountRequests.vue 完成，个人密码修改位于 Profile.vue。
 */
import { ref, reactive, computed, watch, onBeforeUnmount } from "vue";
import { useRouter, useRoute } from "vue-router";
import { Input, Button, Toast } from "../ui";
import AppIcon from "../components/AppIcon.vue";
import { authApi } from "../api/auth";
import { errorMessage, useMock } from "../api/request";
import {
  systemConfigApi,
  branding,
  applyBranding,
  defaultConfig,
  subscribeConfigChanges,
} from "../api/system-config";
const router = useRouter();
const route = useRoute();
const username = ref("");
const password = ref("");
const tenant = ref("");
const loading = ref(false);
const error = ref("");
const mode = ref<"login" | "register" | "forgot">("login");
const details = reactive({ name: "", email: "", phone: "", confirm: "" });
const success = ref("");
const config = reactive({ ...defaultConfig });
const configBusy = ref(true),
  configError = ref(""),
  captchaBusy = ref(false);
const captcha = reactive({
  id: "",
  image: "",
  code: "",
  expiresAt: 0,
  expired: false,
});
const captchaError = ref("");
const captchaButton = ref<HTMLButtonElement>();
const captchaNeeded = computed(() =>
  mode.value === "login"
    ? config.captchaEnabled
    : mode.value === "register" && config.registrationCaptchaEnabled,
);
let revision = 0,
  challengeRevision = 0,
  timer: ReturnType<typeof setTimeout> | undefined,
  captchaExpiryTimer: ReturnType<typeof setTimeout> | undefined;
function clearCaptcha() {
  clearTimeout(captchaExpiryTimer);
  Object.assign(captcha, {
    id: "",
    image: "",
    code: "",
    expiresAt: 0,
    expired: false,
  });
  captchaError.value = "";
}
/** 按签发响应的有效期更新框内状态；切回后台标签页时也校验截止时间，不依赖倒计时文案。 */
function syncCaptchaExpiry() {
  if (captcha.expired) return true;
  if (!captcha.expiresAt || Date.now() < captcha.expiresAt) return false;
  clearTimeout(captchaExpiryTimer);
  Object.assign(captcha, {
    id: "",
    image: "",
    code: "",
    expiresAt: 0,
    expired: true,
  });
  return true;
}
function captchaImageFailed() {
  clearCaptcha();
  captchaError.value = "验证码图片加载失败，请点击重试";
}
async function refreshCaptcha() {
  const seq = ++challengeRevision,
    current = revision,
    requestedAt = Date.now();
  captchaBusy.value = false;
  clearCaptcha();
  if (!captchaNeeded.value || mode.value === "forgot" || !tenant.value.trim())
    return;
  captchaBusy.value = true;
  try {
    const result = await authApi.captcha(
      tenant.value.trim(),
      mode.value as "login" | "register",
    );
    if (seq !== challengeRevision || current !== revision) return;
    if (result.enabled) {
      captcha.id = result.captchaId || "";
      captcha.image = result.image || "";
      captcha.expiresAt =
        requestedAt + (result.expiresIn ?? config.captchaExpireSeconds) * 1000;
      captchaExpiryTimer = setTimeout(
        () => {
          if (seq === challengeRevision && current === revision)
            syncCaptchaExpiry();
        },
        Math.max(0, captcha.expiresAt - Date.now()),
      );
    } else {
      await loadConfig();
    }
  } catch (e) {
    if (seq === challengeRevision) captchaError.value = errorMessage(e);
  } finally {
    if (seq === challengeRevision) captchaBusy.value = false;
  }
}
async function loadConfig() {
  const seq = ++revision;
  configBusy.value = true;
  configError.value = "";
  try {
    if (!tenant.value.trim()) return;
    const value = await systemConfigApi.public(tenant.value.trim());
    if (seq !== revision) return;
    Object.assign(config, value);
    applyBranding(value);
    if (!value.registrationEnabled && mode.value === "register")
      switchMode("login");
    await refreshCaptcha();
  } catch (e) {
    if (seq === revision) configError.value = errorMessage(e);
  } finally {
    if (seq === revision) configBusy.value = false;
  }
}
watch(
  tenant,
  () => {
    ++revision;
    ++challengeRevision;
    captchaBusy.value = false;
    configBusy.value = true;
    clearCaptcha();
    clearTimeout(timer);
    timer = setTimeout(() => void loadConfig(), 350);
  },
  { immediate: true },
);
watch(mode, () => {
  if (!configBusy.value) void refreshCaptcha();
});
onBeforeUnmount(() => {
  clearTimeout(timer);
  clearTimeout(captchaExpiryTimer);
  ++revision;
  ++challengeRevision;
});
const stopConfigSync = subscribeConfigChanges(async (changedTenant) => {
  syncCaptchaExpiry();
  const code = tenant.value.trim(),
    current = revision;
  if (
    !code ||
    configBusy.value ||
    loading.value ||
    (changedTenant && changedTenant !== code)
  )
    return;
  try {
    const value = await systemConfigApi.public(code);
    if (current !== revision || tenant.value.trim() !== code) return;
    if (
      (Object.keys(defaultConfig) as (keyof typeof defaultConfig)[]).some(
        (key) => value[key] !== config[key],
      )
    )
      await loadConfig();
    else applyBranding(value);
  } catch {
    /* 提交仍执行服务端实时校验；保留当前验证码，不因切换窗口清空输入。 */
  }
});
onBeforeUnmount(stopConfigSync);
function captchaFields() {
  return { captchaId: captcha.id, captchaCode: captcha.code };
}
async function recoverConfig() {
  await loadConfig();
}

const title = computed(
  () =>
    ({
      login: "登录您的工作空间",
      register: "申请加入工作空间",
      forgot: "找回您的账号密码",
    })[mode.value],
);
function switchMode(next: typeof mode.value) {
  if (next === "register" && !config.registrationEnabled) return;
  if (next === "register") username.value = "";
  mode.value = next;
  error.value = "";
  success.value = "";
  password.value = "";
  details.confirm = "";
}
async function submit() {
  if (
    loading.value ||
    configBusy.value ||
    configError.value ||
    captchaBusy.value
  )
    return;
  if (captchaNeeded.value && syncCaptchaExpiry()) {
    captchaButton.value?.focus();
    return;
  }
  if (captchaNeeded.value && (!captcha.id || !captcha.code.trim())) {
    error.value = "请输入图片验证码";
    return;
  }
  if (mode.value === "login") return login();
  error.value = "";
  success.value = "";
  if (!tenant.value.trim() || !username.value.trim()) {
    error.value = "请输入租户编码和用户账号";
    return;
  }
  if (
    !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(details.email.trim()) ||
    !/^1\d{10}$/.test(details.phone)
  ) {
    error.value = "请填写正确的邮箱和手机号";
    return;
  }
  if (mode.value === "register") {
    if (
      !details.name.trim() ||
      !/^[a-zA-Z0-9_]{3,64}$/.test(username.value.trim())
    ) {
      error.value = "请填写姓名；账号需为 3–64 位字母、数字或下划线";
      return;
    }
    if (
      password.value.length < 12 ||
      new TextEncoder().encode(password.value).length > 72 ||
      !/[a-zA-Z]/.test(password.value) ||
      !/\d/.test(password.value)
    ) {
      error.value = "密码需至少 12 位、不超过 72 字节，包含字母和数字";
      return;
    }
    if (password.value !== details.confirm) {
      error.value = "两次输入的密码不一致";
      return;
    }
  }
  if (useMock) {
    error.value = "演示模式不提交账号申请，请在连接后端的环境中操作";
    return;
  }
  loading.value = true;
  try {
    const common = {
      tenantCode: tenant.value.trim(),
      username: username.value.trim(),
      email: details.email.trim(),
      phone: details.phone,
    };
    if (mode.value === "register") {
      const receipt = await authApi.register({
        ...common,
        name: details.name.trim(),
        password: password.value,
        ...captchaFields(),
      });
      success.value = `${receipt.message}。申请编号：${receipt.requestId}`;
    } else success.value = await authApi.forgotPassword(common);
    password.value = "";
    details.confirm = "";
  } catch (e) {
    error.value = errorMessage(e);
    await recoverConfig();
  } finally {
    loading.value = false;
  }
}
async function login() {
  error.value = "";
  if (!tenant.value.trim() || !username.value.trim() || !password.value) {
    error.value = "请完整填写租户、账号和密码";
    return;
  }
  loading.value = true;
  try {
    await authApi.login({
      tenantCode: tenant.value.trim(),
      username: username.value,
      password: password.value,
      ...captchaFields(),
    });
    sessionStorage.removeItem("star-signed-out");
    Toast.success("登录成功");
    const redirect = String(route.query.redirect || "/dashboard");
    router.push(
      redirect.startsWith("/") && !redirect.startsWith("//")
        ? redirect
        : "/dashboard",
    );
  } catch (reason) {
    error.value = errorMessage(reason);
    await recoverConfig();
  } finally {
    loading.value = false;
  }
}
</script>
<template>
  <div class="login-page">
    <div class="login-brand">
      <img :src="branding.systemLogo" alt="" />
      <div>
        <strong>{{ branding.systemName }}</strong
        ><small>{{ branding.systemDescription }}</small>
      </div>
    </div>
    <div class="login-visual">
      <div class="login-visual-copy">
        <span class="eyebrow">BUILD BETTER. WORK SMARTER.</span>
        <h1>让企业管理，<br />更简单。</h1>
        <p>安全、高效、灵活的一站式企业管理平台</p>
      </div>
      <img
        src="/illustrations/storyset-dashboard.svg"
        alt="Storyset 企业数据仪表盘插画"
      /><a
        class="storyset-credit"
        href="https://storyset.com/illustration/dashboard/amico"
        target="_blank"
        rel="noopener noreferrer"
        >Illustration by Storyset</a
      >
    </div>
    <div class="login-form-wrap">
      <form class="login-form" @submit.prevent="submit">
        <div class="login-greeting">
          欢迎使用 {{ branding.systemName }} <span>✦</span>
        </div>
        <h2>{{ title }}</h2>
        <p>
          {{
            mode === "login"
              ? "请输入账号信息，开启高效工作的一天"
              : mode === "register"
                ? "提交后由企业管理员核实身份、分配部门与角色，审核通过后即可登录。"
                : "填写账号已绑定的邮箱和手机号，管理员核实身份后协助重置。内置管理员无法登录时，请联系系统维护人员。"
          }}
        </p>
        <div v-if="success" class="account-request-success" role="status">
          <AppIcon name="check" :size="20" />
          <p>{{ success }}</p>
          <Button block theme="solid" :onClick="() => switchMode('login')"
            >返回登录</Button
          >
        </div>
        <template v-else>
          <div v-if="mode === 'register'" class="form-field">
            <label for="register-name">姓名</label>
            <Input
              id="register-name"
              aria-label="姓名"
              :value="details.name"
              placeholder="请输入真实姓名"
              :onChange="(v: string) => (details.name = v)"
            />
          </div>
          <div class="form-field">
            <label for="tenant">租户编码</label
            ><Input
              aria-label="租户编码"
              id="tenant"
              :value="tenant"
              size="large"
              placeholder="请输入租户编码"
              :onChange="(v: string) => (tenant = v)"
            />
          </div>
          <div v-if="mode !== 'login'" class="form-field">
            <label for="account-email">联系邮箱</label>
            <Input
              id="account-email"
              aria-label="联系邮箱"
              :value="details.email"
              placeholder="请输入联系邮箱"
              :onChange="(v: string) => (details.email = v)"
            />
          </div>
          <div v-if="mode !== 'login'" class="form-field">
            <label for="account-phone">联系手机号</label>
            <Input
              id="account-phone"
              aria-label="联系手机号"
              :value="details.phone"
              placeholder="请输入联系手机号"
              :onChange="(v: string) => (details.phone = v)"
            />
          </div>
          <div class="form-field">
            <label for="username">用户账号</label
            ><Input
              aria-label="用户账号"
              id="username"
              :value="username"
              size="large"
              placeholder="请输入账号"
              :onChange="(v: string) => (username = v)"
            />
          </div>
          <div v-if="mode !== 'forgot'" class="form-field">
            <label for="password">{{
              mode === "register" ? "设置密码" : "登录密码"
            }}</label
            ><Input
              :aria-label="mode === 'register' ? '设置密码' : '登录密码'"
              id="password"
              :value="password"
              mode="password"
              size="large"
              :placeholder="
                mode === 'register'
                  ? '至少 12 位，包含字母和数字'
                  : '请输入密码'
              "
              :onChange="(v: string) => (password = v)"
            />
          </div>
          <div v-if="mode === 'register'" class="form-field">
            <label for="confirm-password">确认密码</label>
            <Input
              id="confirm-password"
              aria-label="确认密码"
              mode="password"
              :value="details.confirm"
              placeholder="请再次输入密码"
              :onChange="(v: string) => (details.confirm = v)"
            />
          </div>
          <div v-if="captchaNeeded && mode !== 'forgot'" class="form-field">
            <label for="captcha-code">图片验证码</label>
            <div class="login-captcha-row">
              <Input
                id="captcha-code"
                aria-label="图片验证码"
                size="large"
                :maxLength="6"
                :value="captcha.code"
                :placeholder="
                  captcha.expired ? '请先刷新验证码' : '不区分大小写'
                "
                :disabled="
                  captchaBusy || configBusy || captcha.expired || !captcha.id
                "
                autocomplete="off"
                :onChange="(v: string) => (captcha.code = v)"
              />
              <button
                type="button"
                ref="captchaButton"
                class="login-captcha-image"
                :class="{ 'is-expired': captcha.expired }"
                :aria-label="
                  captcha.expired ? '验证码已过期，点击刷新' : '刷新验证码'
                "
                :title="captchaError || '点击刷新验证码'"
                :aria-busy="captchaBusy"
                :disabled="captchaBusy || configBusy || loading"
                @click="refreshCaptcha"
              >
                <img
                  v-if="captcha.image"
                  :src="captcha.image"
                  alt="图片验证码，点击刷新"
                  @error="captchaImageFailed"
                />
                <span
                  v-else
                  class="captcha-state"
                  role="status"
                  aria-live="polite"
                >
                  <AppIcon
                    name="refresh"
                    :size="16"
                    :class="{ 'captcha-spinning': captchaBusy }"
                  />
                  <span
                    >{{
                      captchaBusy
                        ? "正在加载"
                        : captcha.expired
                          ? "验证码已过期"
                          : captchaError
                            ? "加载失败"
                            : "获取验证码"
                    }}<small v-if="!captchaBusy">{{
                      captchaError ? "点击重试" : "点击刷新"
                    }}</small></span
                  >
                </span>
              </button>
            </div>
          </div>
          <p v-if="configError" class="login-error" role="alert">
            配置加载失败：{{ configError }}
            <button type="button" class="link-button" @click="loadConfig">
              重试
            </button>
          </p>
          <p v-if="error" class="login-error" role="alert">
            <AppIcon name="alert" :size="15" />{{ error }}
          </p>
          <Button
            theme="solid"
            htmlType="submit"
            size="large"
            block
            :loading="loading"
            :disabled="configBusy || !!configError"
            :aria-disabled="configBusy || !!configError || captchaBusy || loading"
            >{{
              mode === "login"
                ? "登录"
                : mode === "register"
                  ? "提交注册申请"
                  : "提交找回申请"
            }}<AppIcon name="right" :size="18"
          /></Button>
        </template>
        <div class="login-links">
          <button
            v-if="mode === 'login'"
            type="button"
            :disabled="loading"
            @click="switchMode('forgot')"
          >
            忘记密码？
          </button>
          <button
            v-else
            type="button"
            :disabled="loading"
            @click="switchMode('login')"
          >
            返回登录
          </button>
          <button
            v-if="
              mode !== 'register' &&
              config.registrationEnabled &&
              !configBusy &&
              !configError
            "
            type="button"
            :disabled="loading"
            @click="switchMode('register')"
          >
            注册账号
          </button>
        </div>
        <div v-if="useMock && mode === 'login'" class="demo-account">
          <AppIcon name="help" :size="17" />
          <div>
            <strong>前端演示账号</strong>
            <p>账号：admin　密码：admin123</p>
            <small>仅用于体验页面，无真实身份验证。</small>
          </div>
        </div>
        <p v-else-if="mode === 'login'" class="muted" style="margin-top: 20px">
          使用企业账号登录；可在个人中心的安全设置中修改密码。
        </p>
      </form>
    </div>
    <footer>
      © 2025 {{ branding.systemName }} {{ branding.systemDescription }}
    </footer>
  </div>
</template>

<style scoped>
.login-captcha-row {
  --captcha-height: 46px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 150px;
  gap: 12px;
  align-items: stretch;
  height: var(--captcha-height);
}
.login-captcha-row :deep(.semi-input-wrapper) {
  width: 100%;
  min-width: 0;
  height: var(--captcha-height) !important;
  box-sizing: border-box;
}
.login-captcha-image {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: var(--captcha-height);
  box-sizing: border-box;
  padding: 0;
  border: 1px solid #e3ebf7;
  background: #f1f7ff;
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
  color: #7a94ba;
  font: inherit;
  transition:
    border-color 0.15s,
    background-color 0.15s;
}
.login-captcha-image:hover:not(:disabled) {
  border-color: #91bfff;
  background: #edf5ff;
}
.login-captcha-image:focus-visible {
  outline: 2px solid #91bfff;
  outline-offset: 2px;
}
.login-captcha-image:disabled {
  cursor: wait;
}
.login-captcha-image.is-expired {
  border-color: #dce5f0;
  background: #f5f8fc;
}
.login-captcha-image img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.captcha-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  font-size: 12px;
  line-height: 1.4;
}
.captcha-state > span {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}
.captcha-state small {
  color: #1677ff;
  font-size: 11px;
}
.captcha-spinning {
  animation: captcha-spin 1s linear infinite;
}
@keyframes captcha-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 420px) {
  .login-captcha-row {
    grid-template-columns: minmax(0, 1fr) 128px;
    gap: 10px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .captcha-spinning {
    animation: none;
  }
}
.login-brand img {
  object-fit: contain;
}
.login-brand strong,
.login-brand small {
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
