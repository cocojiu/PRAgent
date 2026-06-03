import { createApp } from "vue";
import { createPinia } from "pinia";
import {
  ElAlert,
  ElButton,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElPopover,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn
} from "element-plus";
import "element-plus/dist/index.css";
import "./styles/main.css";
import App from "./App.vue";
import { router } from "./router";

const app = createApp(App);

[
  ElAlert,
  ElButton,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElPagination,
  ElPopover,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn
].forEach((component) => {
  app.component(component.name!, component);
});

app.use(createPinia()).use(router).mount("#app");
