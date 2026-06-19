<template>
  <el-dialog v-model="visibleModel" :title="editingBindingId ? '编辑消息通知绑定' : '新增消息通知绑定'" width="640px">
    <el-form label-width="120px">
      <el-form-item label="名称">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="平台">
        <el-select v-model="form.provider">
          <el-option label="钉钉" value="DINGTALK" />
          <el-option label="企业微信" value="WECOM" />
        </el-select>
      </el-form-item>
      <el-form-item label="组织">
        <el-input v-model="form.organization" />
      </el-form-item>
      <el-form-item label="仓库">
        <el-input v-model="form.repository" />
      </el-form-item>
      <el-form-item label="Webhook">
        <el-input v-model="form.webhookUrl" type="password" show-password placeholder="机器人 Webhook URL" />
      </el-form-item>
      <el-form-item label="签名 Secret">
        <el-input v-model="form.secret" type="password" show-password placeholder="可选；钉钉加签 Secret" />
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
      <el-form-item label="通知事件">
        <el-checkbox v-model="form.notifyReviewCompleted">审查完成</el-checkbox>
        <el-checkbox v-model="form.notifyReviewFailed">审查失败</el-checkbox>
        <el-checkbox v-model="form.notifyHumanReviewRequired">人工复核</el-checkbox>
        <el-checkbox v-model="form.notifyGithubComment">评论回写</el-checkbox>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visibleModel = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!canManage" @click="emit('save')">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from "vue";
import type { NotificationBindingRequest } from "@/types";

const props = defineProps<{
  canManage: boolean;
  editingBindingId?: number;
  form: NotificationBindingRequest;
  saving: boolean;
  visible: boolean;
}>();

const emit = defineEmits<{
  save: [];
  "update:visible": [value: boolean];
}>();

const visibleModel = computed({
  get: () => props.visible,
  set: (value: boolean) => emit("update:visible", value)
});
</script>
