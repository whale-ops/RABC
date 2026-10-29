<script setup lang="ts">
// 包职责：提供订单筛选、产品选购、客户信息编辑、详情与受控业务操作。
// 模块联系：businessApi 对接订单与可售产品，服务端计算金额及库存；session 控制各操作权限。
import { ref, reactive, computed, onMounted, onBeforeUnmount } from "vue";
import { Button, Modal, Pagination, Toast } from "../ui";
import AppIcon from "../components/AppIcon.vue";
import ListLoadingStatus from "../components/ListLoadingStatus.vue";
import {
  businessApi,
  money,
  businessDate,
  type Order,
  type OrderAction,
  type Product,
} from "../api/business";
import { can } from "../api/session";
import { errorMessage } from "../api/request";
import "../styles/business.css";

const states = ["待付款", "待发货", "已发货", "已完成", "已取消"];
const rows = ref<Order[]>([]),
  total = ref(0),
  page = ref(1),
  size = ref(10);
const filters = reactive({ keyword: "", status: "" });
let applied = { ...filters };
const loading = ref(false),
  loaded = ref(false),
  error = ref(""),
  summary = ref<{
    total: number;
    unpaid: number;
    toShip: number;
    amount: string;
  }>();
let sequence = 0,
  active = true,
  choiceSequence = 0;
const editor = ref(false),
  editing = ref<Order>(),
  saving = ref(false),
  formError = ref("");
const formElement = ref<HTMLFormElement>(),
  details = ref<Order>();
const form = reactive({ customer: "", phone: "", address: "", remark: "" });
type Choice = Pick<Product, "id" | "sku" | "name" | "price" | "stock">;
const choices = ref<Choice[]>([]),
  choicesLoading = ref(false),
  choicesError = ref(""),
  productKeyword = ref(""),
  choice = ref("");
const cart = ref<(Choice & { quantity: number })[]>([]);
let requestId = "",
  lastSubmission = "";
const pending = ref<{ row: Order; action: OrderAction }>(),
  tracking = ref(""),
  working = ref(false),
  actionError = ref("");
const labels: Record<OrderAction, string> = {
  cancel: "取消订单",
  pay: "确认收款",
  ship: "订单发货",
  complete: "完成订单",
};
const actionTitle = computed(() =>
  pending.value ? labels[pending.value.action] : "订单操作",
);
const statusClass = (status: string) =>
  ({ 待付款: "orange", 待发货: "blue", 已发货: "purple", 已完成: "green" })[
    status
  ] || "";
const cents = (price: string) => {
  const [integer, fraction = ""] = price.split(".");
  return BigInt(integer) * 100n + BigInt(fraction.padEnd(2, "0").slice(0, 2));
};
const decimal = (amount: bigint) =>
  `${amount / 100n}.${String(amount % 100n).padStart(2, "0")}`;
const lineAmount = (item: Choice & { quantity: number }) =>
  decimal(
    cents(item.price) *
      BigInt(
        Number.isInteger(item.quantity) && item.quantity > 0
          ? item.quantity
          : 0,
      ),
  );
const estimate = computed(() =>
  decimal(
    cart.value.reduce((amount, item) => amount + cents(lineAmount(item)), 0n),
  ),
);

