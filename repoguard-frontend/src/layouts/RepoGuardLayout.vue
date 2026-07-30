<template>
  <div class="rg-shell">
    <aside class="rg-sidebar" :class="{ collapsed }">
      <div class="brand">
        <div class="brand-icon">◆</div>
        <strong v-if="!collapsed">RepoGuard Agent</strong>
      </div>
      <nav class="nav-list">
        <RouterLink
          v-for="item in visibleNavItems"
          :key="item.path"
          class="nav-item"
          :to="item.path"
          :aria-label="collapsed ? item.label : undefined"
          :title="collapsed ? item.label : undefined"
        >
          <component :is="item.icon" :size="20" />
          <span v-if="!collapsed">{{ item.label }}</span>
        </RouterLink>
      </nav>
      <button
        class="collapse-btn"
        type="button"
        :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'"
        :title="collapsed ? '展开侧边栏' : '收起侧边栏'"
        @click="collapsed = !collapsed"
      >
        <PanelLeftClose :size="18" />
        <span v-if="!collapsed">收起菜单</span>
      </button>
      <div v-if="!collapsed" class="version">v1.0.0</div>
    </aside>
    <main class="rg-main">
      <header class="rg-topbar">
        <button
          class="icon-button"
          type="button"
          :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'"
          :title="collapsed ? '展开侧边栏' : '收起侧边栏'"
          @click="collapsed = !collapsed"
        >
          <Menu :size="22" />
        </button>
        <div class="top-title">{{ currentTitle }}</div>
        <div class="top-actions">
          <div class="top-action-menu-shell" @click.stop>
            <button
              class="top-action-button bell-wrap"
              type="button"
              aria-label="查看通知"
              aria-haspopup="dialog"
              aria-controls="notification-panel"
              :aria-expanded="notificationPanelOpen"
              @click="toggleNotificationPanel"
            >
              <Bell :size="20" />
              <span v-if="unreadCount" class="notification-badge">{{ unreadBadgeText }}</span>
            </button>
            <div
              v-if="notificationPanelOpen"
              id="notification-panel"
              class="top-action-popover notification-popover-panel notification-panel"
              role="dialog"
              aria-label="消息通知"
            >
              <div class="notification-head">
                <div>
                  <strong>消息通知</strong>
                  <small v-if="notificationCenter?.generatedAt">更新于 {{ notificationCenter.generatedAt }}</small>
                </div>
                <button type="button" :disabled="!unreadCount" @click.stop="markAllRead">全部已读</button>
              </div>
              <div v-if="loadingNotifications" class="notification-state">正在加载通知...</div>
              <div v-else-if="notificationError" class="notification-state notification-state--error">
                <span>{{ notificationError }}</span>
                <button type="button" @click="loadNotifications({ force: true })">重试</button>
              </div>
              <div v-else-if="!notifications.length" class="notification-state">暂无待处理通知</div>
              <div v-else class="notification-list">
                <button
                  v-for="item in notifications"
                  :key="item.id"
                  type="button"
                  :class="['notification-item', { read: isNotificationRead(item.id) }]"
                  @click="openNotification(item)"
                >
                  <span :class="`notification-dot ${item.level}`"></span>
                  <span>
                    <b>{{ item.title }}</b>
                    <em>{{ item.description }}</em>
                    <small>{{ item.time }}</small>
                  </span>
                </button>
              </div>
              <RouterLink class="notification-more" to="/repoguard/tasks" @click="notificationPanelOpen = false">
                查看全部消息 →
              </RouterLink>
            </div>
          </div>

          <button class="top-action-button" type="button" aria-label="帮助文档" @click="openHelp">
            <CircleHelp :size="20" />
          </button>

          <div class="top-action-menu-shell" @click.stop>
            <button
              class="user"
              type="button"
              aria-haspopup="menu"
              aria-controls="user-action-menu"
              :aria-expanded="userMenuOpen"
              @click="toggleUserMenu"
            >
              <span class="avatar">{{ currentUserInitial }}</span>
              <span>{{ currentUserName }}</span>
              <ChevronDown :size="16" />
            </button>
            <div
              v-if="userMenuOpen"
              id="user-action-menu"
              class="top-action-popover user-action-menu"
              role="menu"
              aria-label="用户操作"
            >
              <button type="button" role="menuitem" @click="handleUserMenuCommand('profile')">个人资料</button>
              <button type="button" role="menuitem" @click="handleUserMenuCommand('change-password')">修改密码</button>
              <button
                v-if="canOpenPath('/repoguard/settings')"
                type="button"
                role="menuitem"
                @click="handleUserMenuCommand('settings')"
              >
                系统设置
              </button>
              <button
                class="user-action-menu-divider"
                type="button"
                role="menuitem"
                @click="handleUserMenuCommand('logout')"
              >
                退出登录
              </button>
            </div>
          </div>
        </div>
      </header>
      <section class="rg-content">
        <RouterView />
      </section>
    </main>
    <ChangePasswordDialog
      v-if="changePasswordDialogVisible"
      v-model="changePasswordDialogVisible"
      @changed="handlePasswordChanged"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import {
  Bell,
  ChevronDown,
  CircleHelp,
  ClipboardList,
  Cog,
  Home,
  Menu,
  PanelLeftClose,
  Plug,
  RadioTower,
  BellRing,
  ShieldCheck,
  Users
} from "@lucide/vue";
import { logout } from "@/api/auth";
import { hasAuthToken } from "@/api/client";
import { fetchNotifications } from "@/api/notifications";
import { createPageAwarePoller } from "@/composables/pageAwarePoller";
import { pruneReadNotificationIds } from "@/layouts/notificationReadState";
import { canAccessRouteMeta } from "@/router/accessPolicy";
import { canManage, currentUser, loadCurrentUser, resetCurrentUser } from "@/stores/authState";
import type { NotificationCenter, NotificationItem } from "@/types";

