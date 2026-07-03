import { createRouter, createWebHistory } from "vue-router";
import RepoGuardLayout from "@/layouts/RepoGuardLayout.vue";
import { hasAuthToken } from "@/api/client";
import { routeNames } from "@/router/names";
import { canManage, currentUser, loadCurrentUser } from "@/stores/authState";

const LoginPage = () => import("@/pages/LoginPage.vue");
const OverviewPage = () => import("@/pages/OverviewPage.vue");
const ReviewTasksPage = () => import("@/pages/ReviewTasksPage.vue");
const ReviewDetailPage = () => import("@/pages/ReviewDetailPage.vue");
const RuleConfigPage = () => import("@/pages/RuleConfigPage.vue");
const IntegrationsPage = () => import("@/pages/IntegrationsPage.vue");
const MessageQueueHealthPage = () => import("@/pages/MessageQueueHealthPage.vue");
const NotificationOpsPage = () => import("@/pages/NotificationOpsPage.vue");
const UserManagementPage = () => import("@/pages/UserManagementPage.vue");
const SystemSettingsPage = () => import("@/pages/SystemSettingsPage.vue");

const appRouteComponentLoaders = [
  OverviewPage,
  ReviewTasksPage,
  ReviewDetailPage,
  RuleConfigPage,
  IntegrationsPage,
  MessageQueueHealthPage,
  NotificationOpsPage,
  UserManagementPage,
  SystemSettingsPage
];

let routeComponentPrefetchTimer: ReturnType<typeof setTimeout> | undefined;
let hasPrefetchedAppRouteComponents = false;

const scheduleRouteComponentPrefetch = () => {
  if (hasPrefetchedAppRouteComponents || routeComponentPrefetchTimer) {
    return;
  }
  routeComponentPrefetchTimer = setTimeout(() => {
    routeComponentPrefetchTimer = undefined;
    hasPrefetchedAppRouteComponents = true;
    appRouteComponentLoaders.forEach((loadComponent) => {
      void loadComponent().catch(() => {
        hasPrefetchedAppRouteComponents = false;
      });
    });
  }, 1800);
};

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: routeNames.login,
      component: LoginPage,
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
          component: OverviewPage,
          meta: { title: "总览", requiresAuth: true }
        },
        {
          path: "tasks",
          name: routeNames.tasks,
          component: ReviewTasksPage,
          meta: { title: "审查任务", requiresAuth: true }
        },
        {
          path: "tasks/:id",
          name: routeNames.taskDetail,
          component: ReviewDetailPage,
          meta: { title: "任务详情", requiresAuth: true }
        },
        {
          path: "rules",
          name: routeNames.rules,
          component: RuleConfigPage,
          meta: { title: "规则配置", requiresAuth: true, requiresManage: true }
        },
        {
          path: "integrations",
          name: routeNames.integrations,
          component: IntegrationsPage,
          meta: { title: "集成配置", requiresAuth: true, requiresManage: true }
        },
        {
          path: "message-queue",
          name: routeNames.messageQueue,
          component: MessageQueueHealthPage,
          meta: { title: "消息队列健康状态", requiresAuth: true, requiresManage: true }
        },
        {
          path: "notifications",
          name: routeNames.notificationOps,
          component: NotificationOpsPage,
          meta: { title: "通知运维", requiresAuth: true, requiresManage: true }
        },
        {
          path: "users",
          name: routeNames.users,
          component: UserManagementPage,
          meta: { title: "用户管理", requiresAuth: true, requiresManage: true }
        },
        {
          path: "settings",
          name: routeNames.settings,
          component: SystemSettingsPage,
          meta: { title: "系统设置", requiresAuth: true, requiresManage: true }
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

router.beforeEach(async (to) => {
  const title = typeof to.meta.title === "string" ? to.meta.title : "RepoGuard";
  document.title = `${title} - RepoGuard`;

  if (to.meta.requiresAuth && !hasAuthToken()) {
    return {
      name: routeNames.login,
      query: { redirect: to.fullPath }
    };
  }

  if (to.meta.requiresManage) {
    if (!currentUser.value) {
      await loadCurrentUser();
    }
    if (!canManage.value) {
      return { name: routeNames.overview };
    }
  }

  if (to.name === routeNames.login && hasAuthToken()) {
    const redirect = typeof to.query.redirect === "string" ? to.query.redirect : "/repoguard/overview";
    return redirect;
  }
});

router.afterEach((to) => {
  if (to.meta.requiresAuth && hasAuthToken()) {
    scheduleRouteComponentPrefetch();
  }
});
