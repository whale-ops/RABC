<script setup lang="ts">
/**
 * 展示当前账号个人资料和安全设置，支持保存资料与主动修改密码。
 * 关联模块：auth.ts 调用 AccountService 接口并更新会话；真实改密后清理旧登录，演示模式仅操作本地展示状态。
 */
import { reactive, ref } from "vue";
import { Button, Input, TextArea, Toast } from "../ui";
import AppIcon from "../components/AppIcon.vue";
import { ui, audit } from "../data/store";
import { authApi } from "../api/auth";
import { session } from "../api/session";
import { useMock, errorMessage } from "../api/request";
import { useRouter } from "vue-router";
const router = useRouter();
const form = reactive({ ...ui.profile });
const tab = ref("基本资料");
const error = ref("");
const password = reactive({ old: "", next: "", confirm: "" });
async function save() {
  if (!form.name.trim()) return Toast.warning("请输入姓名");
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email))
    return Toast.warning("请输入正确的邮箱");
  if (!/^1\d{10}$/.test(form.phone)) return Toast.warning("请输入正确的手机号");
  if (!useMock) {
    try {
      await authApi.profile({
        name: form.name,
        email: form.email,
        phone: form.phone,
        bio: form.bio,
      });
      Toast.success("个人资料已保存");
    } catch (e) {
      Toast.error(errorMessage(e));
    }
    return;
  }
  Object.assign(ui.profile, form);
  audit("修改", "更新个人资料");
  Toast.success("个人资料已保存");
}
/** 真实模式交给后台检查旧密码并撤销会话；演示分支只验证本地交互。 */
async function changePassword() {
  error.value = "";
  if (!useMock) {
    if (password.next !== password.confirm) {
      error.value = "两次输入的密码不一致";
      return;
    }
    try {
      await authApi.password(password.old, password.next);
      Toast.success("密码已修改，请重新登录");
      router.replace("/login");
    } catch (e) {
      error.value = errorMessage(e);
    }
    return;
  }
  if (password.old !== "admin123") {
    error.value = "演示环境当前密码为 admin123";
    return;
  }
  if (password.next.length < 8) {
    error.value = "新密码至少 8 位";
    return;
  }
  if (password.next !== password.confirm) {
    error.value = "两次输入的密码不一致";
    return;
  }
  password.old = "";
  password.next = "";
  password.confirm = "";
  Toast.info(
    "校验通过。当前为前端演示，实际改密需要接入后端，演示密码仍为 admin123。",
  );
}
</script>
<template>
  <div>
    <div class="page-heading">
      <div>
        <h1>个人中心</h1>
        <p>管理个人资料与账号偏好</p>
      </div>
    </div>
    <div class="profile-layout">
      <aside class="panel profile-card">
        <div class="profile-cover" />
        <span class="profile-avatar">{{ ui.profile.name.slice(0, 1) }}</span>
        <h2>{{ ui.profile.name }}</h2>
        <span class="status-pill blue">{{
          useMock ? "超级管理员" : session.user?.role
        }}</span>
        <p>{{ ui.profile.bio }}</p>
        <dl>
          <dt><AppIcon name="user" :size="17" />用户账号</dt>
          <dd>{{ useMock ? "admin" : session.user?.username }}</dd>
          <dt><AppIcon name="network" :size="17" />所属部门</dt>
          <dd>{{ ui.profile.department }}</dd>
          <dt><AppIcon name="mail" :size="17" />邮箱</dt>
          <dd>{{ ui.profile.email }}</dd>
          <dt><AppIcon name="phone" :size="17" />手机号</dt>
          <dd>{{ ui.profile.phone }}</dd>
        </dl>
      </aside>
      <section class="panel profile-editor">
        <div class="file-tabs">
          <button
            v-for="t in ['基本资料', '安全设置']"
            :key="t"
            :class="{ active: tab === t }"
            @click="tab = t"
          >
            {{ t }}
          </button>
        </div>
        <form v-if="tab === '基本资料'" @submit.prevent="save">
          <h3>基本资料</h3>
          <p class="muted">更新您的个人信息，让团队更好地了解您。</p>
          <div
            v-for="f in [
              { key: 'name', label: '姓名' },
              { key: 'email', label: '电子邮箱' },
              { key: 'phone', label: '手机号码' },
            ]"
            :key="f.key"
            class="form-field"
          >
            <label :for="f.key">{{ f.label }}</label
            ><Input
              :id="f.key"
              :value="(form as any)[f.key]"
              :onChange="(v: string) => ((form as any)[f.key] = v)"
            />
          </div>
          <div class="form-field">
            <label for="bio">个人简介</label
            ><TextArea
              id="bio"
              :value="form.bio"
              :rows="3"
              :onChange="(v: string) => (form.bio = v)"
            />
          </div>
          <Button theme="solid" htmlType="submit">保存修改</Button>
        </form>
        <form v-else @submit.prevent="changePassword">
          <h3>修改密码</h3>
          <p class="muted">
            {{
              useMock
                ? "前端演示仅校验表单，实际密码变更需要后端支持。"
                : session.user?.mustChangePassword
                  ? "建议在此修改初始密码。至少 12 位，包含字母和数字。"
                  : "修改密码后，所有已登录会话将失效。至少 12 位，包含字母和数字。"
            }}
          </p>
          <div
            v-for="f in [
              { key: 'old', label: '当前密码' },
              { key: 'next', label: '新密码' },
              { key: 'confirm', label: '确认新密码' },
            ]"
            :key="f.key"
            class="form-field"
          >
            <label :for="f.key">{{ f.label }}</label
            ><Input
              :id="f.key"
              mode="password"
              :value="(password as any)[f.key]"
              :onChange="(v: string) => ((password as any)[f.key] = v)"
            />
          </div>
          <p v-if="error" class="field-error">{{ error }}</p>
          <Button theme="solid" htmlType="submit">确认修改</Button>
        </form>
      </section>
    </div>
  </div>
</template>
