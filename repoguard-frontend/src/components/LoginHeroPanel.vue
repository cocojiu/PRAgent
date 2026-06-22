<template>
  <section class="login-brand-panel" aria-label="RepoGuard Agent">
    <header class="login-brand">
      <span class="login-brand-icon">
        <Shield :size="24" />
      </span>
      <strong>RepoGuard Agent</strong>
    </header>

    <div class="login-brand-content">
      <h1>智能代码安全审查平台</h1>
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
</template>

<script setup lang="ts">
import { Shield } from "lucide-vue-next";

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
</script>

<style scoped>
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
  font-size: 40px;
  line-height: 1.26;
  font-weight: 800;
  letter-spacing: 0;
  white-space: nowrap;
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

@media (max-width: 980px) {
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
    white-space: normal;
  }

  .login-brand-content p {
    margin: 18px 0 28px;
    font-size: 17px;
  }

  .login-feature-list {
    display: none;
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
}
</style>
