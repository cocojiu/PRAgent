<template>
  <div class="notification-binding-manager">
    <div class="notification-bindings__head">
      <div>
        <h3>消息通知绑定</h3>
        <p>按仓库绑定钉钉或企业微信群机器人，审查结果和评论回写会通过独立通知队列异步发送。</p>
      </div>
      <el-button type="primary" :disabled="!canManage" @click="openBindingDialog()">
        新增绑定
      </el-button>
    </div>

    <el-table v-loading="loadingBindings" :data="notificationBindings" border>
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="平台" width="120">
        <template #default="{ row }">{{ providerText(row.provider) }}</template>
      </el-table-column>
      <el-table-column label="仓库" min-width="180">
        <template #default="{ row }">{{ row.organization }}/{{ row.repository }}</template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? "启用" : "停用" }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastCheckedAt" label="最近检测" min-width="160" />
      <el-table-column prop="lastError" label="最近错误" min-width="220" show-overflow-tooltip />
      <el-table-column label="操作" width="320" fixed="right">
        <template #default="{ row }">
          <el-button size="small" :disabled="!canManage" @click="openBindingDialog(row)">编辑</el-button>
          <el-button size="small" :disabled="!canManage" :loading="testingBindingId === row.id" @click="runBindingTest(row.id)">测试</el-button>
          <el-button size="small" :disabled="!canManage" @click="toggleBinding(row)">
            {{ row.enabled ? "停用" : "启用" }}
          </el-button>
          <el-button size="small" type="danger" :disabled="!canManage" @click="removeBinding(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <NotificationBindingDialog
      v-model:visible="bindingDialogVisible"
      :can-manage="canManage"
      :editing-binding-id="editingBindingId"
      :form="bindingForm"
      :saving="savingBinding"
      @save="saveBinding"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted } from "vue";
import { NotificationBindingDialog, useNotificationBindings } from "@/features/notification-ops";
import { canManage } from "@/stores/authState";

const {
  notificationBindings,
  bindingsLoading: loadingBindings,
  bindingDialogVisible,
  savingBinding,
  testingBindingId,
  editingBindingId,
  bindingForm,
  loadNotificationBindings: refreshNotificationBindings,
  openBindingDialog,
  saveBinding,
  runBindingTest,
  toggleBinding,
  removeBinding
} = useNotificationBindings();

const providerText = (provider: string) => {
  if (provider === "DINGTALK") {
    return "钉钉";
  }
  if (provider === "WECOM") {
    return "企业微信";
  }
  return provider;
};

onMounted(() => {
  void refreshNotificationBindings();
});
</script>
