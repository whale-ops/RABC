<script setup lang="ts">
/** 系统配置页面：按 Tab 编辑租户品牌、登录验证码和注册规则；分组保存并校验并发版本。 */
import { reactive, ref, onMounted, computed } from "vue";
import { Button, Input, Toast } from "../ui";
import AppIcon from "../components/AppIcon.vue";
import StorageSettings from "../components/StorageSettings.vue";
import { session, can } from "../api/session";
import { errorMessage } from "../api/request";
import {
  systemConfigApi,
  defaultConfig,
  applyBranding,
  notifyConfigChanged,
} from "../api/system-config";
const tab = ref<"basic" | "login" | "registration" | "storage">("basic");
const tabs = [
  { key: "basic", label: "基本配置", icon: "settings" },
  { key: "login", label: "登录配置", icon: "shield" },
  { key: "registration", label: "注册配置", icon: "users" },
  { key: "storage", label: "存储配置", icon: "database" },
] as const;
const form = reactive({ ...defaultConfig, version: 0 });
const loading = ref(true),
  saving = ref(false),
  error = ref(""),
  loaded = ref(false);
function tabKey(event: KeyboardEvent, index: number) {
  const keys = ["ArrowLeft", "ArrowRight", "Home", "End"];
  if (!keys.includes(event.key)) return;
  event.preventDefault();
  const next =
    event.key === "Home"
      ? 0
      : event.key === "End"
        ? tabs.length - 1
        : (index + (event.key === "ArrowRight" ? 1 : -1) + tabs.length) %
          tabs.length;
  const item = tabs[next];
  if (item) {
    tab.value = item.key;
    document.getElementById(`tab-${item.key}`)?.focus();
  }
}
const editable = computed(
  () => !!session.user?.admin && can("system:config:update"),
);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    Object.assign(form, await systemConfigApi.get());
    loaded.value = true;
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    loading.value = false;
  }
}
async function save() {
  if (saving.value || !editable.value || tab.value === "storage") return;
  if (
    tab.value === "basic" &&
    (!form.systemName.trim() || !form.systemDescription.trim())
  )
    return Toast.warning("请填写系统名称和描述");
  saving.value = true;
  error.value = "";
  try {
    const data =
      tab.value === "basic"
        ? {
            version: form.version,
            systemName: form.systemName,
            systemLogo: form.systemLogo,
            systemDescription: form.systemDescription,
          }
        : tab.value === "login"
          ? {
              version: form.version,
              captchaEnabled: form.captchaEnabled,
              captchaLength: form.captchaLength,
              captchaExpireSeconds: form.captchaExpireSeconds,
            }
          : {
              version: form.version,
              registrationEnabled: form.registrationEnabled,
              registrationCaptchaEnabled: form.registrationCaptchaEnabled,
            };
    const saved = await systemConfigApi.save(tab.value, data);
    // 保存当前组后保留其他 Tab 尚未提交的草稿，版本号同步到最新。
    form.version = saved.version;
    if (tab.value === "basic") {
      form.systemLogo = saved.systemLogo;
      form.systemName = saved.systemName;
      form.systemDescription = saved.systemDescription;
    }
    applyBranding(saved);
    if (session.user) notifyConfigChanged(session.user.tenant.code);
    Toast.success("配置已保存并生效");
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    saving.value = false;
  }
}
async function uploadLogo(event: Event) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0];
  input.value = "";
  if (!file) return;
  if (
    !["image/png", "image/jpeg"].includes(file.type) ||
    file.size > 256 * 1024
  )
    return Toast.warning("请选择不超过 256 KB 的 PNG 或 JPEG 图片");
  try {
    const data = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = reject;
      reader.readAsDataURL(file);
    });
    const image = new Image();
    image.src = data;
    await image.decode();
    if (image.width > 1024 || image.height > 1024)
      return Toast.warning("Logo 宽高不能超过 1024 像素");
    form.systemLogo = data;
  } catch {
    Toast.error("图片读取失败，请重新选择");
  }
}
onMounted(load);
</script>
<template>
  <div class="system-config-page">
    <div class="page-heading">
      <div>
        <h1>系统配置</h1>
        <p>统一管理系统品牌、登录注册规则与文件存储</p>
      </div>
      <span class="config-tenant"
        ><AppIcon name="shield" :size="17" />{{
          session.user?.tenant.name
        }}</span
      >
    </div>
    <section class="panel config-panel" :aria-busy="loading">
      <div class="file-tabs config-tabs" role="tablist" aria-label="配置分类">
        <button
          v-for="(t, index) in tabs"
          :key="t.key"
          :id="`tab-${t.key}`"
          role="tab"
          :aria-selected="tab === t.key"
          :tabindex="tab === t.key ? 0 : -1"
          @keydown="tabKey($event, index)"
          :aria-controls="`panel-${t.key}`"
          :class="{ active: tab === t.key }"
          @click="tab = t.key"
        >
          <AppIcon :name="t.icon" :size="18" />{{ t.label }}
        </button>
      </div>
      <p v-if="loading" class="config-loading" role="status">正在加载配置…</p>
      <StorageSettings v-else-if="tab === 'storage'" />
      <form
        v-else
        :id="`panel-${tab}`"
        role="tabpanel"
        :aria-labelledby="`tab-${tab}`"
        @submit.prevent="save"
      >
        <div v-if="tab === 'basic'" class="config-basic">
          <div class="config-fields">
            <h3>基本信息</h3>
            <p class="muted">保存后同步应用到侧栏、登录页面和浏览器标题。</p>
            <div class="form-field">
              <label for="system-name">系统名称</label
              ><Input
                id="system-name"
                aria-label="系统名称"
                :maxLength="40"
                :disabled="!editable"
                :value="form.systemName"
                :onChange="(v: string) => (form.systemName = v)"
              /><small class="muted">最多 40 个字符</small>
            </div>
            <div class="form-field">
              <label>系统 Logo</label>
              <div class="config-logo-editor">
                <img :src="form.systemLogo" alt="当前系统 Logo" />
                <div>
                  <label class="config-upload" :class="{ disabled: !editable }"
                    ><AppIcon name="upload" :size="17" />选择图片<input
                      type="file"
                      accept="image/png,image/jpeg"
                      aria-label="上传系统 Logo"
                      :disabled="!editable"
                      @change="uploadLogo" /></label
                  ><Button
                    :disabled="!editable"
                    :onClick="() => (form.systemLogo = '/favicon.svg')"
                    >恢复默认</Button
                  >
                </div>
              </div>
              <small class="muted"
                >PNG / JPEG，最大 256 KB，宽高不超过 1024 像素</small
              >
            </div>
            <div class="form-field">
              <label for="system-description">系统描述</label
              ><textarea
                id="system-description"
                aria-label="系统描述"
                class="config-description"
                rows="3"
                maxlength="120"
                :disabled="!editable"
                v-model="form.systemDescription"
              /><small class="muted">简洁介绍您的系统，最多 120 个字符</small>
            </div>
          </div>
          <aside class="config-brand-preview">
            <span class="config-preview-label">品牌预览</span
            ><img :src="form.systemLogo" alt="品牌预览 Logo" /><strong>{{
              form.systemName || "系统名称"
            }}</strong>
            <p>{{ form.systemDescription || "系统描述" }}</p>
            <span class="config-preview-tag"
              ><span class="online-dot" /> 工作空间已就绪</span
            >
          </aside>
        </div>
        <div v-else-if="tab === 'login'" class="config-fields">
          <h3>登录安全</h3>
          <p class="muted">按需开启图片验证码，减少自动化登录尝试。</p>
          <div class="config-switch-row">
            <div>
              <label for="login-captcha-enabled" class="config-switch-label"
                >登录验证码</label
              >
              <p>开启后，登录必须通过图片验证码校验。</p>
            </div>
            <button
              type="button"
              role="switch"
              id="login-captcha-enabled"
              aria-label="登录验证码"
              :aria-checked="form.captchaEnabled"
              class="config-toggle"
              :class="{ active: form.captchaEnabled }"
              :disabled="!editable"
              @click="form.captchaEnabled = !form.captchaEnabled"
            >
              <span />
            </button>
          </div>
          <div class="config-number-grid">
            <div class="form-field">
              <label for="captcha-length">验证码长度</label
              ><select
                id="captcha-length"
                v-model.number="form.captchaLength"
                :disabled="!editable"
              >
                <option :value="4">4 位</option>
                <option :value="5">5 位</option>
                <option :value="6">6 位</option>
              </select>
            </div>
            <div class="form-field">
              <label for="captcha-expiry">验证码有效期（秒）</label
              ><input
                id="captcha-expiry"
                v-model.number="form.captchaExpireSeconds"
                type="number"
                min="60"
                max="300"
                required
                :disabled="!editable"
              /><small class="muted">60–300 秒，登录和注册共用此规则</small>
            </div>
          </div>
          <div class="config-notice">
            <AppIcon name="lock" :size="18" />
            <p>
              验证码一次有效，不区分字母大小写。登录接口保留每分钟 10
              次的账号尝试限制。
            </p>
          </div>
        </div>
        <div v-else class="config-fields">
          <h3>注册规则</h3>
          <p class="muted">控制登录页的注册入口，以及注册申请的验证方式。</p>
          <div class="config-switch-row">
            <div>
              <label for="registration-enabled" class="config-switch-label"
                >允许用户注册</label
              >
              <p>关闭后隐藏注册入口，后端同时停止接收新的注册申请。</p>
            </div>
            <button
              type="button"
              role="switch"
              id="registration-enabled"
              aria-label="允许用户注册"
              :aria-checked="form.registrationEnabled"
              class="config-toggle"
              :class="{ active: form.registrationEnabled }"
              :disabled="!editable"
              @click="form.registrationEnabled = !form.registrationEnabled"
            >
              <span />
            </button>
          </div>
          <div class="config-switch-row">
            <div>
              <label
                for="registration-captcha-enabled"
                class="config-switch-label"
                >注册验证码</label
              >
              <p>
                提交注册申请前校验图片验证码，使用登录配置中的长度和有效期。
              </p>
            </div>
            <button
              type="button"
              role="switch"
              id="registration-captcha-enabled"
              aria-label="注册验证码"
              :aria-checked="form.registrationCaptchaEnabled"
              class="config-toggle"
              :class="{ active: form.registrationCaptchaEnabled }"
              :disabled="!editable || !form.registrationEnabled"
              @click="
                form.registrationCaptchaEnabled =
                  !form.registrationCaptchaEnabled
              "
            >
              <span />
            </button>
          </div>
          <div class="config-notice">
            <AppIcon name="shield" :size="18" />
            <p>
              注册申请仍需管理员审核通过后才能登录。关闭注册不影响已有账号登录和待处理申请的审核。
            </p>
          </div>
        </div>
        <p v-if="error" class="login-error" role="alert">
          {{ error }}
          <button type="button" class="link-button" @click="load">
            重新加载
          </button>
        </p>
        <div class="config-footer">
          <Button
            theme="solid"
            htmlType="submit"
            :loading="saving"
            :disabled="!editable || saving || !loaded"
            >保存{{ tabs.find((t) => t.key === tab)?.label }}</Button
          ><span class="muted">{{
            editable
              ? "仅保存当前 Tab，保存后立即生效"
              : "仅租户管理员可修改系统配置"
          }}</span>
        </div>
      </form>
    </section>
  </div>
