/**
 * 集中按需导出 Semi UI Vue 控件，减少页面重复导入及无关组件依赖。
 * 关联模块：各视图与公共组件通过本文件使用按钮、表单、表格分页、抽屉和提示等控件。
 */
// 按控件分别导入，减少无关组件的类型与代码依赖；界面统一使用 Semi UI Vue。
export { default as Button } from "@kousum/semi-ui-vue/dist/button";
export { default as Input } from "@kousum/semi-ui-vue/dist/input";
export { default as TextArea } from "@kousum/semi-ui-vue/dist/input/textArea";
export { default as Select } from "@kousum/semi-ui-vue/dist/select";
export { default as Switch } from "@kousum/semi-ui-vue/dist/switch";
export { default as Modal } from "@kousum/semi-ui-vue/dist/modal";
export { default as SideSheet } from "@kousum/semi-ui-vue/dist/sideSheet";
export { default as Pagination } from "@kousum/semi-ui-vue/dist/pagination";
export { default as Toast } from "@kousum/semi-ui-vue/dist/toast";
export { default as ConfigProvider } from "@kousum/semi-ui-vue/dist/configProvider";
