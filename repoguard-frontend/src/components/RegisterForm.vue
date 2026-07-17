<template>
  <el-form class="auth-form" :model="model" label-position="top" @submit.prevent>
    <el-form-item label="用户名">
      <el-input v-model="model.username" size="large" placeholder="请设置用户名" autocomplete="username">
        <template #prefix>
          <UserRound :size="18" />
        </template>
      </el-input>
    </el-form-item>

    <el-form-item label="邮箱地址">
      <el-input v-model="model.email" size="large" placeholder="请输入企业邮箱" autocomplete="email">
        <template #prefix>
          <Mail :size="18" />
        </template>
      </el-input>
    </el-form-item>

    <el-form-item label="密码">
      <el-input
        v-model="model.password"
        size="large"
        type="password"
        placeholder="至少 8 位，包含字母和数字"
        autocomplete="new-password"
        show-password
      >
        <template #prefix>
          <LockKeyhole :size="18" />
        </template>
      </el-input>
    </el-form-item>

    <el-form-item label="确认密码">
      <el-input
        v-model="model.confirmPassword"
        size="large"
        type="password"
        placeholder="再次输入密码"
        autocomplete="new-password"
        show-password
      >
        <template #prefix>
          <LockKeyhole :size="18" />
        </template>
      </el-input>
    </el-form-item>

    <el-button class="auth-primary" type="primary" size="large" :loading="loading" @click="$emit('submit')">立即注册</el-button>

    <div class="auth-divider">
      <span></span>
      <em>或</em>
      <span></span>
    </div>

    <el-button class="auth-secondary" size="large" @click="$emit('switch-login')">已有账户？返回登录</el-button>
  </el-form>
</template>

<script setup lang="ts">
import { LockKeyhole, Mail, UserRound } from "@lucide/vue";

interface RegisterFormModel {
  username: string;
  email: string;
  password: string;
  confirmPassword: string;
}

defineProps<{
  loading: boolean;
}>();

defineEmits<{
  submit: [];
  "switch-login": [];
}>();

const model = defineModel<RegisterFormModel>({ required: true });
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
</style>
