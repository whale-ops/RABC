/** 业务管理 API：产品目录、订单快照与状态操作；金额以十进制字符串传输。 */
import { request } from "./request";
export interface Product {
  id: string;
  sku: string;
  name: string;
  category: string;
  price: string;
  stock: number;
  status: "上架" | "下架";
  description: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}
export interface ProductInput {
  sku: string;
  name: string;
  category: string;
  price: string;
  stock: number;
  description: string;
  version?: number;
}
export interface OrderItem {
  id: string;
  productId: string;
  sku: string;
  name: string;
  price: string;
  quantity: number;
  subtotal: string;
}
export interface Order {
  id: string;
  orderNo: string;
  customer: string;
  phone: string;
  address: string;
  remark: string;
  total: string;
  status: "待付款" | "待发货" | "已发货" | "已完成" | "已取消";
  trackingNo: string;
  version: number;
  quantity: number;
  createdAt: string;
  updatedAt: string;
  paidAt: string | null;
  shippedAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  items: OrderItem[];
}
export interface OrderInput {
  requestId: string;
  customer: string;
  phone: string;
  address: string;
  remark: string;
  items: { productId: string; quantity: number }[];
}
export type OrderAction = "cancel" | "pay" | "ship" | "complete";
export const businessApi = {
  products: (params: {
    page: number;
    size: number;
    keyword: string;
    status: string;
    category: string;
  }) =>
    request<{
      records: Product[];
      total: number;
      categories: string[];
      summary: {
        total: number;
        onSale: number;
        stock: string;
        inventoryValue: string;
      };
    }>({ url: "/business/products", params }),
  product: (id: string) =>
    request<Product>({ url: `/business/products/${id}` }),
  saveProduct: (id: string | undefined, data: ProductInput) =>
    request<Product>({
      url: `/business/products${id ? "/" + id : ""}`,
      method: id ? "PATCH" : "POST",
      data,
    }),
  productStatus: (row: Product) =>
    request<Product>({
      url: `/business/products/${row.id}/status`,
      method: "PATCH",
      data: {
        version: row.version,
        status: row.status === "上架" ? "下架" : "上架",
      },
    }),
  deleteProduct: (row: Product) =>
    request<void>({
      url: `/business/products/${row.id}`,
      method: "DELETE",
      data: { version: row.version },
    }),
  choices: (keyword: string) =>
    request<Pick<Product, "id" | "sku" | "name" | "price" | "stock">[]>({
      url: "/business/products/choices",
      params: { keyword },
    }),
  orders: (params: {
    page: number;
    size: number;
    keyword: string;
    status: string;
  }) =>
    request<{
      records: Order[];
      total: number;
      summary: {
        total: number;
        unpaid: number;
        toShip: number;
        amount: string;
      };
    }>({ url: "/business/orders", params }),
  order: (id: string) => request<Order>({ url: `/business/orders/${id}` }),
  createOrder: (data: OrderInput) =>
    request<{ id: string; orderNo?: string }>({
      url: "/business/orders",
      method: "POST",
      data,
    }),
  editOrder: (
    row: Pick<
      Order,
      "id" | "version" | "customer" | "phone" | "address" | "remark"
    >,
  ) =>
    request<void>({
      url: `/business/orders/${row.id}`,
      method: "PATCH",
      data: {
        version: row.version,
        customer: row.customer,
        phone: row.phone,
        address: row.address,
        remark: row.remark,
      },
    }),
  orderAction: (row: Order, action: OrderAction, trackingNo = "") =>
    request<void>({
      url: `/business/orders/${row.id}/${action}`,
      method: "POST",
      data:
        action === "ship"
          ? { version: row.version, trackingNo }
          : { version: row.version },
    }),
};
export function money(value: string | number | undefined) {
  if (value == null) return "—";
  const [integer, fraction = ""] = String(value).split(".");
  return (
    BigInt(integer).toLocaleString("zh-CN") +
    "." +
    fraction.padEnd(2, "0").slice(0, 2)
  );
}
export const businessDate = (value: string | null | undefined) =>
  value
    ? new Date(value).toLocaleString("zh-CN", {
        hour12: false,
        timeZone: "Asia/Shanghai",
      })
    : "—";
