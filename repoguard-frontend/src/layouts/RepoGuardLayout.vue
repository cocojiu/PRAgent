<template>
  <div class="rg-shell">
    <aside class="rg-sidebar" :class="{ collapsed }">
      <div class="brand">
        <div class="brand-icon">◆</div>
        <strong v-if="!collapsed">RepoGuard Agent</strong>
      </div>
      <nav class="nav-list">
        <RouterLink v-for="item in navItems" :key="item.path" class="nav-item" :to="item.path">
          <component :is="item.icon" :size="20" />
          <span v-if="!collapsed">{{ item.label }}</span>
        </RouterLink>
      </nav>
      <button class="collapse-btn" @click="collapsed = !collapsed">
        <PanelLeftClose :size="18" />
        <span v-if="!collapsed">收起菜单</span>
      </button>
      <div v-if="!collapsed" class="version">v1.0.0</div>
    </aside>
    <main class="rg-main">
      <header class="rg-topbar">
        <button class="icon-button" @click="collapsed = !collapsed">
          <Menu :size="22" />
        </button>
        <div class="top-title">{{ currentTitle }}</div>
        <div class="top-actions">
          <el-popover placement="bottom-end" trigger="click" width="380" popper-class="notification-popover" @show="loadNotifications">
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
                <button type="button" @click="loadNotifications">重试</button>
              </div>
              <div v-else-if="!notifications.length" class="notification-state">暂无待处理通知</div>
              <template v-else>
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
              </template>
              <RouterLink class="notification-more" to="/repoguard/tasks">查看全部消息 ›</RouterLink>
            </div>
          </el-popover>

          <button class="top-action-button" type="button" aria-label="帮助文档" @click="openHelp">
            <CircleHelp :size="20" />
          </button>

          <el-dropdown trigger="click" @command="handleUserCommand">
            <button class="user" type="button">
              <span class="avatar">A</span>
              <span>管理员</span>
              <ChevronDown :size="16" />
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人资料</el-dropdown-item>
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
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
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
  ShieldCheck
} from "lucide-vue-next";
import { fetchNotifications } from "@/api/notifications";
import type { NotificationCenter, NotificationItem } from "@/types";

const collapsed = ref(false);
const route = useRoute();
const router = useRouter();
const notificationCenter = ref<NotificationCenter>();
const loadingNotifications = ref(false);
const notificationError = ref("");
const readNotificationIds = ref<Set<string>>(new Set());
const NOTIFICATION_READ_KEY = "repoguard-read-notifications";

const navItems = [
  { label: "总览", path: "/repoguard/overview", icon: Home },
  { label: "审查任务", path: "/repoguard/tasks", icon: ClipboardList },
  { label: "规则配置", path: "/repoguard/rules", icon: ShieldCheck },
  { label: "集成设置", path: "/repoguard/integrations", icon: Plug },
  { label: "系统设置", path: "/repoguard/settings", icon: Cog }
];

const currentTitle = computed(() => String(route.meta.title || "RepoGuard Agent"));
const notifications = computed(() => notificationCenter.value?.items ?? []);
const unreadCount = computed(() => notifications.value.filter((item) => !isNotificationRead(item.id)).length);
const unreadBadgeText = computed(() => (unreadCount.value > 99 ? "99+" : String(unreadCount.value)));

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

const loadNotifications = async () => {
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

const handleUserCommand = (command: string) => {
  if (command === "settings") {
    router.push("/repoguard/settings");
    return;
  }
  if (command === "logout") {
    ElMessage.success("已退出登录（模拟）");
    return;
  }
  ElMessage.info("个人资料功能暂未接入后端");
};

onMounted(() => {
  loadReadNotificationIds();
  void loadNotifications();
});
</script>