async function load() {
  const current = ++sequence;
  loading.value = true;
  error.value = "";
  try {
    const data = await businessApi.orders({
      page: page.value,
      size: size.value,
      ...applied,
    });
    if (!active || current !== sequence) return;
    rows.value = data.records;
    total.value = data.total;
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
  Object.assign(filters, { keyword: "", status: "" });
  search();
}
async function searchProducts() {
  const current = ++choiceSequence;
  choicesLoading.value = true;
  choicesError.value = "";
  try {
    const data = await businessApi.choices(productKeyword.value);
    if (active && current === choiceSequence) {
      choices.value = data;
      if (!data.some((item) => item.id === choice.value)) choice.value = "";
    }
  } catch (e) {
    if (active && current === choiceSequence) {
      choicesError.value = errorMessage(e);
      choices.value = [];
      choice.value = "";
    }
  } finally {
    if (active && current === choiceSequence) choicesLoading.value = false;
  }
}
function edit(row?: Order) {
  editing.value = row;
  formError.value = "";
  cart.value = [];
  choices.value = [];
  choice.value = "";
  productKeyword.value = "";
  choicesError.value = "";
  Object.assign(form, {
    customer: row?.customer || "",
    phone: row?.phone || "",
    address: row?.address || "",
    remark: row?.remark || "",
  });
  requestId = crypto.randomUUID();
  lastSubmission = "";
  editor.value = true;
  if (!row) void searchProducts();
}
function addProduct() {
  const product = choices.value.find((item) => item.id === choice.value);
  if (!product) return;
  if (cart.value.some((item) => item.id === product.id)) {
    formError.value = "该产品已添加，请直接修改数量";
    return;
  }
  if (cart.value.length >= 50) {
    formError.value = "每份订单最多添加 50 种产品";
    return;
  }
  cart.value.push({ ...product, quantity: 1 });
  choice.value = "";
  formError.value = "";
}
async function save() {
  if (saving.value || !formElement.value?.reportValidity()) return;
  if (!editing.value && !cart.value.length) {
    formError.value = "请至少添加一个产品";
    return;
  }
  if (
    !editing.value &&
    cart.value.some(
      (item) =>
        !Number.isInteger(item.quantity) ||
        item.quantity < 1 ||
        item.quantity > Math.min(item.stock, 10000),
    )
  ) {
    formError.value = "产品数量必须为正整数且不能超过可售库存或单项 10000 件";
    return;
  }
  saving.value = true;
  formError.value = "";
  try {
    if (editing.value)
      await businessApi.editOrder({ ...editing.value, ...form });
    else {
      const data = {
        ...form,
        items: cart.value.map((item) => ({
          productId: item.id,
          quantity: item.quantity,
        })),
      };
      const submission = JSON.stringify(data);
      if (lastSubmission && lastSubmission !== submission)
        requestId = crypto.randomUUID();
      lastSubmission = submission;
      await businessApi.createOrder({ ...data, requestId });
    }
    if (!active) return;
    editor.value = false;
    Toast.success(
      editing.value ? "订单信息已更新" : "订单创建成功，库存已扣减",
    );
    await load();
  } catch (e) {
    if (active) formError.value = errorMessage(e);
  } finally {
    saving.value = false;
  }
}
async function view(row: Order) {
  try {
    const data = await businessApi.order(row.id);
    if (active) details.value = data;
  } catch (e) {
    Toast.error(errorMessage(e));
  }
}
function ask(row: Order, action: OrderAction) {
  pending.value = { row, action };
  tracking.value = "";
  actionError.value = "";
}
async function confirm() {
  if (!pending.value || working.value) return;
  if (pending.value.action === "ship" && !tracking.value.trim()) {
    actionError.value = "请输入物流单号";
    return;
  }
  working.value = true;
  actionError.value = "";
  try {
    const { row, action } = pending.value;
    await businessApi.orderAction(row, action, tracking.value);
    if (!active) return;
    Toast.success(labels[action] + "成功");
    pending.value = undefined;
    await load();
  } catch (e) {
    if (active) actionError.value = errorMessage(e);
  } finally {
    working.value = false;
  }
}
// 从产品管理或其他标签页返回时刷新可售选项，保留已经填写的客户与订单明细。
function refreshAvailable() {
  if (
    document.visibilityState === "visible" &&
    editor.value &&
    !editing.value &&
    !saving.value &&
    !choicesLoading.value
  )
    void searchProducts();
}
onMounted(() => {
  void load();
  window.addEventListener("focus", refreshAvailable);
  document.addEventListener("visibilitychange", refreshAvailable);
});
onBeforeUnmount(() => {
  active = false;
  ++sequence;
  ++choiceSequence;
  window.removeEventListener("focus", refreshAvailable);
  document.removeEventListener("visibilitychange", refreshAvailable);
});
</script>

<template>
  <div class="business-page">
    <header class="page-heading">
      <div>
        <h1>订单管理</h1>
        <p>追踪订单从创建、收款到发货与完成，库存与产品目录实时关联</p>
      </div>
      <Button
        v-if="can('business:order:create')"
        theme="solid"
        :onClick="() => edit()"
        ><AppIcon name="plus" :size="16" />创建订单</Button
      >
    </header>
    <section class="business-stats">
      <article class="business-stat">
        <span class="business-stat-icon"
          ><AppIcon name="list" :size="25"
        /></span>
        <div>
          <small>符合条件的订单</small
          ><strong>{{ summary?.total ?? "—" }}</strong>
        </div>
      </article>
      <article class="business-stat">
        <span class="business-stat-icon"
          ><AppIcon name="clock" :size="25"
        /></span>
        <div>
          <small>待付款订单</small><strong>{{ summary?.unpaid ?? "—" }}</strong>
        </div>
      </article>
      <article class="business-stat">
        <span class="business-stat-icon"
          ><AppIcon name="package" :size="25"
        /></span>
        <div>
          <small>待发货订单</small><strong>{{ summary?.toShip ?? "—" }}</strong>
        </div>
      </article>
      <article class="business-stat">
        <span class="business-stat-icon"
          ><AppIcon name="briefcase" :size="25"
        /></span>
        <div>
          <small>有效订单金额（元）</small
          ><strong>{{ money(summary?.amount) }}</strong>
        </div>
      </article>
    </section>
    <section class="panel records-panel" :aria-busy="loading">
      <form class="business-filter" @submit.prevent="search">
        <label
          >订单关键字<input
            v-model="filters.keyword"
            maxlength="120"
            placeholder="订单号 / 客户 / 联系电话"
        /></label>
        <label
          >订单状态<select v-model="filters.status">
            <option value="">全部状态</option>
            <option v-for="state in states" :key="state">{{ state }}</option>
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
          aria-label="刷新订单"
          title="刷新"
          :aria-disabled="loading"
          @click="!loading && load()"
        >
          <AppIcon name="refresh" />
        </button>
      </form>
      <div class="table-toolbar">
        <span class="business-help"
          >仅待付款订单可以编辑或取消，取消后自动归还库存。统计遵循角色数据范围。</span
        ><span class="business-help">共 {{ total }} 笔订单</span>
      </div>
      <div v-if="error" class="storage-warning" role="alert">
        {{ error }}<button class="text-button" @click="load">重试</button>
      </div>
      <div class="table-scroll">
        <table class="data-table business-table" aria-label="订单列表">
          <thead>
            <tr>
              <th>订单编号</th>
              <th>客户 / 联系方式</th>
              <th>产品数量</th>
              <th>订单金额（元）</th>
              <th>订单状态</th>
              <th>下单时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.orderNo }}</td>
              <td class="business-name" :title="row.customer">
                <strong>{{ row.customer }}</strong
                ><small class="business-subtext">{{
                  row.phone || "未填写电话"
                }}</small>
              </td>
              <td>{{ row.quantity }} 件</td>
              <td>¥ {{ money(row.total) }}</td>
              <td>
                <span
                  class="business-status"
                  :class="statusClass(row.status)"
                  >{{ row.status }}</span
                >
              </td>
              <td>{{ businessDate(row.createdAt) }}</td>
              <td>
                <div class="business-row-actions">
                  <button v-if="can('business:order:read')" @click="view(row)">
                    详情</button
                  ><button
                    v-if="
                      row.status === '待付款' && can('business:order:update')
                    "
                    @click="edit(row)"
                  >
                    编辑</button
                  ><button
                    v-if="row.status === '待付款' && can('business:order:pay')"
                    @click="ask(row, 'pay')"
                  >
                    确认收款</button
                  ><button
                    v-if="row.status === '待发货' && can('business:order:ship')"
                    @click="ask(row, 'ship')"
                  >
                    发货</button
                  ><button
                    v-if="
                      row.status === '已发货' && can('business:order:complete')
                    "
                    @click="ask(row, 'complete')"
                  >
                    完成</button
                  ><button
                    v-if="
                      row.status === '待付款' && can('business:order:cancel')
                    "
                    class="danger"
                    @click="ask(row, 'cancel')"
                  >
                    取消
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="7" class="business-empty">
                {{
                  loading && !loaded
                    ? "正在加载订单…"
                    : error
                      ? "订单加载失败，请重试"
                      : "暂无符合条件的订单"
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="business-pagination">
        <span>共 {{ total }} 笔订单</span
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
      :title="editing ? '编辑订单' : '创建订单'"
      :width="780"
      :okText="editing ? '保存订单' : '提交订单'"
      cancelText="取消"
      :okButtonProps="{ 'aria-label': editing ? '保存订单' : '提交订单' }"
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
          >客户 / 收货人<input
            v-model="form.customer"
            required
            maxlength="100"
            placeholder="请输入客户或收货人"
        /></label>
        <label class="business-field"
          >联系电话<input
            v-model="form.phone"
            maxlength="32"
            placeholder="请输入联系电话（选填）"
        /></label>
        <label class="business-field full"
          >收货地址<input
            v-model="form.address"
            maxlength="300"
            placeholder="请输入收货地址（选填）"
        /></label>
        <div v-if="!editing" class="business-order-items">
          <label class="business-field"
            >查找产品
            <div class="business-item-picker">
              <input
                v-model="productKeyword"
                maxlength="120"
                placeholder="产品名称 / 编码"
                @keydown.enter.prevent="searchProducts"
              /><Button
                theme="outline"
                :disabled="choicesLoading"
                :onClick="searchProducts"
                >查找</Button
              >
            </div></label
          >
          <div class="business-item-picker">
            <select v-model="choice" aria-label="选择产品">
              <option value="">
                {{
                  choicesLoading
                    ? "正在加载产品…"
                    : choicesError
                      ? "可售产品加载失败，请刷新重试"
                      : choices.length
                        ? "请选择上架产品（最多返回 100 个匹配项）"
                        : productKeyword.trim()
                          ? "没有匹配的可售产品，请更换关键字"
                          : "暂无上架且有库存的产品"
                }}
              </option>
              <option
                v-for="product in choices"
                :key="product.id"
                :value="product.id"
              >
                {{ product.name }} · {{ product.sku }} · ¥{{
                  money(product.price)
                }}
                · 库存 {{ product.stock }}
              </option></select
            ><Button
              theme="outline"
              :disabled="!choice || choicesLoading"
              :onClick="addProduct"
              >添加产品</Button
            >
            <button
              type="button"
              class="icon-button"
              aria-label="刷新可售产品"
              title="刷新可售产品"
              :disabled="choicesLoading"
              @click="searchProducts"
            >
              <AppIcon name="refresh" :size="18" />
            </button>
          </div>
          <p v-if="choicesError" class="business-form-error" role="alert">
            {{ choicesError }}，请点击刷新重试。
          </p>
          <p
            v-else-if="!choicesLoading && !choices.length"
            class="business-help"
          >
            {{
              productKeyword.trim()
                ? "请更换关键字，或清空关键字后查找。"
                : "仅显示状态为“上架”且库存大于 0 的产品。"
            }}
            <RouterLink
              v-if="can('business:product:list')"
              to="/business/products"
              target="_blank"
              rel="noopener"
              >前往产品管理</RouterLink
            >
          </p>
          <div
            v-for="(item, index) in cart"
            :key="item.id"
            class="business-item-row"
          >
            <div>
              <strong>{{ item.name }}</strong
              ><small class="business-subtext"
                >{{ item.sku }} · ¥{{ money(item.price) }}</small
              >
            </div>
            <input
              v-model.number="item.quantity"
              type="number"
              :aria-label="item.name + '数量'"
              required
              min="1"
              :max="Math.min(item.stock, 10000)"
              step="1"
            /><span>¥{{ money(lineAmount(item)) }}</span
            ><button
              type="button"
              class="icon-button"
              :aria-label="'移除' + item.name"
              @click="cart.splice(index, 1)"
            >
              <AppIcon name="close" :size="16" />
            </button>
          </div>
          <p v-if="!cart.length" class="business-help">尚未添加产品</p>
          <p class="business-total">
            预计金额<strong>¥ {{ money(estimate) }}</strong>
          </p>
          <p class="business-help">
            实际金额以下单时的后台产品价格为准，提交时重新校验库存；默认创建为待付款。
          </p>
        </div>
        <p v-else class="business-help">
          产品明细与单价已锁定，编辑仅修改客户、地址和备注。
        </p>
        <label class="business-field full"
          >订单备注<textarea
            v-model="form.remark"
            maxlength="500"
            placeholder="请输入订单备注（选填）"
          />
        </label>
        <button type="submit" hidden>保存</button>
      </form>
    </Modal>
    <Modal
      :visible="!!pending"
      :title="actionTitle"
      okText="确认操作"
      cancelText="返回"
      :okButtonProps="{ 'aria-label': '确认操作' }"
      :cancelButtonProps="{ 'aria-label': '返回' }"
      :confirmLoading="working"
      :onOk="confirm"
      :onCancel="
        () => {
          if (!working) pending = undefined;
        }
      "
    >
      <p class="business-dialog-note">
        订单：{{ pending?.row.orderNo }}<br />客户：{{
          pending?.row.customer
        }}
        · 金额：¥ {{ money(pending?.row.total) }}
      </p>
      <p class="business-dialog-note" v-if="pending?.action === 'cancel'">
        确认取消这笔待付款订单？取消后会归还本订单占用的产品库存。
      </p>
      <p class="business-dialog-note" v-if="pending?.action === 'pay'">
        请核实实际款项已到账，再确认收款。此操作记录人工收款状态，不会发起线上扣款。
      </p>
      <label v-if="pending?.action === 'ship'" class="business-field"
        >物流单号<input
          v-model="tracking"
          maxlength="100"
          placeholder="请输入物流单号"
      /></label>
      <p class="business-dialog-note" v-if="pending?.action === 'complete'">
        确认订单已交付，标记为已完成？
      </p>
      <p v-if="actionError" class="business-form-error" role="alert">
        {{ actionError }}
      </p>
    </Modal>
    <Modal
      :visible="!!details"
      title="订单详情"
      :width="820"
      :footer="() => ''"
      :onCancel="() => (details = undefined)"
    >
      <template v-if="details"
        ><dl class="business-details">
          <div>
            <dt>订单编号</dt>
            <dd>{{ details.orderNo }}</dd>
          </div>
          <div>
            <dt>订单状态</dt>
            <dd>
              <span
                class="business-status"
                :class="statusClass(details.status)"
                >{{ details.status }}</span
              >
            </dd>
          </div>
          <div>
            <dt>客户 / 收货人</dt>
            <dd>{{ details.customer }}</dd>
          </div>
          <div>
            <dt>联系电话</dt>
            <dd>{{ details.phone || "—" }}</dd>
          </div>
          <div class="full">
            <dt>收货地址</dt>
            <dd>{{ details.address || "—" }}</dd>
          </div>
          <div>
            <dt>下单时间</dt>
            <dd>{{ businessDate(details.createdAt) }}</dd>
          </div>
          <div>
            <dt>确认收款时间</dt>
            <dd>{{ businessDate(details.paidAt) }}</dd>
          </div>
          <div>
            <dt>发货时间</dt>
            <dd>{{ businessDate(details.shippedAt) }}</dd>
          </div>
          <div>
            <dt>物流单号</dt>
            <dd>{{ details.trackingNo || "—" }}</dd>
          </div>
          <div>
            <dt>完成时间</dt>
            <dd>{{ businessDate(details.completedAt) }}</dd>
          </div>
          <div>
            <dt>取消时间</dt>
            <dd>{{ businessDate(details.cancelledAt) }}</dd>
          </div>
          <div class="full">
            <dt>订单备注</dt>
            <dd>{{ details.remark || "—" }}</dd>
          </div>
        </dl>
        <div class="table-scroll">
          <table
            class="data-table"
            style="min-width: 540px"
            aria-label="订单产品明细"
          >
            <thead>
              <tr>
                <th>产品 / 编码</th>
                <th>单价（元）</th>
                <th>数量</th>
                <th>小计（元）</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in details.items" :key="item.id">
                <td>
                  {{ item.name
                  }}<small class="business-subtext">{{ item.sku }}</small>
                </td>
                <td>{{ money(item.price) }}</td>
                <td>{{ item.quantity }}</td>
                <td>{{ money(item.subtotal) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="business-total">
          订单金额<strong>¥ {{ money(details.total) }}</strong>
        </p>
        <p class="business-help">
          明细为下单时的产品与价格快照，后续产品改价不会改变此订单。
        </p></template
      >
    </Modal>
  </div>
</template>