const ChangePasswordDialog = defineAsyncComponent(
  () => import("@/features/auth/components/ChangePasswordDialog.vue")
);
const collapsed = ref(false);
const route = useRoute();
const router = useRouter();
const changePasswordDialogVisible = ref(false);
const notificationPanelOpen = ref(false);
const userMenuOpen = ref(false);
const notificationCenter = ref<NotificationCenter>();
const loadingNotifications = ref(false);
const notificationError = ref("");
const readNotificationIds = ref<Set<string>>(new Set());
const NOTIFICATION_READ_KEY = "repoguard-read-notifications";
const NOTIFICATION_POLL_INTERVAL_MS = 90000;
let notificationWarmupTimer: ReturnType<typeof setTimeout> | undefined;

const navItems = [
  { label: "总览", path: "/repoguard/overview", icon: Home },
  { label: "审查任务", path: "/repoguard/tasks", icon: ClipboardList },
  { label: "规则配置", path: "/repoguard/rules", icon: ShieldCheck },
  { label: "集成设置", path: "/repoguard/integrations", icon: Plug },
  { label: "消息队列", path: "/repoguard/message-queue", icon: RadioTower },
  { label: "通知运维", path: "/repoguard/notifications", icon: BellRing },
  { label: "用户管理", path: "/repoguard/users", icon: Users },
  { label: "系统设置", path: "/repoguard/settings", icon: Cog }
];

const canOpenPath = (path: string) => canAccessRouteMeta(router.resolve(path).meta, {
  authenticated: hasAuthToken(),
  managementAllowed: canManage.value
});
const visibleNavItems = computed(() => navItems.filter((item) => canOpenPath(item.path)));
const currentTitle = computed(() => String(route.meta.title || "RepoGuard Agent"));
const notifications = computed(() => notificationCenter.value?.items ?? []);
const unreadCount = computed(() => notifications.value.filter((item) => !isNotificationRead(item.id)).length);
const unreadBadgeText = computed(() => (unreadCount.value > 99 ? "99+" : String(unreadCount.value)));
const currentUserName = computed(() => currentUser.value?.username || "管理员");
const currentUserInitial = computed(() => (currentUserName.value.trim().charAt(0) || "A").toUpperCase());

const loadReadNotificationIds = () => {
  try {
    const storedValue = window.localStorage.getItem(NOTIFICATION_READ_KEY);
    const ids = storedValue ? (JSON.parse(storedValue) as string[]) : [];
    readNotificationIds.value = new Set(ids);
  } catch {
    readNotificationIds.value = new Set();
  }
};

const persistReadNotificationIds = () => {
  window.localStorage.setItem(NOTIFICATION_READ_KEY, JSON.stringify([...readNotificationIds.value]));
};

const markNotificationRead = (id: string) => {
  if (readNotificationIds.value.has(id)) {
    return;
  }
  readNotificationIds.value = new Set([...readNotificationIds.value, id]);
  persistReadNotificationIds();
};

