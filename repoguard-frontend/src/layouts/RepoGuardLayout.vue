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
      <div v-if="!collapsed" class="version">v{{ APP_VERSION }}</div>
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
          <div v-if="enterpriseEditionEnabled" class="top-action-menu-shell" @click.stop>
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
                  <small v-if="notificationCenter?.generatedAt">更新于 {{ formatDateTime(notificationCenter.generatedAt) }}</small>
                </div>
                <button type="button" :disabled="!notificationCurrent || bulkSaving || (!unreadCount && !unsyncedCount)" @click.stop="markAllRead">全部已读</button>
              </div>
              <button type="button" :disabled="loadingNotifications" @click="refreshNotifications">刷新通知与已读记录</button>
              <p v-if="!notificationCurrent && notifications.length" class="notification-state">以下为上次读取的通知，请刷新确认后再操作。</p>
              <p v-if="readKeysError" class="notification-state notification-state--error">{{ readKeysError }}</p>
              <p v-if="storageError" class="notification-state notification-state--error">{{ storageError }}</p>
              <p v-if="bulkNotice" class="notification-state">{{ bulkNotice }}</p>
              <div v-if="loadingNotifications" class="notification-state">正在加载通知...</div>
              <div v-else-if="notificationError" class="notification-state notification-state--error">
                <span>{{ notificationError }}</span>
                <button type="button" @click="refreshNotifications">重试</button>
              </div>
              <div v-else-if="!notifications.length" class="notification-state">暂无待处理通知</div>
              <div v-else class="notification-list">
                <div v-for="item in notifications" :key="item.id">
                  <button
                    type="button"
                    :class="['notification-item', { read: isNotificationRead(item.id) }]"
                    :disabled="!notificationCurrent"
                    @click="openNotification(item)"
                  >
                    <span :class="`notification-dot ${item.level}`"></span>
                    <span>
                      <b>{{ item.title }}</b>
                      <em>{{ item.description }}</em>
                      <small>{{ item.time }}</small>
                      <small v-if="pendingReadIds.has(item.id)">已读状态正在同步</small>
                      <small v-else-if="isNotificationRead(item.id) && !isReadConfirmed(item.id)">本地已读，服务端未确认</small>
                      <small v-if="readSyncErrors[item.id]">{{ readSyncErrors[item.id] }}</small>
                    </span>
                  </button>
                  <button
                    v-if="isNotificationRead(item.id) && !isReadConfirmed(item.id)" type="button"
                    :disabled="!notificationCurrent || pendingReadIds.has(item.id) || bulkSaving" @click.stop="markNotificationRead(item.id)"
                  >重试已读同步</button>
                </div>
              </div>
              <RouterLink class="notification-more" to="/repoguard/tasks" @click="notificationPanelOpen = false">
                查看全部审查任务 →
              </RouterLink>
              <RouterLink v-if="enterpriseEditionEnabled" class="notification-more" to="/repoguard/tasks?review=pending" @click="notificationPanelOpen = false">
                查看全部待复核任务 →
              </RouterLink>
            </div>
          </div>

          <TenantSwitcher v-if="enterpriseEditionEnabled" />

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
import { formatDateTime } from "@/utils/dateTime";
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import {
  Bell,
  ChevronDown,
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
import { createPageAwarePoller } from "@/composables/pageAwarePoller";
import { useNotificationCenter } from "@/layouts/useNotificationCenter";
import { canAccessRouteMeta } from "@/router/accessPolicy";
import { canManage, currentUser, loadCurrentUser, resetCurrentUser } from "@/stores/authState";
import { APP_VERSION } from "@/config/appVersion";
import { enterpriseEditionEnabled } from "@/config/edition";
import type { NotificationItem } from "@/types";

const ChangePasswordDialog = defineAsyncComponent(
  () => import("@/features/auth/components/ChangePasswordDialog.vue")
);
const TenantSwitcher = defineAsyncComponent(
  () => import("@/components/TenantSwitcher.vue")
);
const collapsed = ref(false);
const route = useRoute();
const router = useRouter();
const changePasswordDialogVisible = ref(false);
const notificationPanelOpen = ref(false);
const userMenuOpen = ref(false);
const { notificationCenter, notifications, loadingNotifications, notificationCurrent, notificationError, readKeysError, storageError,
  readSyncErrors, bulkNotice, pendingReadIds, bulkSaving, unreadCount, unsyncedCount, isNotificationRead, isReadConfirmed,
  currentNotification, loadNotifications, refreshNotifications, markNotificationRead, markAllRead } = useNotificationCenter();
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
  { label: "租户与仓库", path: "/repoguard/tenants", icon: Users },
  { label: "系统设置", path: "/repoguard/settings", icon: Cog }
];

const canOpenPath = (path: string) => canAccessRouteMeta(router.resolve(path).meta, {
  authenticated: hasAuthToken(),
  managementAllowed: canManage.value,
  enterpriseEnabled: enterpriseEditionEnabled,
  role: currentUser.value?.role
});
const visibleNavItems = computed(() => navItems.filter((item) => canOpenPath(item.path)));
const currentTitle = computed(() => String(route.meta.title || "RepoGuard Agent"));
const unreadBadgeText = computed(() => (unreadCount.value > 99 ? "99+" : String(unreadCount.value)));
const currentUserName = computed(() => currentUser.value?.username || "管理员");
const currentUserInitial = computed(() => (currentUserName.value.trim().charAt(0) || "A").toUpperCase());

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

const notificationPoller = createPageAwarePoller({
  intervalMs: () => NOTIFICATION_POLL_INTERVAL_MS,
  isEnabled: () => enterpriseEditionEnabled,
  poll: async () => { await loadNotifications({ force: true }); }
});

const openNotification = (item: NotificationItem) => {
  const selected = currentNotification(item.id);
  if (!selected) return;
  notificationPanelOpen.value = false;
  void markNotificationRead(selected.id);
  if (selected.targetPath) {
    router.push(selected.targetPath);
    return;
  }
  ElMessage.info(selected.title);
};

const closeTopActionMenus = () => {
  notificationPanelOpen.value = false;
  userMenuOpen.value = false;
};

const toggleNotificationPanel = () => {
  if (!enterpriseEditionEnabled) {
    return;
  }
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
  void refreshCurrentUser();
  if (enterpriseEditionEnabled) {
    notificationWarmupTimer = setTimeout(() => {
      notificationWarmupTimer = undefined;
      void loadNotifications();
    }, 12000);
    notificationPoller.start();
  }
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
