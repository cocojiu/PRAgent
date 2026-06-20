<template>
  <el-table v-loading="loading" :data="bindings" :border="border" :class="tableClass" :size="size">
    <el-table-column prop="name" label="名称" :min-width="nameMinWidth" />
    <el-table-column label="平台" width="120">
      <template #default="{ row }">{{ providerText(row.provider) }}</template>
    </el-table-column>
    <el-table-column label="仓库" min-width="180">
      <template #default="{ row }">{{ row.organization }}/{{ row.repository }}</template>
    </el-table-column>
    <el-table-column label="状态" :width="statusWidth">
      <template #default="{ row }">
        <el-tag v-if="statusDisplay === 'tag'" :type="row.enabled ? 'success' : 'info'">
          {{ row.enabled ? "启用" : "停用" }}
        </el-tag>
        <span v-else :class="`status-pill ${row.enabled ? 'success' : 'pending'}`">
          {{ row.enabled ? "启用" : "停用" }}
        </span>
      </template>
    </el-table-column>
    <el-table-column prop="lastCheckedAt" label="最近检测" min-width="160" />
    <el-table-column prop="lastError" label="最近错误" min-width="220" show-overflow-tooltip />
    <el-table-column label="操作" :width="actionWidth" fixed="right">
      <template #default="{ row }">
        <div :class="{ 'table-actions': actionLayout === 'group' }">
          <el-button size="small" :disabled="!canManage" @click="emit('edit', row)">编辑</el-button>
          <el-button size="small" :disabled="!canManage" :loading="testingBindingId === row.id" @click="emit('test', row.id)">测试</el-button>
          <el-button size="small" :disabled="!canManage" @click="emit('toggle', row)">
            {{ row.enabled ? "停用" : "启用" }}
          </el-button>
          <el-button size="small" type="danger" :disabled="!canManage" @click="emit('remove', row.id)">删除</el-button>
        </div>
      </template>
    </el-table-column>
  </el-table>
</template>

<script setup lang="ts">
import type { NotificationBinding } from "@/types";
import { providerText } from "../notificationOpsDisplayMappers";

withDefaults(
  defineProps<{
    actionLayout?: "inline" | "group";
    actionWidth?: number;
    bindings: NotificationBinding[];
    border?: boolean;
    canManage: boolean;
    loading: boolean;
    nameMinWidth?: number;
    size?: "default" | "small" | "large";
    statusDisplay?: "pill" | "tag";
    statusWidth?: number;
    tableClass?: string;
    testingBindingId?: number;
  }>(),
  {
    actionLayout: "inline",
    actionWidth: 292,
    border: false,
    nameMinWidth: 150,
    size: "default",
    statusDisplay: "pill",
    statusWidth: 120,
    tableClass: "",
    testingBindingId: undefined
  }
);

const emit = defineEmits<{
  edit: [binding: NotificationBinding];
  remove: [id: number];
  test: [id: number];
  toggle: [binding: NotificationBinding];
}>();
</script>
