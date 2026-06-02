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
          <el-popover placement="bottom-end" trigger="click" width="360" popper-class="notification-popover">
            <template #reference>
              <button class="top-action-button bell-wrap" type="button" aria-label="查看通知">
                <Bell :size="20" />
                <span>12</span>
              </button>
            </template>
            <div class="notification-panel">
              <div class="notification-head">
                <strong>消息通知</strong>
                <button type="button" @click="markAllRead">全部已读</button>
              </div>
              <button v-for="item in notifications" :key="item.id" type="button" class="notification-item" @click="openNotification(item)">
                <span :class="`notification-dot ${item.level}`"></span>
                <span>
                  <b>{{ item.title }}</b>
                  <em>{{ item.description }}</em>
                  <small>{{ item.time }}</small>
                </span>
              </button>
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
import { computed, ref } from "vue";
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

const collapsed = ref(false);
const route = useRoute();
const router = useRouter();

const navItems = [
  { label: "总览", path: "/repoguard/overview", icon: Home },
  { label: "审查任务", path: "/repoguard/tasks", icon: ClipboardList },
  { label: "规则配置", path: "/repoguard/rules", icon: ShieldCheck },
  { label: "集成设置", path: "/repoguard/integrations", icon: Plug },
  { label: "系统设置", path: "/repoguard/settings", icon: Cog }
];

const notifications = [
  { id: 1, level: "danger", title: "高风险 PR 待处理", description: "PR #512 命中 2 个高风险规则", time: "2 分钟前" },
  { id: 2, level: "warning", title: "LLM 审查降级", description: "auth-service 审查已使用规则结果兜底", time: "18 分钟前" },
  { id: 3, level: "success", title: "RabbitMQ 队列正常", description: "最近一次健康检查通过", time: "35 分钟前" }
];

const currentTitle = computed(() => String(route.meta.title || "RepoGuard Agent"));

const markAllRead = () => {
  ElMessage.success("已将所有消息标记为已读");
};

const openNotification = (item: (typeof notifications)[number]) => {
  ElMessage.info(item.title);
  if (item.id === 1) {
    router.push("/repoguard/tasks/512");
  }
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
</script>
