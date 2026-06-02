<template>
  <div class="integration-page">
    <div class="integration-header">
      <div>
        <h1>集成配置</h1>
      </div>
      <el-button type="primary" size="large" @click="saveConfig">
        <Save :size="17" />
        保存配置
      </el-button>
    </div>

    <el-alert
      title="配置系统所需的外部服务连接信息，确保所有服务均正常连接以保证系统稳定运行。"
      type="primary"
      :closable="false"
      show-icon
      class="integration-alert"
    />

    <section class="integration-list">
      <article v-for="item in integrations" :key="item.id" class="integration-card">
        <div class="integration-service">
          <div class="service-logo" :class="`service-logo--${item.id}`">
            <component :is="serviceIcons[item.id]" :size="58" />
          </div>
          <div>
            <h2>{{ item.name }}</h2>
            <span :class="`integration-status ${item.status}`">{{ item.statusText }}</span>
          </div>
          <p>{{ item.description }}</p>
        </div>

        <div class="integration-form">
          <div v-for="field in item.fields" :key="field.label" class="config-row">
            <label>{{ field.label }}</label>
            <el-select v-if="field.type === 'select'" v-model="formState[item.id][field.label]">
              <el-option v-for="option in field.options" :key="option" :label="option" :value="option" />
            </el-select>
            <el-input
              v-else
              v-model="formState[item.id][field.label]"
              :type="field.type === 'password' && !visibleSecrets[`${item.id}-${field.label}`] ? 'password' : 'text'"
              :placeholder="field.placeholder"
            >
              <template #suffix>
                <Eye
                  v-if="field.type === 'password'"
                  :size="18"
                  class="suffix-icon"
                  @click="toggleSecret(item.id, field.label)"
                />
                <Copy v-else :size="18" class="suffix-icon" />
              </template>
            </el-input>
          </div>
          <div :class="`connection-message ${item.status}`">
            <CircleCheck v-if="item.status === 'connected'" :size="18" />
            <TriangleAlert v-else :size="18" />
            {{ item.message }}
          </div>
        </div>

        <aside class="integration-side">
          <ChevronDown :size="20" class="side-caret" />
          <p>连接状态</p>
          <span :class="`integration-status ${item.status}`">{{ item.statusText }}</span>
          <p>{{ item.metaLabel }}</p>
          <strong>{{ item.metaValue }}</strong>
          <el-button type="primary" plain @click="testConnection(item.name)">
            <RadioTower :size="17" />
            测试连接
          </el-button>
        </aside>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { reactive } from "vue";
import { ElMessage } from "element-plus";
import { ChevronDown, CircleCheck, Copy, Database, Eye, Github, Hexagon, RadioTower, Save, TriangleAlert } from "lucide-vue-next";
import { integrations } from "@/mocks/integrations";

const serviceIcons: Record<string, unknown> = {
  github: Github,
  mysql: Database,
  rabbitmq: RadioTower,
  "spring-ai": Hexagon
};

const formState = reactive(
  Object.fromEntries(integrations.map((item) => [item.id, Object.fromEntries(item.fields.map((field) => [field.label, field.value]))]))
);

const visibleSecrets = reactive<Record<string, boolean>>({});

const toggleSecret = (id: string, label: string) => {
  const key = `${id}-${label}`;
  visibleSecrets[key] = !visibleSecrets[key];
};

const testConnection = (name: string) => {
  ElMessage.success(`${name} 测试连接已触发`);
};

const saveConfig = () => {
  ElMessage.success("配置已保存");
};
</script>
