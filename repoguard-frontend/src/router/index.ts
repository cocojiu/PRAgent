import { createRouter, createWebHistory } from "vue-router";
import RepoGuardLayout from "@/layouts/RepoGuardLayout.vue";
import { hasAuthToken } from "@/api/client";
import { routeNames } from "@/router/names";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: routeNames.login,
      component: () => import("@/pages/LoginPage.vue"),
      meta: { title: "登录" }
    },
    {
      path: "/",
      redirect: "/repoguard/overview"
    },
    {
      path: "/repoguard",
      component: RepoGuardLayout,
      redirect: "/repoguard/overview",
      meta: { requiresAuth: true },
      children: [
        {
          path: "overview",
          name: routeNames.overview,
          component: () => import("@/pages/OverviewPage.vue"),
          meta: { title: "总览", requiresAuth: true }
        },
        {
          path: "tasks",
          name: routeNames.tasks,
          component: () => import("@/pages/ReviewTasksPage.vue"),
          meta: { title: "审查任务", requiresAuth: true }
        },
        {
          path: "tasks/:id",
          name: routeNames.taskDetail,
          component: () => import("@/pages/ReviewDetailPage.vue"),
          meta: { title: "任务详情", requiresAuth: true }
        },
        {
          path: "rules",
          name: routeNames.rules,
          component: () => import("@/pages/RuleConfigPage.vue"),
          meta: { title: "规则配置", requiresAuth: true }
        },
        {
          path: "integrations",
          name: routeNames.integrations,
          component: () => import("@/pages/IntegrationsPage.vue"),
          meta: { title: "集成配置", requiresAuth: true }
        },
        {
          path: "message-queue",
          name: routeNames.messageQueue,
          component: () => import("@/pages/MessageQueueHealthPage.vue"),
          meta: { title: "消息队列健康状态", requiresAuth: true }
        },
        {
          path: "users",
          name: routeNames.users,
          component: () => import("@/pages/UserManagementPage.vue"),
          meta: { title: "用户管理", requiresAuth: true }
        },
        {
          path: "settings",
          name: routeNames.settings,
          component: () => import("@/pages/SystemSettingsPage.vue"),
          meta: { title: "系统设置", requiresAuth: true }
        }
      ]
    },
    {
      path: "/:pathMatch(.*)*",
      name: routeNames.notFound,
      redirect: "/repoguard/overview"
    }
  ]
});

router.beforeEach((to) => {
  const title = typeof to.meta.title === "string" ? to.meta.title : "RepoGuard";
  document.title = `${title} - RepoGuard`;

  if (to.meta.requiresAuth && !hasAuthToken()) {
    return {
      name: routeNames.login,
      query: { redirect: to.fullPath }
    };
  }

  if (to.name === routeNames.login && hasAuthToken()) {
    const redirect = typeof to.query.redirect === "string" ? to.query.redirect : "/repoguard/overview";
    return redirect;
  }
});
