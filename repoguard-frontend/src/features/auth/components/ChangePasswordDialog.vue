<template>
  <el-dialog
    v-model="visibleModel"
    title="修改密码"
    width="460px"
    append-to-body
    destroy-on-close
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    @closed="resetForm"
  >
    <div class="change-password-dialog">
      <el-alert
        title="修改成功后，当前登录和其他已登录会话都会失效，需要使用新密码重新登录。"
        type="info"
        show-icon
        :closable="false"
      />
      <el-alert
        v-if="errorMessage"
        class="change-password-error"
        :title="errorMessage"
        type="error"
        show-icon
        :closable="false"
      />
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="当前密码">
          <el-input
            v-model="form.currentPassword"
            type="password"
            placeholder="请输入当前密码"
            autocomplete="current-password"
            show-password
            :disabled="submitting"
            @input="clearError"
          />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="form.newPassword"
            type="password"
            placeholder="至少 8 位，包含字母和数字"
            autocomplete="new-password"
            show-password
            :disabled="submitting"
            @input="clearError"
          />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="再次输入新密码"
            autocomplete="new-password"
            show-password
            :disabled="submitting"
            @input="clearError"
            @keyup.enter="submit"
          />
        </el-form-item>
      </el-form>
      <p class="password-requirement">密码长度为 8–128 位，必须同时包含字母和数字。</p>
    </div>
    <template #footer>
      <el-button :disabled="submitting" @click="visibleModel = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">确认修改</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { changePassword, type PasswordChangeRequest } from "@/api/auth";
import { RequestError, getErrorMessage } from "@/utils/errors";

const visibleModel = defineModel<boolean>({ required: true });
const emit = defineEmits<{
  changed: [];
}>();

const submitting = ref(false);
const errorMessage = ref("");
const form = reactive<PasswordChangeRequest>({
  currentPassword: "",
  newPassword: "",
  confirmPassword: ""
});

const clearError = () => {
  errorMessage.value = "";
};

const validationError = () => {
  if (form.currentPassword.length < 8 || form.currentPassword.length > 128) {
    return "请输入 8–128 位当前密码";
  }
  if (form.newPassword.length < 8 || form.newPassword.length > 128) {
    return "新密码长度必须为 8–128 位";
  }
  if (!/\p{L}/u.test(form.newPassword) || !/\p{N}/u.test(form.newPassword)) {
    return "新密码必须同时包含字母和数字";
  }
  if (form.newPassword === form.currentPassword) {
    return "新密码不能与当前密码相同";
  }
  if (form.newPassword !== form.confirmPassword) {
    return "两次输入的新密码不一致";
  }
  return "";
};

const passwordChangeError = (error: unknown) => {
  if (error instanceof RequestError) {
    if (error.status === 401) {
      return "当前密码不正确，请重新输入";
    }
    if (error.status === 429) {
      return "操作过于频繁，请稍后再试";
    }
    if (error.code === "BAD_REQUEST") {
      return "新密码不符合安全要求，请检查后重试";
    }
  }
  return getErrorMessage(error, "密码修改失败，请稍后重试");
};

const submit = async () => {
  if (submitting.value) {
    return;
  }
  const invalidReason = validationError();
  if (invalidReason) {
    errorMessage.value = invalidReason;
    return;
  }
  submitting.value = true;
  errorMessage.value = "";
  try {
    await changePassword({ ...form });
    visibleModel.value = false;
    emit("changed");
  } catch (error) {
    errorMessage.value = passwordChangeError(error);
  } finally {
    submitting.value = false;
  }
};

const resetForm = () => {
  form.currentPassword = "";
  form.newPassword = "";
  form.confirmPassword = "";
  errorMessage.value = "";
  submitting.value = false;
};
</script>

<style scoped>
.change-password-dialog {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.change-password-error {
  margin-top: -4px;
}

.change-password-dialog :deep(.el-form-item) {
  margin-bottom: 18px;
}

.change-password-dialog :deep(.el-form-item__label) {
  color: #334155;
  font-weight: 700;
}

.change-password-dialog :deep(.el-input__wrapper) {
  min-height: 44px;
  border-radius: 8px;
}

.password-requirement {
  margin: -8px 0 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 560px) {
  .change-password-dialog {
    min-width: 0;
  }
}
</style>
