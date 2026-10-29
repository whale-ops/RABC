<script setup lang="ts">
/** 命名存储配置列表：服务端提供协议和表单字段，多条连接可增删编辑并即时切换上传位置。 */
import { ref, reactive, computed, onMounted } from "vue";
import { Button, Modal, Toast } from "../ui";
import AppIcon from "./AppIcon.vue";
import { can, session } from "../api/session";
import { request, errorMessage } from "../api/request";
interface Field {
  key: string;
  label: string;
  placeholder: string;
  secret: boolean;
  required: boolean;
}
interface BackendType {
  value: string;
  label: string;
  description: string;
  fields: Field[];
}
interface Entry {
  id: string;
  name: string;
  type: string;
  enabled: boolean;
  values: Record<string, string>;
  secretsSet: string[];
}
interface Catalog {
  version: number;
  enabled: boolean;
  entryId: string | null;
  items: Entry[];
  types: BackendType[];
}
const catalog = ref<Catalog>({
  version: 0,
  enabled: false,
  entryId: null,
  items: [],
  types: [],
});
const loading = ref(true),
  busy = ref(false),
  loaded = ref(false),
  error = ref("");
const editable = computed(
  () => !!session.user?.admin && can("system:config:update"),
);
const keyword = ref("");
const filtered = computed(() =>
  catalog.value.items.filter((e) =>
    (e.name + e.values.endpoint + typeName(e.type))
      .toLowerCase()
      .includes(keyword.value.toLowerCase()),
  ),
);
const active = computed(() =>
  catalog.value.items.find((e) => e.id === catalog.value.entryId),
);
const available = computed(() => catalog.value.items.filter((e) => e.enabled));
const base = "/system/config/storage/catalog";
function typeName(code: string) {
  return catalog.value.types.find((t) => t.value === code)?.label || code;
}
async function load() {
  loading.value = true;
  error.value = "";
  try {
    catalog.value = await request<Catalog>({ url: base });
    loaded.value = true;
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    loading.value = false;
  }
}
async function select(id: string | null, enabled = catalog.value.enabled) {
  if (busy.value || !editable.value || !loaded.value) return;
  busy.value = true;
  error.value = "";
  const changed = id !== catalog.value.entryId;
  try {
    catalog.value = await request<Catalog>({
      url: base + "/selection",
      method: "PUT",
      data: { version: catalog.value.version, entryId: id, enabled },
    });
    Toast.success(
      enabled
        ? "上传存储已生效"
        : changed
          ? "上传位置已选择，启用文件上传后生效"
          : "文件上传已暂停",
    );
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
const editorOpen = ref(false),
  editorError = ref(""),
  editing = ref<Entry | null>(null);
const form = reactive({
  name: "",
  type: "",
  enabled: true,
  values: {} as Record<string, string>,
});
const chosenType = computed(() =>
  catalog.value.types.find((t) => t.value === form.type),
);
function resetFields() {
  form.values = {};
  for (const f of chosenType.value?.fields || [])
    form.values[f.key] =
      f.key === "region" && form.type !== "aliyun" ? "us-east-1" : "";
}
function openEditor(entry: Entry | null = null) {
  editing.value = entry;
  editorError.value = "";
  form.name = entry?.name || "";
  form.type = entry?.type || catalog.value.types[0]?.value || "";
  form.enabled = entry?.enabled ?? true;
  resetFields();
  if (entry)
    for (const f of chosenType.value?.fields || [])
      form.values[f.key] = f.secret ? "" : entry.values[f.key] || "";
  editorOpen.value = true;
}
function secretKept(field: Field) {
  return (
    !!editing.value &&
    editing.value.type === form.type &&
    editing.value.secretsSet.includes(field.key)
  );
}
function closeEditor() {
  if (busy.value) return;
  editorOpen.value = false;
  form.values = {};
  editing.value = null;
}
async function save() {
  if (busy.value || !editable.value) return;
  busy.value = true;
  editorError.value = "";
  try {
    catalog.value = await request<Catalog>({
      url: base + (editing.value ? "/" + editing.value.id : ""),
      method: editing.value ? "PUT" : "POST",
      data: {
        version: catalog.value.version,
        name: form.name,
        type: form.type,
        enabled: form.enabled,
        values: { ...form.values },
      },
    });
    form.values = {};
    editorOpen.value = false;
    Toast.success("配置已保存，可在当前上传存储中选择使用");
  } catch (e) {
    editorError.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
const removing = ref<Entry | null>(null),
  removeError = ref("");
async function remove() {
  if (!removing.value || busy.value) return;
  busy.value = true;
  removeError.value = "";
  try {
    catalog.value = await request<Catalog>({
      url: base + "/" + removing.value.id,
      method: "DELETE",
      params: { version: catalog.value.version },
    });
    removing.value = null;
    Toast.success("存储配置已移除，历史文件仍保留原连接");
  } catch (e) {
    removeError.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section
    id="panel-storage"
    class="storage-settings"
    role="tabpanel"
    aria-labelledby="tab-storage"
    :aria-busy="loading || busy"
  >
    <div class="storage-heading">
      <div>
        <h3>文件存储</h3>
        <p>
          新增不同服务器的存储配置，图片、视频和附件统一使用选中的上传位置。
        </p>
      </div>
      <Button
        v-if="editable"
        theme="solid"
        type="primary"
        :disabled="busy || !loaded"
        :onClick="() => openEditor()"
        ><AppIcon name="plus" :size="16" />新增存储配置</Button
      >
    </div>
    <p v-if="loading" class="muted" role="status">正在加载存储配置…</p>
    <template v-else-if="loaded">
      <div class="storage-upload-control">
        <div>
          <strong>启用文件上传</strong>
          <p>关闭后停止接收新上传，已有文件仍可管理。</p>
        </div>
        <button
          type="button"
          class="native-switch"
          role="switch"
          aria-label="启用文件上传"
          :aria-checked="catalog.enabled"
          :disabled="busy || !editable"
          @click="select(catalog.entryId, !catalog.enabled)"
        >
          <span />
        </button>
      </div>
      <div class="storage-current">
        <label for="storage-active-entry">当前上传存储</label>
        <select
          id="storage-active-entry"
          :value="catalog.entryId || ''"
          :disabled="busy || !editable"
          @change="select(($event.target as HTMLSelectElement).value || null)"
        >
          <option value="" disabled>请新增并选择存储配置</option>
          <option v-if="active && !active.enabled" :value="active.id" disabled>
            {{ active.name }}（已停用）
          </option>
          <option v-for="entry in available" :key="entry.id" :value="entry.id">
            {{ entry.name }} · {{ typeName(entry.type) }}
          </option>
        </select>
        <small
          >选项来自下方已启用的配置，可添加多个同类型服务器。选择后立即生效。</small
        >
      </div>
      <div class="storage-list-toolbar">
        <h4>
          存储配置 <span>{{ catalog.items.length }}</span>
        </h4>
        <div>
          <input
            v-model="keyword"
            aria-label="搜索存储配置"
            placeholder="搜索名称或服务器地址"
          /><Button :disabled="busy" :onClick="load" aria-label="刷新存储配置"
            ><AppIcon name="refresh" :size="17"
          /></Button>
        </div>
      </div>
      <div class="storage-table-wrap">
        <table class="storage-table">
          <thead>
            <tr>
              <th>配置名称</th>
              <th>存储类型</th>
              <th>地址 / 服务器目录</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="entry in filtered" :key="entry.id">
              <td>
                <strong>{{ entry.name }}</strong
                ><span
                  v-if="catalog.entryId === entry.id"
                  class="current-badge"
                  >{{ catalog.enabled ? "当前上传" : "已选择" }}</span
                >
              </td>
              <td>{{ typeName(entry.type) }}</td>
              <td class="storage-address">
                <span :title="entry.values.endpoint">{{
                  entry.values.endpoint
                }}</span
                ><small v-if="entry.type !== 'local' && entry.type !== 'webdav'"
                  >Bucket：{{ entry.values.bucket }}</small
                >
              </td>
              <td>
                <span :class="['storage-status', { on: entry.enabled }]">{{
                  entry.enabled ? "已启用" : "已停用"
                }}</span>
              </td>
              <td class="storage-actions">
                <button
                  :disabled="busy || !editable"
                  @click="openEditor(entry)"
                >
                  编辑</button
                ><button
                  :disabled="
                    busy ||
                    !editable ||
                    !entry.enabled ||
                    entry.id === catalog.entryId
                  "
                  @click="select(entry.id)"
                >
                  设为上传</button
                ><button
                  class="danger"
                  :disabled="busy || !editable"
                  @click="
                    removing = entry;
                    removeError = '';
                  "
                >
                  移除
                </button>
              </td>
            </tr>
            <tr v-if="!filtered.length">
              <td colspan="5" class="storage-empty">
                {{
                  catalog.items.length
                    ? "没有匹配的配置"
                    : "暂无存储配置，点击“新增存储配置”连接你的服务器"
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="storage-notice">
        <AppIcon name="help" :size="18" />
        <p>
          支持 OSS、MinIO、通用 S3 兼容服务、WebDAV
          文件服务器和服务器本地目录（含已挂载
          NAS）。其他服务器需提供对应协议，普通网页地址不能直接作为上传接口。更改地址或移除配置不会迁移、删除已有文件，历史文件仍使用原连接；密钥加密保存且不回显。
        </p>
      </div>
    </template>
    <p v-if="error" class="storage-error" role="alert">
      {{ error }} <button :disabled="busy" @click="load">重新加载</button>
    </p>
    <Modal
      :visible="editorOpen"
      :title="editing ? '编辑存储配置' : '新增存储配置'"
      :footer="() => ''"
      :width="680"
      :maskClosable="!busy"
      :closable="!busy"
      :onCancel="closeEditor"
    >
      <form class="storage-editor" @submit.prevent="save">
        <div class="storage-field">
          <label for="storage-entry-name">配置名称</label
          ><input
            id="storage-entry-name"
            v-model="form.name"
            required
            maxlength="60"
            :disabled="busy"
            placeholder="例如：公司文件服务器 / 备用存储"
          />
        </div>
        <div class="storage-field">
          <label for="storage-entry-type">存储协议</label
          ><select
            id="storage-entry-type"
            v-model="form.type"
            :disabled="busy"
            @change="resetFields"
          >
            <option
              v-for="type in catalog.types"
              :key="type.value"
              :value="type.value"
            >
              {{ type.label }}
            </option></select
          ><small>{{ chosenType?.description }}</small>
        </div>
        <div
          v-for="field in chosenType?.fields || []"
          :key="form.type + field.key"
          class="storage-field"
        >
          <label :for="'storage-value-' + field.key">{{ field.label }}</label>
          <input
            :id="'storage-value-' + field.key"
            v-model="form.values[field.key]"
            :type="field.secret ? 'password' : 'text'"
            :required="field.required && !secretKept(field)"
            :maxlength="
              field.key === 'endpoint' ? 255 : field.secret ? 512 : 64
            "
            :disabled="busy"
            :placeholder="
              secretKept(field) ? '已设置，留空保留原值' : field.placeholder
            "
            autocomplete="off"
            :spellcheck="false"
          />
          <small v-if="secretKept(field)"
            >已加密保存，只有填写新值时才会替换。</small
          >
        </div>
        <div class="storage-upload-control compact">
          <div>
            <strong>启用此配置</strong>
            <p>停用当前上传配置时会同时暂停文件上传。</p>
          </div>
          <button
            class="native-switch"
            type="button"
            role="switch"
            aria-label="启用此配置"
            :aria-checked="form.enabled"
            :disabled="busy"
            @click="form.enabled = !form.enabled"
          >
            <span />
          </button>
        </div>
        <p v-if="editorError" class="storage-error" role="alert">
          {{ editorError }}
        </p>
        <div class="storage-editor-footer">
          <Button :disabled="busy" :onClick="closeEditor">取消</Button
          ><Button
            htmlType="submit"
            theme="solid"
            type="primary"
            :loading="busy"
            :disabled="!editable"
            >保存配置</Button
          >
        </div>
      </form>
    </Modal>
    <Modal
      :visible="!!removing"
      title="移除存储配置"
      :footer="() => ''"
      :maskClosable="!busy"
      :closable="!busy"
      :onCancel="
        () => {
          if (!busy) removing = null;
        }
      "
    >
      <p>确认移除「{{ removing?.name }}」吗？</p>
      <p class="muted">
        仅移除可选配置入口，不删除服务器上的文件；历史文件仍保留原连接。正在用于上传的配置需先切换或关闭上传。
      </p>
      <p v-if="removeError" class="storage-error" role="alert">
        {{ removeError }}
      </p>
      <div class="storage-editor-footer">
        <Button :disabled="busy" :onClick="() => (removing = null)">取消</Button
        ><Button theme="solid" type="danger" :loading="busy" :onClick="remove"
          >确认移除</Button
        >
      </div>
    </Modal>
  </section>
</template>
<style scoped>
.storage-settings h3 {
  font-size: 21px;
  color: #193354;
  margin: 0 0 10px;
}
.storage-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
}
.storage-heading p,
.storage-upload-control p {
  font-size: 13px;
  color: #8b9eb7;
  line-height: 1.8;
  margin: 6px 0;
}
.storage-heading button {
  white-space: nowrap;
}
.storage-upload-control {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  border-bottom: 1px solid #eaf0f7;
  padding: 28px 0;
}
.storage-upload-control strong {
  color: #274262;
  font-size: 16px;
}
.native-switch {
  border: 0;
  background: #c8d3e2;
  width: 42px;
  height: 24px;
  border-radius: 20px;
  flex-shrink: 0;
  padding: 3px;
  cursor: pointer;
}
.native-switch span {
  display: block;
  height: 18px;
  width: 18px;
  border-radius: 50%;
  background: #fff;
  transition: transform 0.15s;
}
.native-switch[aria-checked="true"] {
  background: #1677ff;
}
.native-switch[aria-checked="true"] span {
  transform: translateX(18px);
}
button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.storage-current {
  max-width: 570px;
  margin: 26px 0 32px;
}
.storage-current label,
.storage-field label {
  display: block;
  font-size: 13px;
  color: #627b9b;
  margin-bottom: 9px;
}
.storage-current select,
.storage-field input,
.storage-field select,
.storage-list-toolbar input {
  height: 40px;
  box-sizing: border-box;
  width: 100%;
  border: 1px solid #dce6f2;
  border-radius: 6px;
  padding: 0 12px;
  background: #fff;
  color: #314968;
  font: inherit;
  font-size: 14px;
}
.storage-settings input:focus,
.storage-settings select:focus,
.storage-editor input:focus,
.storage-editor select:focus {
  outline: 2px solid #91bfff;
  outline-offset: 1px;
}
small {
  display: block;
  color: #8a9eb8;
  line-height: 1.8;
  font-size: 12px;
  margin-top: 8px;
}
.storage-list-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}
.storage-list-toolbar h4 {
  margin: 0;
  color: #243e60;
  font-size: 16px;
  white-space: nowrap;
}
.storage-list-toolbar h4 span {
  font-size: 12px;
  color: #8b9db5;
  margin-left: 6px;
}
.storage-list-toolbar > div {
  display: flex;
  align-items: center;
  gap: 10px;
}
.storage-list-toolbar input {
  max-width: 260px;
}
.storage-table-wrap {
  overflow-x: auto;
}
.storage-table {
  border-collapse: collapse;
  width: 100%;
  font-size: 13px;
  color: #405b7c;
}
.storage-table th {
  text-align: left;
  background: #f5f8fd;
  font-weight: 500;
  color: #6981a2;
  padding: 15px 14px;
}
.storage-table td {
  padding: 20px 14px;
  border-bottom: 1px solid #edf2f8;
  vertical-align: middle;
}
.storage-table td:first-child {
  min-width: 130px;
}
.storage-table strong {
  font-weight: 500;
  color: #244161;
}
.storage-address span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 330px;
}
.storage-address small {
  margin-top: 4px;
}
.storage-status {
  color: #8d9eb3;
  background: #f0f3f8;
  border-radius: 4px;
  padding: 4px 8px;
  white-space: nowrap;
}
.storage-status.on {
  color: #18a782;
  background: #e7f7f1;
}
.current-badge {
  display: inline-block;
  margin: 6px 0 0 8px;
  padding: 3px 6px;
  background: #eaf3ff;
  color: #1677ff;
  font-size: 11px;
  border-radius: 4px;
  white-space: nowrap;
}
.storage-actions {
  white-space: nowrap;
}
.storage-actions button,
.storage-error button {
  background: none;
  border: 0;
  color: #1677ff;
  font: inherit;
  cursor: pointer;
  padding: 3px 7px;
}
.storage-actions button.danger {
  color: #ef6472;
}
.storage-empty {
  text-align: center;
  color: #94a6bf;
  padding: 36px !important;
}
.storage-notice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  background: #f5f9ff;
  border-radius: 8px;
  padding: 16px;
  margin-top: 24px;
  color: #829ab9;
  font-size: 13px;
}
.storage-notice svg {
  flex-shrink: 0;
  margin-top: 4px;
}
.storage-notice p {
  margin: 0;
  line-height: 1.9;
}
.storage-error {
  font-size: 13px;
  line-height: 1.8;
  color: #d84b59;
  margin-top: 16px;
}
.storage-field {
  margin-bottom: 20px;
}
.storage-editor {
  padding: 6px 0;
}
.storage-editor-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  border-top: 1px solid #edf2f8;
  margin-top: 24px;
  padding-top: 20px;
}
.compact {
  padding: 4px 0 20px;
  border: 0;
}
@media (max-width: 700px) {
  .storage-heading {
    align-items: flex-start;
    flex-direction: column;
  }
  .storage-heading p {
    margin-bottom: 0;
  }
  .storage-list-toolbar {
    flex-wrap: wrap;
  }
  .storage-list-toolbar > div {
    width: 100%;
  }
  .storage-list-toolbar input {
    max-width: none;
  }
  .storage-table {
    min-width: 740px;
  }
  .storage-notice {
    padding: 14px;
  }
  .storage-editor-footer {
    flex-wrap: wrap;
  }
}
</style>
