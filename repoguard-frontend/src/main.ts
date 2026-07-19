import { createApp } from "vue";
import { createPinia } from "pinia";
import "element-plus/es/components/message/style/css.mjs";
import "element-plus/es/components/message-box/style/css.mjs";
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

app.use(createPinia()).use(router).mount("#app");
