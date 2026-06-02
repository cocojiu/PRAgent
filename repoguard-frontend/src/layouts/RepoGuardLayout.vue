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
          <div class="bell-wrap">
            <Bell :size="20" />
            <span>12</span>
          </div>
          <CircleHelp :size="20" />
          <div class="user">
            <span class="avatar">A</span>
            <span>管理员</span>
            <ChevronDown :size="16" />
          </div>
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
import { RouterLink, RouterView, useRoute } from "vue-router";
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
  Settings,
  ShieldCheck
} from "lucide-vue-next";

const collapsed = ref(false);
const route = useRoute();

const navItems = [
  { label: "总览", path: "/repoguard/overview", icon: Home },
  { label: "审查任务", path: "/repoguard/tasks", icon: ClipboardList },
  { label: "规则配置", path: "/repoguard/rules", icon: ShieldCheck },
  { label: "集成设置", path: "/repoguard/integrations", icon: Plug },
  { label: "系统设置", path: "/repoguard/settings", icon: Cog }
];

const currentTitle = computed(() => String(route.meta.title || "RepoGuard Agent"));
</script>