</template>
<style scoped>
.config-description {
  width: 100%;
  padding: 10px 12px;
  resize: vertical;
  min-height: 90px;
  border: 1px solid #dce5f2;
  border-radius: 6px;
  color: #314968;
  font: inherit;
  line-height: 1.6;
  box-sizing: border-box;
}
.config-description:focus {
  outline: 2px solid #91bfff;
  outline-offset: 1px;
}
.config-toggle {
  position: relative;
  width: 40px;
  height: 24px;
  border: 0;
  border-radius: 20px;
  padding: 0;
  background: #cbdcf6;
  cursor: pointer;
  flex-shrink: 0;
  transition: background 0.15s;
}
.config-toggle > span {
  position: absolute;
  width: 18px;
  height: 18px;
  left: 3px;
  top: 3px;
  border-radius: 50%;
  background: white;
  box-shadow: 0 2px 4px #1a477b20;
  transition: transform 0.15s;
}
.config-toggle.active {
  background: #1677ff;
}
.config-toggle.active > span {
  transform: translateX(16px);
}
.config-toggle:focus-visible {
  outline: 3px solid #9cc8ff;
  outline-offset: 3px;
}
.config-toggle:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.config-panel {
  padding: 0 28px 28px;
}
.config-tabs {
  overflow-x: auto;
  white-space: nowrap;
  scrollbar-width: thin;
  padding-top: 12px;
  margin-bottom: 28px;
}
.config-tabs button {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 20px 24px;
}
.config-tenant {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #7b93b4;
}
.config-basic {
  display: grid;
  grid-template-columns: minmax(0, 680px) minmax(220px, 1fr);
  gap: 60px;
}
.config-fields {
  max-width: 760px;
}
.config-fields h3 {
  font-size: 20px;
  margin: 0 0 10px;
}
.config-fields > .muted {
  margin-bottom: 32px;
}
.config-fields .form-field {
  margin-top: 26px;
}
.config-fields small {
  display: block;
  margin-top: 8px;
  font-size: 12px;
}
.config-logo-editor {
  display: flex;
  gap: 22px;
  align-items: center;
}
.config-logo-editor > img {
  width: 88px;
  height: 88px;
  object-fit: contain;
  padding: 12px;
  border: 1px solid #e7edf6;
  border-radius: 16px;
  background: #f8faff;
}
.config-logo-editor > div {
  display: flex;
  gap: 12px;
  align-items: center;
}
.config-upload {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  color: #1677ff;
  border: 1px solid #dbe6f6;
  border-radius: 6px;
  cursor: pointer;
  overflow: hidden;
}
.config-upload input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
  width: 100%;
}
.config-upload.disabled {
  opacity: 0.5;
}
.config-brand-preview {
  display: flex;
  align-items: center;
  flex-direction: column;
  align-self: start;
  min-height: 330px;
  padding: 24px;
  border-radius: 16px;
  background: linear-gradient(145deg, #edf5ff, #f8fbff);
  border: 1px solid #e5efff;
  text-align: center;
}
.config-preview-label {
  align-self: flex-start;
  font-size: 12px;
  color: #8ca4c4;
  letter-spacing: 2px;
}
.config-brand-preview > img {
  width: 76px;
  height: 76px;
  object-fit: contain;
  margin: 36px 0 20px;
}
.config-brand-preview > strong {
  font-size: 26px;
  color: #142c50;
  overflow-wrap: anywhere;
}
.config-brand-preview > p {
  font-size: 14px;
  color: #819abc;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.config-preview-tag {
  font-size: 12px;
  color: #7d95b5;
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 24px;
}
.config-switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 30px;
  padding: 24px 0;
  border-bottom: 1px solid #edf2f8;
}
.config-switch-label {
  font-weight: 600;
  cursor: pointer;
}
.config-switch-row strong {
  font-size: 15px;
}
.config-switch-row p {
  font-size: 13px;
  color: #8b9db7;
  line-height: 1.7;
  margin: 8px 0 0;
}
.config-number-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
}
.config-number-grid input,
.config-number-grid select {
  width: 100%;
  height: 40px;
  padding: 0 12px;
  border: 1px solid #dce5f2;
  border-radius: 6px;
  background: #fff;
  color: #314968;
  font: inherit;
}
.config-notice {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-top: 28px;
  padding: 16px 20px;
  background: #f5f9ff;
  color: #7993b5;
  border-radius: 8px;
  font-size: 13px;
}
.config-notice svg {
  flex-shrink: 0;
  margin-top: 2px;
}
.config-notice p {
  margin: 0;
  line-height: 1.8;
}
.config-footer {
  display: flex;
  gap: 20px;
  align-items: center;
  margin-top: 38px;
  padding-top: 24px;
  border-top: 1px solid #edf2f8;
}
.config-footer .muted {
  font-size: 13px;
}
.config-loading {
  padding: 60px;
  text-align: center;
  color: #8b9db7;
}
@media (max-width: 1100px) {
  .config-basic {
    grid-template-columns: 1fr;
    gap: 25px;
  }
  .config-brand-preview {
    display: none;
  }
}
@media (max-width: 600px) {
  .config-tabs {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    overflow: visible;
    white-space: normal;
  }
  .config-tabs button {
    width: 100%;
    justify-content: center;
  }

  .config-tabs button {
    padding: 16px 6px;
    font-size: 13px;
    gap: 6px;
  }
  .config-panel {
    padding: 0 16px 20px;
  }
  .config-number-grid {
    grid-template-columns: 1fr;
  }
  .config-footer {
    flex-wrap: wrap;
  }
  .config-tenant {
    display: none;
  }
  .config-logo-editor,
  .config-logo-editor > div {
    flex-wrap: wrap;
  }
}
</style>
