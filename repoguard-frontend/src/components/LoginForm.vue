<template>
  <el-form class="auth-form" :model="model" label-position="top" @submit.prevent>
    <el-form-item label="用户名 / 邮箱">
      <el-input v-model="model.account" size="large" placeholder="请输入用户名或邮箱" autocomplete="username">
        <template #prefix>
          <UserRound :size="18" />
        </template>
      </el-input>
    </el-form-item>

    <el-form-item label="密码">
      <el-input
        v-model="model.password"
        size="large"
        type="password"
        placeholder="请输入密码"
        autocomplete="current-password"
        show-password
      >
        <template #prefix>
          <LockKeyhole :size="18" />
        </template>
      </el-input>
    </el-form-item>

    <div class="login-options">
      <el-checkbox v-model="model.remember">记住登录状态</el-checkbox>
      <button class="text-action" type="button" @click="$emit('forgot-password')">忘记密码?</button>
    </div>

    <el-button class="auth-primary" type="primary" size="large" :loading="loading" @click="$emit('submit')">登录</el-button>

    <div v-if="registrationEnabled" class="auth-divider">
      <span></span>
      <em>或</em>
      <span></span>
    </div>

    <el-button v-if="registrationEnabled" class="auth-secondary" size="large" @click="$emit('switch-register')">
      没有账户？立即注册
    </el-button>
  </el-form>
</template>

<script setup lang="ts">
import { LockKeyhole, UserRound } from "lucide-vue-next";

interface LoginFormModel {
  account: string;
  password: string;
  remember: boolean;
}

withDefaults(defineProps<{
  loading: boolean;
  registrationEnabled?: boolean;
}>(), {
  registrationEnabled: true
});

defineEmits<{
  submit: [];
  "forgot-password": [];
  "switch-register": [];
}>();

const model = defineModel<LoginFormModel>({ required: true });
</script>

<style scoped>
.auth-form {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.auth-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.auth-form :deep(.el-form-item__label) {
  padding-bottom: 8px;
  color: #334155;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.3;
}

.auth-form :deep(.el-input__wrapper) {
  min-height: 48px;
  border-radius: 8px;
  box-shadow: 0 0 0 1px #e5eaf3 inset;
}

.auth-form :deep(.el-input__inner) {
  color: #334155;
  font-size: 14px;
}

.auth-form :deep(.el-input__inner::placeholder) {
  color: #a8b3c5;
}

.auth-form :deep(.el-input__wrapper:hover),
.auth-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #1268ff inset;
}

.auth-form :deep(.el-input__prefix) {
  color: #a8b3c5;
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 0 0 18px;
}

.login-options :deep(.el-checkbox__label) {
  color: #64748b;
  font-size: 14px;
  font-weight: 400;
}

.text-action {
  padding: 0;
  border: 0;
  color: #1268ff;
  background: transparent;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.auth-primary,
.auth-secondary {
  width: 100%;
  min-height: 48px;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 700;
}

.auth-primary {
  border: 0;
  background: linear-gradient(90deg, #2563eb 0%, #3b82f6 100%);
  box-shadow: 0 7px 16px rgba(37, 99, 235, 0.24);
}

.auth-divider {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 16px;
  margin: 24px 0 16px;
  color: #a8b3c5;
}

.auth-divider span {
  height: 1px;
  background: #e5eaf3;
}

.auth-divider em {
  font-style: normal;
}

.auth-secondary {
  border-color: #e5eaf3;
  color: #334155;
  background: #ffffff;
  box-shadow: none;
}

@media (max-width: 560px) {
  .login-options {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
  }
}
</style>
