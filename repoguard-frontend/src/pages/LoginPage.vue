<template>
  <main class="login-page">
    <section class="login-brand-panel" aria-label="RepoGuard Agent">
      <header class="login-brand">
        <span class="login-brand-icon">
          <Shield :size="24" />
        </span>
        <strong>RepoGuard Agent</strong>
      </header>

      <div class="login-brand-content">
        <h1>智能代码安全<br />审查平台</h1>
        <p>基于 AI 驱动的代码审查与安全检测，全面保障您的软件供应链安全。</p>

        <div class="login-feature-list">
          <div v-for="feature in features" :key="feature.title" class="login-feature">
            <span class="login-feature-dot"></span>
            <div>
              <strong>{{ feature.title }}</strong>
              <span>{{ feature.description }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

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

        <el-form v-if="authMode === 'login'" class="auth-form" :model="loginForm" label-position="top" @submit.prevent>
          <el-form-item label="用户名 / 邮箱">
            <el-input v-model="loginForm.account" size="large" placeholder="请输入用户名或邮箱" autocomplete="username">
              <template #prefix>
                <UserRound :size="18" />
              </template>
            </el-input>
          </el-form-item>

          <el-form-item label="密码">
            <el-input
              v-model="loginForm.password"
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
            <el-checkbox v-model="loginForm.remember">记住登录状态</el-checkbox>
            <button class="text-action" type="button" @click="handleForgotPassword">忘记密码?</button>
          </div>

          <el-button class="auth-primary" type="primary" size="large" @click="handleLogin">登录</el-button>

          <div class="auth-divider">
            <span></span>
            <em>或</em>
            <span></span>
          </div>

          <el-button class="auth-secondary" size="large" @click="switchMode('register')">没有账户？立即注册</el-button>
        </el-form>

        <el-form v-else class="auth-form" :model="registerForm" label-position="top" @submit.prevent>
          <el-form-item label="用户名">
            <el-input v-model="registerForm.username" size="large" placeholder="请设置用户名" autocomplete="username">
              <template #prefix>
                <UserRound :size="18" />
              </template>
            </el-input>
          </el-form-item>

          <el-form-item label="邮箱地址">
            <el-input v-model="registerForm.email" size="large" placeholder="请输入企业邮箱" autocomplete="email">
              <template #prefix>
                <Mail :size="18" />
              </template>
            </el-input>
          </el-form-item>

          <el-form-item label="密码">
            <el-input
              v-model="registerForm.password"
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
              v-model="registerForm.confirmPassword"
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

          <el-button class="auth-primary" type="primary" size="large" @click="handleRegister">立即注册</el-button>

          <div class="auth-divider">
            <span></span>
            <em>或</em>
            <span></span>
          </div>

          <el-button class="auth-secondary" size="large" @click="switchMode('login')">已有账户？返回登录</el-button>
        </el-form>

        <footer class="login-footer">© 2026 RepoGuard Agent · 安全可信赖的代码审查平台</footer>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { ArrowLeft, LockKeyhole, Mail, Shield, UserRound } from "lucide-vue-next";

type AuthMode = "login" | "register";

const router = useRouter();
const authMode = ref<AuthMode>("login");

const features = [
  {
    title: "自动化 PR 安全扫描",
    description: "实时检测代码漏洞与安全风险"
  },
  {
    title: "多平台集成支持",
    description: "GitHub、GitLab、Bitbucket 无缝接入"
  },
  {
    title: "智能审查报告",
    description: "深度分析，精准定位问题根因"
  }
];

const loginForm = reactive({
  account: "",
  password: "",
  remember: false
});

const registerForm = reactive({
  username: "",
  email: "",
  password: "",
  confirmPassword: ""
});

const switchMode = (mode: AuthMode) => {
  authMode.value = mode;
};

const handleForgotPassword = () => {
  ElMessage.info("密码找回功能将在账号体系接入后开放");
};

const handleLogin = () => {
  ElMessage.info("登录接口尚未接入，当前仅完成登录页交互");
  void router.push("/repoguard/overview");
};

const handleRegister = () => {
  if (registerForm.password && registerForm.confirmPassword && registerForm.password !== registerForm.confirmPassword) {
    ElMessage.warning("两次输入的密码不一致");
    return;
  }
  ElMessage.info("注册接口尚未接入，当前仅完成注册表单交互");
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

.login-brand-panel {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  padding: 66px 70px;
  color: #ffffff;
  background:
    radial-gradient(circle at 10% 42%, rgba(37, 99, 235, 0.14), transparent 22%),
    radial-gradient(circle at 82% 61%, rgba(59, 130, 246, 0.11), transparent 18%),
    #142f5f;
}

.login-brand-panel::before {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image: radial-gradient(rgba(255, 255, 255, 0.08) 1px, transparent 1px);
  background-size: 58px 58px;
  content: "";
}

.login-brand,
.login-brand-content {
  position: relative;
  z-index: 1;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  color: #eef6ff;
  font-size: 22px;
  font-weight: 800;
}

.login-brand-icon {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(96, 165, 250, 0.36);
  border-radius: 14px;
  color: #65a8ff;
  background: rgba(18, 104, 255, 0.16);
}

.login-brand-content {
  max-width: 500px;
  margin-top: auto;
  margin-bottom: auto;
}

.login-brand-content h1 {
  margin: 0;
  color: #ffffff;
  font-size: 42px;
  line-height: 1.26;
  font-weight: 800;
  letter-spacing: 0;
}

.login-brand-content p {
  max-width: 430px;
  margin: 28px 0 42px;
  color: rgba(219, 234, 254, 0.72);
  font-size: 17px;
  line-height: 1.8;
}

.login-feature-list {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.login-feature {
  display: grid;
  grid-template-columns: 28px 1fr;
  gap: 16px;
  align-items: flex-start;
}

.login-feature-dot {
  position: relative;
  width: 24px;
  height: 24px;
  margin-top: 2px;
  border: 1px solid rgba(96, 165, 250, 0.45);
  border-radius: 999px;
  background: rgba(18, 104, 255, 0.25);
}

.login-feature-dot::after {
  position: absolute;
  top: 7px;
  left: 7px;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #60a5fa;
  content: "";
}

.login-feature strong,
.login-feature span {
  display: block;
}

.login-feature strong {
  color: #eaf2ff;
  font-size: 16px;
  line-height: 1.4;
}

.login-feature span {
  margin-top: 4px;
  color: rgba(191, 219, 254, 0.48);
  font-size: 14px;
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

  .login-brand-panel {
    min-height: auto;
    padding: 34px 32px;
  }

  .login-brand-content {
    margin-top: 54px;
    margin-bottom: 0;
  }

  .login-brand-content h1 {
    font-size: 36px;
  }

  .login-brand-content p {
    margin: 18px 0 28px;
    font-size: 17px;
  }

  .login-feature-list {
    display: none;
  }

  .login-form-panel {
    min-height: auto;
    padding: 44px 24px;
  }
}

@media (max-width: 560px) {
  .login-brand-panel {
    padding: 24px 20px;
  }

  .login-brand {
    font-size: 20px;
  }

  .login-brand-icon {
    width: 46px;
    height: 46px;
  }

  .login-brand-content h1 {
    font-size: 32px;
  }

  .login-form-panel {
    padding: 34px 18px;
  }

  .login-heading h2 {
    font-size: 28px;
  }

  .login-options {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
  }
}
</style>
