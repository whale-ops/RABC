<script setup lang="ts">
// 包职责：展示产品目录、筛选统计与产品编辑、上下架操作，使用统一管理页样式。
// 模块联系：businessApi 调用产品后台，session 决定按钮权限，订单通过产品库存关联。
import { ref, reactive, computed, onMounted, onBeforeUnmount } from "vue";
import { Button, Modal, Pagination, Toast } from "../ui";
import AppIcon from "../components/AppIcon.vue";
import ListLoadingStatus from "../components/ListLoadingStatus.vue";
import {
  businessApi,
  money,
  businessDate,
  type Product,
  type ProductInput,
} from "../api/business";
import { can } from "../api/session";
import { errorMessage } from "../api/request";
import "../styles/business.css";

const rows = ref<Product[]>([]),
  total = ref(0),
  page = ref(1),
  size = ref(10),
  categories = ref<string[]>([]);
const filters = reactive({ keyword: "", status: "", category: "" });
let applied = { ...filters },
  sequence = 0,
  active = true;
const loading = ref(false),
  loaded = ref(false),
  error = ref(""),
  summary = ref<{
    total: number;
    onSale: number;
    stock: string;
    inventoryValue: string;
  }>();
const editor = ref(false),
  editingId = ref<string>(),
  saving = ref(false),
  formError = ref("");
const formElement = ref<HTMLFormElement>();
const createStatus = ref<"上架" | "下架">("上架");
const saveLabel = computed(() =>
  !editingId.value &&
  can("business:product:status") &&
  createStatus.value === "上架"
    ? "保存并上架"
    : "保存产品",
);
const form = reactive<ProductInput>({
  sku: "",
  name: "",
  category: "",
  price: "0.00",
  stock: 0,
  description: "",
});
const details = ref<Product>();
const pending = ref<{ kind: "status" | "delete"; row: Product }>(),
  working = ref(false);
async function load() {
  const current = ++sequence;
  loading.value = true;
  error.value = "";
  try {
    const data = await businessApi.products({
      page: page.value,
      size: size.value,
      ...applied,
    });
    if (!active || current !== sequence) return;
    rows.value = data.records;
    total.value = data.total;
    categories.value = data.categories;
    summary.value = data.summary;
    if (page.value > 1 && !data.records.length && data.total) {
      page.value = Math.ceil(data.total / size.value);
      void load();
    }
  } catch (e) {
    if (active && current === sequence) error.value = errorMessage(e);
  } finally {
    if (active && current === sequence) {
      loading.value = false;
      loaded.value = true;
    }
  }
}
function search() {
  if (loading.value) return;
  applied = { ...filters };
  page.value = 1;
  void load();
}
function reset() {
  if (loading.value) return;
  Object.assign(filters, { keyword: "", status: "", category: "" });
  search();
}
function edit(row?: Product) {
  editingId.value = row?.id;
  createStatus.value = can("business:product:status") ? "上架" : "下架";
  formError.value = "";
  Object.assign(form, {
    sku: row?.sku || "",
    name: row?.name || "",
    category: row?.category || "",
    price: row?.price || "0.00",
    stock: row?.stock || 0,
    description: row?.description || "",
    version: row?.version,
  });
  editor.value = true;
}
async function save() {
  if (saving.value || !formElement.value?.reportValidity()) return;
  saving.value = true;
  formError.value = "";
  const publish =
    !editingId.value &&
    can("business:product:status") &&
    createStatus.value === "上架";
  try {
    const product = await businessApi.saveProduct(editingId.value, {
      ...form,
      price: String(form.price),
      stock: Number(form.stock),
    });
    if (publish && product.status === "下架") {
      try {
        await businessApi.productStatus(product);
      } catch (e) {
        // 新增已成功时不重复创建；保留产品供列表中的独立上架操作重试。
        if (active) {
          editor.value = false;
          Toast.warning(
            "产品已保存，但上架未成功：" +
              errorMessage(e) +
              "。请在列表重试上架。",
          );
          await load();
        }
        return;
      }
    }
    if (!active) return;
    editor.value = false;
    Toast.success(
      editingId.value
        ? "产品已更新"
        : publish
          ? "产品已创建并上架"
          : "产品已保存为下架，暂不能下单",
    );
    await load();
  } catch (e) {
    if (active) formError.value = errorMessage(e);
  } finally {
    saving.value = false;
  }
}
async function view(row: Product) {
  try {
    const result = await businessApi.product(row.id);
    if (active) details.value = result;
  } catch (e) {
    Toast.error(errorMessage(e));
  }
}
async function confirm() {
  if (!pending.value || working.value) return;
  working.value = true;
  try {
    const { kind, row } = pending.value;
    if (kind === "delete") await businessApi.deleteProduct(row);
    else await businessApi.productStatus(row);
    if (!active) return;
    Toast.success(
      kind === "delete"
        ? "产品已删除"
        : row.status === "上架"
          ? "产品已下架"
          : "产品已上架",
    );
    pending.value = undefined;
    await load();
  } catch (e) {
    Toast.error(errorMessage(e));
  } finally {
    working.value = false;
  }
}
onMounted(load);
onBeforeUnmount(() => {
  active = false;
  ++sequence;
});
</script>

