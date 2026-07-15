import { createRouter, createWebHistory } from "vue-router";
import RepoGuardLayout from "@/layouts/RepoGuardLayout.vue";
import { hasAuthToken } from "@/api/client";
import { routeNames } from "@/router/names";
import { resolveSafePostAuthRedirect } from "@/router/authRedirect";
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

type RouteComponentLoader = () => Promise<unknown>;

const commonRouteNeighbors = new Map<string, RouteComponentLoader>([
  [routeNames.overview, ReviewTasksPage],
  [routeNames.tasks, OverviewPage],
  [routeNames.taskDetail, ReviewTasksPage]
]);

const managementRouteNeighbors = new Map<string, RouteComponentLoader>([
  [routeNames.rules, IntegrationsPage],
  [routeNames.integrations, MessageQueueHealthPage],
  [routeNames.messageQueue, NotificationOpsPage],
  [routeNames.notificationOps, UserManagementPage],
  [routeNames.users, SystemSettingsPage],
  [routeNames.settings, RuleConfigPage]
]);

let routeComponentPrefetchTimer: ReturnType<typeof setTimeout> | undefined;
const prefetchedRoutes = new Set<string>();

const scheduleRouteComponentPrefetch = (routeName: string, managementAllowed: boolean) => {
  const loadComponent = commonRouteNeighbors.get(routeName)
    ?? (managementAllowed ? managementRouteNeighbors.get(routeName) : undefined);
  if (!loadComponent || prefetchedRoutes.has(routeName) || routeComponentPrefetchTimer || !prefetchAllowed()) {
    return;
  }
  routeComponentPrefetchTimer = setTimeout(() => {
    routeComponentPrefetchTimer = undefined;
    if (!prefetchAllowed()) {
      return;
    }
    const run = () => {
      prefetchedRoutes.add(routeName);
      void loadComponent().catch(() => prefetchedRoutes.delete(routeName));
    };
    if ("requestIdleCallback" in window) {
      window.requestIdleCallback(run, { timeout: 1200 });
    } else {
      run();
    }
  }, 1200);
};

const prefetchAllowed = () => {
  if (document.visibilityState === "hidden") {
    return false;
  }
  const connection = (navigator as Navigator & {
    connection?: { saveData?: boolean; effectiveType?: string };
  }).connection;
  return !connection?.saveData && !["slow-2g", "2g", "3g"].includes(connection?.effectiveType ?? "");
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
    return resolveSafePostAuthRedirect(to.query.redirect);
  }
});

router.afterEach((to) => {
  if (to.meta.requiresAuth && hasAuthToken()) {
    scheduleRouteComponentPrefetch(String(to.name ?? ""), Boolean(to.meta.requiresManage && canManage.value));
  }
});
