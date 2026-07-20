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
          <el-popover placement="bottom-end" trigger="click" width="380" popper-class="notification-popover" @show="loadNotifications()">
            <template #reference>
              <button class="top-action-button bell-wrap" type="button" aria-label="查看通知">
                <Bell :size="20" />
                <span v-if="unreadCount" class="notification-badge">{{ unreadBadgeText }}</span>
              </button>
            </template>
            <div class="notification-panel">
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
              <RouterLink class="notification-more" to="/repoguard/tasks">查看全部消息 →</RouterLink>
            </div>
          </el-popover>

          <button class="top-action-button" type="button" aria-label="帮助文档" @click="openHelp">
            <CircleHelp :size="20" />
          </button>

          <el-dropdown trigger="click" @command="handleUserCommand">
            <button class="user" type="button">
              <span class="avatar">{{ currentUserInitial }}</span>
              <span>{{ currentUserName }}</span>
              <ChevronDown :size="16" />
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人资料</el-dropdown-item>
                <el-dropdown-item command="change-password">修改密码</el-dropdown-item>
                <el-dropdown-item command="settings">系统设置</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
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
import { fetchNotifications } from "@/api/notifications";
import { canManage, currentUser, loadCurrentUser, resetCurrentUser } from "@/stores/authState";
import type { NotificationCenter, NotificationItem } from "@/types";

const ChangePasswordDialog = defineAsyncComponent(
  () => import("@/features/auth/components/ChangePasswordDialog.vue")
);
const collapsed = ref(false);
const route = useRoute();
const router = useRouter();
const changePasswordDialogVisible = ref(false);
const notificationCenter = ref<NotificationCenter>();
const loadingNotifications = ref(false);
const notificationError = ref("");
const readNotificationIds = ref<Set<string>>(new Set());
const NOTIFICATION_READ_KEY = "repoguard-read-notifications";
let notificationWarmupTimer: ReturnType<typeof setTimeout> | undefined;

const navItems = [
  { label: "总览", path: "/repoguard/overview", icon: Home },
  { label: "审查任务", path: "/repoguard/tasks", icon: ClipboardList },
  { label: "规则配置", path: "/repoguard/rules", icon: ShieldCheck },
  { label: "集成设置", path: "/repoguard/integrations", icon: Plug },
  { label: "消息队列", path: "/repoguard/message-queue", icon: RadioTower },
  { label: "通知运维", path: "/repoguard/notifications", icon: BellRing },
  { label: "用户管理", path: "/repoguard/users", icon: Users, requiresManage: true },
  { label: "系统设置", path: "/repoguard/settings", icon: Cog }
];

const visibleNavItems = computed(() => navItems.filter((item) => !item.requiresManage || canManage.value));
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
    notificationCenter.value = await fetchNotifications();
  } catch (error) {
    notificationError.value = error instanceof Error ? error.message : "通知加载失败";
  } finally {
    loadingNotifications.value = false;
  }
};

const markAllRead = () => {
  if (!notifications.value.length) {
    return;
  }
  readNotificationIds.value = new Set([...readNotificationIds.value, ...notifications.value.map((item) => item.id)]);
  persistReadNotificationIds();
  ElMessage.success("已将当前通知标记为已读");
};

const openNotification = (item: NotificationItem) => {
  markNotificationRead(item.id);
  if (item.targetPath) {
    router.push(item.targetPath);
    return;
  }
  ElMessage.info(item.title);
};

const openHelp = () => {
  ElMessage.info("帮助文档功能将在接入后端后开放，当前可查看 README 和需求文档。");
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
    await logout();
    resetCurrentUser();
    ElMessage.success("已退出登录");
    router.push("/login");
    return;
  }
  if (command === "profile") {
    ElMessage.info(currentUser.value?.email || "个人资料功能暂未开放");
    return;
  }
  ElMessage.info("个人资料功能暂未开放");
};

const handlePasswordChanged = () => {
  resetCurrentUser();
  ElMessage.success("密码修改成功，请使用新密码重新登录");
  void router.replace("/login");
};

onMounted(() => {
  loadReadNotificationIds();
  void refreshCurrentUser();
  notificationWarmupTimer = setTimeout(() => {
    notificationWarmupTimer = undefined;
    void loadNotifications();
  }, 1200);
});

onBeforeUnmount(() => {
  if (notificationWarmupTimer) {
    clearTimeout(notificationWarmupTimer);
  }
});
</script>