const isNotificationRead = (id: string) => readNotificationIds.value.has(id);

const refreshCurrentUser = async () => {
  if (currentUser.value) {
    return;
  }
  try {
    await loadCurrentUser();
  } catch {
    resetCurrentUser();
  }
};

const loadNotifications = async (options: { force?: boolean } = {}) => {
  if (loadingNotifications.value || (notificationCenter.value && !options.force)) {
    return;
  }
  loadingNotifications.value = true;
  notificationError.value = "";
  try {
    const center = await fetchNotifications();
    notificationCenter.value = center;
    pruneReadNotifications(center.items);
  } catch (error) {
    notificationError.value = error instanceof Error ? error.message : "通知加载失败";
  } finally {
    loadingNotifications.value = false;
  }
};

const pruneReadNotifications = (items: NotificationItem[]) => {
  const pruned = pruneReadNotificationIds(readNotificationIds.value, items.map((item) => item.id));
  if (pruned.size === readNotificationIds.value.size) {
    return;
  }
  readNotificationIds.value = pruned;
  persistReadNotificationIds();
};

const notificationPoller = createPageAwarePoller({
  intervalMs: () => NOTIFICATION_POLL_INTERVAL_MS,
  isEnabled: () => true,
  poll: () => loadNotifications({ force: true })
});

const markAllRead = () => {
  if (!notifications.value.length) {
    return;
  }
  readNotificationIds.value = new Set([...readNotificationIds.value, ...notifications.value.map((item) => item.id)]);
  persistReadNotificationIds();
  ElMessage.success("已将当前通知标记为已读");
};

const openNotification = (item: NotificationItem) => {
  notificationPanelOpen.value = false;
  markNotificationRead(item.id);
  if (item.targetPath) {
    router.push(item.targetPath);
    return;
  }
  ElMessage.info(item.title);
};

const openHelp = () => {
  closeTopActionMenus();
  ElMessage.info("帮助文档功能将在接入后端后开放，当前可查看 README 和需求文档。");
};

const closeTopActionMenus = () => {
  notificationPanelOpen.value = false;
  userMenuOpen.value = false;
};

const toggleNotificationPanel = () => {
  const nextOpen = !notificationPanelOpen.value;
  userMenuOpen.value = false;
  notificationPanelOpen.value = nextOpen;
  if (nextOpen) {
    void loadNotifications();
  }
};

const toggleUserMenu = () => {
  const nextOpen = !userMenuOpen.value;
  notificationPanelOpen.value = false;
  userMenuOpen.value = nextOpen;
};

const handleDocumentKeydown = (event: KeyboardEvent) => {
  if (event.key === "Escape") {
    closeTopActionMenus();
  }
};

const handleUserCommand = async (command: string) => {
  if (command === "change-password") {
    changePasswordDialogVisible.value = true;
    return;
  }
  if (command === "settings") {
    router.push("/repoguard/settings");
    return;
  }
  if (command === "logout") {
    resetCurrentUser();
    try {
      await logout();
      ElMessage.success("已退出登录");
    } catch {
      ElMessage.warning("服务端退出失败，本地登录状态已清理");
    } finally {
      resetCurrentUser();
      await router.replace("/login");
    }
    return;
  }
  if (command === "profile") {
    ElMessage.info(currentUser.value?.email || "个人资料功能暂未开放");
    return;
  }
  ElMessage.info("个人资料功能暂未开放");
};

const handleUserMenuCommand = (command: string) => {
  userMenuOpen.value = false;
  void handleUserCommand(command);
};

const handlePasswordChanged = () => {
  resetCurrentUser();
  ElMessage.success("密码修改成功，请使用新密码重新登录");
  void router.replace("/login");
};

onMounted(() => {
  document.addEventListener("click", closeTopActionMenus);
  document.addEventListener("keydown", handleDocumentKeydown);
  loadReadNotificationIds();
  void refreshCurrentUser();
  notificationWarmupTimer = setTimeout(() => {
    notificationWarmupTimer = undefined;
    void loadNotifications();
  }, 12000);
  notificationPoller.start();
});

onBeforeUnmount(() => {
  document.removeEventListener("click", closeTopActionMenus);
  document.removeEventListener("keydown", handleDocumentKeydown);
  if (notificationWarmupTimer) {
    clearTimeout(notificationWarmupTimer);
  }
  notificationPoller.dispose();
});
</script>
