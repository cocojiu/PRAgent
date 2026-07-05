import { createApp } from "vue";
import { createPinia } from "pinia";
import { ElAlert } from "element-plus/es/components/alert/index.mjs";
import { ElButton } from "element-plus/es/components/button/index.mjs";
import { ElCheckbox } from "element-plus/es/components/checkbox/index.mjs";
import { ElDialog } from "element-plus/es/components/dialog/index.mjs";
import { ElDropdown, ElDropdownItem, ElDropdownMenu } from "element-plus/es/components/dropdown/index.mjs";
import { ElEmpty } from "element-plus/es/components/empty/index.mjs";
import { ElForm, ElFormItem } from "element-plus/es/components/form/index.mjs";
import { ElInput } from "element-plus/es/components/input/index.mjs";
import { ElInputNumber } from "element-plus/es/components/input-number/index.mjs";
import { ElLoadingDirective } from "element-plus/es/components/loading/index.mjs";
import { ElOption, ElSelect } from "element-plus/es/components/select/index.mjs";
import { ElPagination } from "element-plus/es/components/pagination/index.mjs";
import { ElPopover } from "element-plus/es/components/popover/index.mjs";
import { ElRadio } from "element-plus/es/components/radio/index.mjs";
import { ElSegmented } from "element-plus/es/components/segmented/index.mjs";
import { ElSwitch } from "element-plus/es/components/switch/index.mjs";
import { ElTabPane, ElTabs } from "element-plus/es/components/tabs/index.mjs";
import { ElTag } from "element-plus/es/components/tag/index.mjs";
import { ElTable, ElTableColumn } from "element-plus/es/components/table/index.mjs";
import { ElTooltip } from "element-plus/es/components/tooltip/index.mjs";
import "element-plus/dist/index.css";
import "./styles/main.css";
import "./features/integrations/integrations.css";
import "./features/message-queue/messageQueue.css";
import "./features/notification-ops/notificationOps.css";
import "./features/rule-config/ruleConfig.css";
import "./features/review-detail/reviewDetail.css";
import "./features/review-tasks/reviewTasks.css";
import "./features/system-settings/systemSettings.css";
import "./features/user-management/userManagement.css";
import App from "./App.vue";
import { startFrontendPerformanceObservation } from "./observability/frontendPerformance";
import { router } from "./router";

startFrontendPerformanceObservation(() => {
  const name = router.currentRoute.value.name;
  return typeof name === "string" ? name : undefined;
});

const app = createApp(App);

[
  ElAlert,
  ElButton,
  ElCheckbox,
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
  ElSegmented,
  ElSelect,
  ElSwitch,
  ElTabPane,
  ElTabs,
  ElTag,
  ElTable,
  ElTableColumn,
  ElTooltip
].forEach((component) => {
  app.component(component.name!, component);
});

app.directive("loading", ElLoadingDirective);
app.use(createPinia()).use(router).mount("#app");