<template>
  <div class="business-page">
    <header class="page-heading">
      <div>
        <h1>产品管理</h1>
        <p>维护产品目录、销售价格与可售库存，统一管理产品上下架</p>
      </div>
      <Button
        v-if="can('business:product:create')"
        theme="solid"
        :onClick="() => edit()"
        ><AppIcon name="plus" :size="16" />新增产品</Button
      >
    </header>
    <section class="business-stats">
      <article class="business-stat">
        <span class="business-stat-icon"
          ><AppIcon name="package" :size="25"
        /></span>
        <div>
          <small>符合条件的产品</small
          ><strong>{{ summary?.total ?? "—" }}</strong>
        </div>
      </article>
      <article class="business-stat">
        <span class="business-stat-icon"
          ><AppIcon name="check" :size="25"
        /></span>
        <div>
          <small>已上架产品</small><strong>{{ summary?.onSale ?? "—" }}</strong>
        </div>
      </article>
      <article class="business-stat">
        <span class="business-stat-icon"
          ><AppIcon name="archive" :size="25"
        /></span>
        <div>
          <small>可售库存总数</small
          ><strong>{{ summary?.stock ?? "—" }}</strong>
        </div>
      </article>
      <article class="business-stat">
        <span class="business-stat-icon"
          ><AppIcon name="briefcase" :size="25"
        /></span>
        <div>
          <small>库存货值（元）</small
          ><strong>{{ money(summary?.inventoryValue) }}</strong>
        </div>
      </article>
    </section>
    <section class="panel records-panel" :aria-busy="loading">
      <form class="business-filter" @submit.prevent="search">
        <label
          >产品关键字<input
            v-model="filters.keyword"
            placeholder="产品名称 / 产品编码"
            maxlength="120"
        /></label>
        <label
          >上架状态<select v-model="filters.status">
            <option value="">全部状态</option>
            <option>上架</option>
            <option>下架</option>
          </select></label
        >
        <label
          >产品分类<select v-model="filters.category">
            <option value="">全部分类</option>
            <option v-for="item in categories" :key="item">{{ item }}</option>
          </select></label
        >
        <div class="filter-actions">
          <Button theme="solid" htmlType="submit" :aria-disabled="loading"
            ><AppIcon name="search" :size="15" />查询</Button
          ><Button
            theme="outline"
            type="tertiary"
            :onClick="reset"
            :aria-disabled="loading"
            >重置</Button
          >
        </div>
        <ListLoadingStatus :loading="loading" />
        <button
          type="button"
          class="icon-button"
          aria-label="刷新产品"
          title="刷新"
          :aria-disabled="loading"
          @click="!loading && load()"
        >
          <AppIcon name="refresh" />
        </button>
      </form>
      <div class="table-toolbar">
        <span class="business-help"
          >产品目录在租户内共享；上架且有库存的产品可下单，订单自动更新可售库存。</span
        ><span class="business-help">共 {{ total }} 个产品</span>
      </div>
      <div v-if="error" class="storage-warning" role="alert">
        {{ error }}<button class="text-button" @click="load">重试</button>
      </div>
      <div class="table-scroll">
        <table class="data-table business-table" aria-label="产品列表">
          <thead>
            <tr>
              <th>产品名称 / 编码</th>
              <th>分类</th>
              <th>单价（元）</th>
              <th>可售库存</th>
              <th>状态</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td class="business-name" :title="row.name">
                <strong>{{ row.name }}</strong
                ><small class="business-subtext">{{ row.sku }}</small>
              </td>
              <td>{{ row.category || "未分类" }}</td>
              <td>¥ {{ money(row.price) }}</td>
              <td>{{ row.stock }}</td>
              <td>
                <span
                  class="business-status"
                  :class="{ green: row.status === '上架' }"
                  >{{ row.status }}</span
                >
              </td>
              <td>{{ businessDate(row.updatedAt) }}</td>
              <td>
                <div class="business-row-actions">
                  <button
                    v-if="can('business:product:read')"
                    @click="view(row)"
                  >
                    详情</button
                  ><button
                    v-if="can('business:product:update')"
                    @click="edit(row)"
                  >
                    编辑</button
                  ><button
                    v-if="can('business:product:status')"
                    @click="pending = { kind: 'status', row }"
                  >
                    {{ row.status === "上架" ? "下架" : "上架" }}</button
                  ><button
                    v-if="can('business:product:delete')"
                    class="danger"
                    @click="pending = { kind: 'delete', row }"
                  >
                    删除
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="7" class="business-empty">
                {{
                  loading && !loaded
                    ? "正在加载产品…"
                    : error
                      ? "产品加载失败，请重试"
                      : "暂无符合条件的产品"
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="business-pagination">
        <span>共 {{ total }} 个产品</span
        ><Pagination
          :total="total"
          :currentPage="page"
          :pageSize="size"
          :onPageChange="
            (value: number) => {
              page = value;
              load();
            }
          "
        />
      </div>
    </section>
    <Modal
      :visible="editor"
      :title="editingId ? '编辑产品' : '新增产品'"
      :width="640"
      :okText="saveLabel"
      cancelText="取消"
      :okButtonProps="{ 'aria-label': saveLabel }"
      :cancelButtonProps="{ 'aria-label': '取消' }"
      :confirmLoading="saving"
      :onOk="save"
      :onCancel="
        () => {
          if (!saving) editor = false;
        }
      "
    >
      <form ref="formElement" class="business-form" @submit.prevent="save">
        <div v-if="formError" class="business-form-error" role="alert">
          {{ formError }}
        </div>
        <label class="business-field"
          >产品名称<input
            v-model="form.name"
            required
            maxlength="120"
            placeholder="请输入产品名称"
        /></label>
        <label class="business-field"
          >产品编码<input
            v-model="form.sku"
            required
            maxlength="64"
            pattern="[A-Za-z0-9_-]+"
            placeholder="如 PROD-001"
          /><small>字母、数字、短横线或下划线，租户内唯一</small></label
        >
        <label class="business-field"
          >产品分类<input
            v-model="form.category"
            maxlength="60"
            placeholder="如 办公用品"
        /></label>
        <label class="business-field"
          >销售单价（元）<input
            v-model="form.price"
            type="number"
            required
            min="0"
            max="9999999999.99"
            step="0.01"
        /></label>
        <label class="business-field"
          >可售库存<input
            v-model.number="form.stock"
            type="number"
            required
            min="0"
            max="1000000000"
            step="1"
          /><small>填写当前可售数量，已创建订单的占用不包含在内</small></label
        >
        <label
          v-if="!editingId && can('business:product:status')"
          class="business-field"
        >
          保存后状态
          <select v-model="createStatus" :disabled="saving">
            <option value="上架">上架，可在订单中选择</option>
            <option value="下架">下架，暂不销售</option>
          </select>
          <small>产品上架且可售库存大于 0 时，才会出现在创建订单中。</small>
        </label>
        <p
          v-if="!editingId && !can('business:product:status')"
          class="business-help"
        >
          产品将保存为下架，请有上架权限的人员上架后再下单。
        </p>
        <label class="business-field full"
          >产品说明<textarea
            v-model="form.description"
            maxlength="1000"
            placeholder="请输入产品说明（选填）"
          />
        </label>
        <button type="submit" hidden>保存</button>
      </form>
    </Modal>
    <Modal
      :visible="!!pending"
      :title="pending?.kind === 'delete' ? '删除产品' : '产品上下架'"
      okText="确认操作"
      cancelText="取消"
      :okButtonProps="{ 'aria-label': '确认操作' }"
      :cancelButtonProps="{ 'aria-label': '取消' }"
      :confirmLoading="working"
      :onOk="confirm"
      :onCancel="
        () => {
          if (!working) pending = undefined;
        }
      "
    >
      <p class="business-dialog-note">
        {{
          pending?.kind === "delete"
            ? `确认删除「${pending.row.name}」吗？已有订单的产品只能下架，不能删除。`
            : `确认${pending?.row.status === "上架" ? "下架" : "上架"}「${pending?.row.name}」吗？下架后不能创建新订单，历史订单不受影响。`
        }}
      </p>
    </Modal>
    <Modal
      :visible="!!details"
      title="产品详情"
      :width="640"
      :footer="() => ''"
      :onCancel="() => (details = undefined)"
    >
      <dl v-if="details" class="business-details">
        <div>
          <dt>产品名称</dt>
          <dd>{{ details.name }}</dd>
        </div>
        <div>
          <dt>产品编码</dt>
          <dd>{{ details.sku }}</dd>
        </div>
        <div>
          <dt>分类</dt>
          <dd>{{ details.category || "未分类" }}</dd>
        </div>
        <div>
          <dt>销售单价</dt>
          <dd>¥ {{ money(details.price) }}</dd>
        </div>
        <div>
          <dt>可售库存</dt>
          <dd>{{ details.stock }}</dd>
        </div>
        <div>
          <dt>上架状态</dt>
          <dd>{{ details.status }}</dd>
        </div>
        <div>
          <dt>创建时间</dt>
          <dd>{{ businessDate(details.createdAt) }}</dd>
        </div>
        <div>
          <dt>更新时间</dt>
          <dd>{{ businessDate(details.updatedAt) }}</dd>
        </div>
        <div class="full">
          <dt>产品说明</dt>
          <dd>{{ details.description || "暂无说明" }}</dd>
        </div>
      </dl>
    </Modal>
  </div>
</template>
