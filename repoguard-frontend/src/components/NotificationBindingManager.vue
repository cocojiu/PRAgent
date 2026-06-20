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

    <NotificationBindingTable
      :bindings="notificationBindings"
      :can-manage="canManage"
      :loading="loadingBindings"
      :testing-binding-id="testingBindingId"
      :border="true"
      :action-width="320"
      :name-min-width="140"
      :status-width="130"
      status-display="tag"
      @edit="openBindingDialog"
      @remove="removeBinding"
      @test="runBindingTest"
      @toggle="toggleBinding"
    />

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
import { NotificationBindingDialog, NotificationBindingTable, useNotificationBindings } from "@/features/notification-ops";
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

onMounted(() => {
  void refreshNotificationBindings();
});
</script>
