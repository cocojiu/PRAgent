import { createApp } from "vue";
import { createPinia } from "pinia";
import {
  ElAlert,
  ElButton,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElPopover,
  ElRadio,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTooltip
} from "element-plus";
import "element-plus/dist/index.css";
import "./styles/main.css";
import App from "./App.vue";
import { router } from "./router";

const app = createApp(App);

[
  ElAlert,
  ElButton,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElPopover,
  ElRadio,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTooltip
].forEach((component) => {
  app.component(component.name!, component);
});

app.use(createPinia()).use(router).mount("#app");
