import { createRouter, createWebHistory } from "vue-router";
import RepoGuardLayout from "@/layouts/RepoGuardLayout.vue";
import OverviewPage from "@/pages/OverviewPage.vue";
import ReviewTasksPage from "@/pages/ReviewTasksPage.vue";
import ReviewDetailPage from "@/pages/ReviewDetailPage.vue";
import IntegrationsPage from "@/pages/IntegrationsPage.vue";
import RuleConfigPage from "@/pages/RuleConfigPage.vue";
import SystemSettingsPage from "@/pages/SystemSettingsPage.vue";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      redirect: "/repoguard/overview"
    },
    {
      path: "/repoguard",
      component: RepoGuardLayout,
      redirect: "/repoguard/overview",
      children: [
        { path: "overview", name: "overview", component: OverviewPage, meta: { title: "总览" } },
        { path: "tasks", name: "tasks", component: ReviewTasksPage, meta: { title: "审查任务" } },
        { path: "tasks/:id", name: "task-detail", component: ReviewDetailPage, meta: { title: "任务详情" } },
        { path: "rules", name: "rules", component: RuleConfigPage, meta: { title: "规则配置" } },
        { path: "integrations", name: "integrations", component: IntegrationsPage, meta: { title: "集成配置" } },
        { path: "settings", name: "settings", component: SystemSettingsPage, meta: { title: "系统设置" } }
      ]
    }
  ]
});

