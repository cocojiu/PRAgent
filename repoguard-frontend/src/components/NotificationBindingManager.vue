<template>
  <div class="notification-binding-manager">
    <el-alert v-if="bindingLoadError" :title="bindingLoadError" type="error" :closable="false" />
    <p v-if="bindingsNeedRefresh" role="status">当前渠道列表尚未确认最新状态，请刷新查询。</p>
    <div class="notification-bindings__head">
      <div>
        <h3>消息通知绑定</h3>
        <p>按仓库绑定钉钉或企业微信群机器人，审查结果和评论回写会通过独立通知队列异步发送。</p>
      </div>
      <div>
        <el-button :loading="loadingBindings" @click="refreshNotificationBindings">刷新渠道</el-button>
        <el-button type="primary" :disabled="!canManage" @click="openBindingDialog()">新增绑定</el-button>
      </div>
    </div>

    <NotificationBindingTable
      :bindings="notificationBindings"
      :can-manage="canManage"
      :loading="loadingBindings"
      :actions-disabled="!bindingsCurrent"
      :testing-binding-id="testingBindingId"
      :busy-binding-ids="busyBindingIds"
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
    <el-pagination
      v-if="bindingTotal > bindingPageSize"
      v-model:current-page="bindingPage"
      v-model:page-size="bindingPageSize"
      class="table-pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="[10, 20, 50, 100]"
      :total="bindingTotal"
      @current-change="changeBindingPage"
      @size-change="changeBindingPageSize"
    />

    <NotificationBindingDialog
      v-model:visible="bindingDialogVisible"
      :can-manage="canManage"
      :editing-binding-id="editingBindingId"
      :form="bindingForm"
      :saving="savingBinding"
      :can-save="bindingCanSave"
      :has-unsaved-changes="bindingHasUnsavedChanges"
      :save-error="bindingSaveError"
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
  bindingPage,
  bindingPageSize,
  bindingTotal,
  bindingsLoading: loadingBindings,
  bindingLoadError,
  bindingsNeedRefresh,
  bindingsCurrent,
  bindingDialogVisible,
  savingBinding,
  testingBindingId,
  busyBindingIds,
  editingBindingId,
  bindingForm,
  bindingCanSave,
  bindingHasUnsavedChanges,
  bindingSaveError,
  loadNotificationBindings: refreshNotificationBindings,
  openBindingDialog,
  saveBinding,
  runBindingTest,
  toggleBinding,
  removeBinding,
  changeBindingPage,
  changeBindingPageSize
} = useNotificationBindings();

onMounted(() => {
  void refreshNotificationBindings();
});
</script>
