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
      <IntegrationCard
        v-for="item in integrations"
        :key="item.id"
        :item="item"
        :icon="serviceIcons[item.id] ?? Hexagon"
        :form-state="formState[item.id]"
        :visible-secrets="visibleSecrets"
        @test-connection="testConnection"
      />
    </section>
  </div>
</template>

<script setup lang="ts">
import { reactive } from "vue";
import { ElMessage } from "element-plus";
import { Database, Github, Hexagon, RadioTower, Save } from "lucide-vue-next";
import type { Component } from "vue";
import IntegrationCard from "@/components/IntegrationCard.vue";
import { integrations } from "@/mocks/integrations";

const serviceIcons: Record<string, Component> = {
  github: Github,
  mysql: Database,
  rabbitmq: RadioTower,
  "spring-ai": Hexagon
};

const formState = reactive<Record<string, Record<string, string>>>(
  Object.fromEntries(integrations.map((item) => [item.id, Object.fromEntries(item.fields.map((field) => [field.label, field.value]))]))
);

const visibleSecrets = reactive<Record<string, boolean>>({});

const testConnection = (name: string) => {
  ElMessage.success(`${name} 测试连接已触发`);
};

const saveConfig = () => {
  ElMessage.success("配置已保存");
};
</script>
