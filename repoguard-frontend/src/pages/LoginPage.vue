<template>
  <main class="login-page">
    <LoginHeroPanel />

    <section class="login-form-panel" aria-label="登录与注册">
      <div class="login-form-wrap">
        <button v-if="authMode === 'register'" class="login-back" type="button" @click="switchMode('login')">
          <ArrowLeft :size="18" />
          <span>返回登录</span>
        </button>

        <div class="login-heading">
          <h2>{{ authMode === "login" ? "欢迎回来" : "创建账户" }}</h2>
          <p>{{ authMode === "login" ? "登录您的账户以继续使用" : "填写以下信息完成注册" }}</p>
        </div>

        <LoginForm
          v-if="authMode === 'login'"
          v-model="loginForm"
          :loading="submitting"
          :registration-enabled="registrationEnabled"
          @forgot-password="handleForgotPassword"
          @submit="handleLogin"
          @switch-register="switchMode('register')"
        />

        <RegisterForm
          v-else
          v-model="registerForm"
          :loading="submitting"
          @submit="handleRegister"
          @switch-login="switchMode('login')"
        />

        <footer class="login-footer">© 2026 RepoGuard Agent · 安全可信赖的代码审查平台</footer>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { ArrowLeft } from "@lucide/vue";
import { login, register } from "@/api/auth";
import LoginForm from "@/components/LoginForm.vue";
import LoginHeroPanel from "@/components/LoginHeroPanel.vue";
import RegisterForm from "@/components/RegisterForm.vue";
import { resolveSafePostAuthRedirect } from "@/router/authRedirect";
import { getAuthErrorMessage } from "@/utils/errors";

type AuthMode = "login" | "register";

const router = useRouter();
const route = useRoute();
const authMode = ref<AuthMode>("login");
const submitting = ref(false);
const registrationEnabled = import.meta.env.VITE_REGISTRATION_ENABLED === "true"
  || (import.meta.env.DEV && import.meta.env.VITE_REGISTRATION_ENABLED !== "false");

const loginForm = ref({
  account: "",
  password: "",
  remember: false
});

const registerForm = ref({
  username: "",
  email: "",
  password: "",
  confirmPassword: ""
});

const switchMode = (mode: AuthMode) => {
  if (mode === "register" && !registrationEnabled) {
    ElMessage.info("公开注册已关闭，请联系管理员开通账号");
    return;
  }
  authMode.value = mode;
};

const handleForgotPassword = () => {
  ElMessage.info("密码找回功能将在账号体系接入后开放");
};

const handleLogin = async () => {
  if (submitting.value) {
    return;
  }
  if (!loginForm.value.account.trim() || !loginForm.value.password) {
    ElMessage.warning("请输入账号和密码");
    return;
  }
  submitting.value = true;
  try {
    await login({
      account: loginForm.value.account.trim(),
      password: loginForm.value.password,
      remember: loginForm.value.remember
    });
    ElMessage.success("登录成功");
    void router.replace(resolveSafePostAuthRedirect(route.query.redirect));
  } catch (error) {
    ElMessage.error(getAuthErrorMessage(error, "登录失败"));
  } finally {
    submitting.value = false;
  }
};

const handleRegister = async () => {
  if (submitting.value) {
    return;
  }
  if (!registerForm.value.username.trim() || !registerForm.value.email.trim() || !registerForm.value.password || !registerForm.value.confirmPassword) {
    ElMessage.warning("请完整填写注册信息");
    return;
  }
  if (registerForm.value.password && registerForm.value.confirmPassword && registerForm.value.password !== registerForm.value.confirmPassword) {
    ElMessage.warning("两次输入的密码不一致");
    return;
  }
  submitting.value = true;
  try {
    await register({
      username: registerForm.value.username.trim(),
      email: registerForm.value.email.trim(),
      password: registerForm.value.password,
      confirmPassword: registerForm.value.confirmPassword
    });
    ElMessage.success("注册成功");
    void router.replace(resolveSafePostAuthRedirect(route.query.redirect));
  } catch (error) {
    ElMessage.error(getAuthErrorMessage(error, "注册失败"));
  } finally {
    submitting.value = false;
  }
};
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(500px, 52vw) minmax(460px, 1fr);
  font-family:
    Inter, "HarmonyOS Sans SC", "PingFang SC", "Microsoft YaHei", system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI",
    sans-serif;
  background: #f6f8fb;
}

.login-form-panel {
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: 48px 56px;
}

.login-form-wrap {
  width: min(100%, 438px);
}

.login-back {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 20px;
  padding: 0;
  border: 0;
  color: #64748b;
  background: transparent;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.login-heading {
  margin-bottom: 28px;
}

.login-heading h2 {
  margin: 0;
  color: #0f172a;
  font-size: 28px;
  line-height: 1.2;
  font-weight: 800;
  letter-spacing: 0;
}

.login-heading p {
  margin: 10px 0 0;
  color: #64748b;
  font-size: 14px;
}

.login-footer {
  margin-top: 30px;
  color: #9aa8bd;
  text-align: center;
  font-size: 13px;
}

@media (max-width: 980px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .login-form-panel {
    min-height: auto;
    padding: 44px 24px;
  }
}

@media (max-width: 560px) {
  .login-form-panel {
    padding: 34px 18px;
  }

  .login-heading h2 {
    font-size: 28px;
  }
}
</style>
