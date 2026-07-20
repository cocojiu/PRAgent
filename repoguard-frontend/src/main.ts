import { createApp } from "vue";
import { createPinia } from "pinia";
import "element-plus/es/components/message/style/css.mjs";
import "element-plus/es/components/message-box/style/css.mjs";
import "./styles/main.css";
import App from "./App.vue";
import { startFrontendPerformanceObservation } from "./observability/frontendPerformance";
import {
  disableFrontendPerformanceDiagnostics,
  enableFrontendPerformanceDiagnostics,
  isControlledPerformanceProfile
} from "./observability/frontendPerformanceDiagnosticsBridge";
import { router } from "./router";

const resolveCurrentRoute = () => {
  const name = router.currentRoute.value.name;
  return typeof name === "string" ? name : undefined;
};

startFrontendPerformanceObservation(resolveCurrentRoute);

const requestedPerformanceProfile = new URLSearchParams(window.location.search)
  .get("performanceProfile")
  ?.trim()
  .toLowerCase();
if (isControlledPerformanceProfile(requestedPerformanceProfile)) {
  enableFrontendPerformanceDiagnostics();
  void import("./observability/frontendPerformanceDiagnostics")
    .then(({ startFrontendPerformanceDiagnostics }) => {
      startFrontendPerformanceDiagnostics(resolveCurrentRoute);
    })
    .catch(disableFrontendPerformanceDiagnostics);
}

const app = createApp(App);

app.use(createPinia()).use(router).mount("#app");
