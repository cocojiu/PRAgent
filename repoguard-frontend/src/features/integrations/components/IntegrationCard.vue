<template>
  <article class="integration-card">
    <div class="integration-service">
      <div class="service-logo" :class="`service-logo--${item.id}`">
        <component :is="icon" :size="58" />
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
        <el-select v-if="field.type === 'select'" v-model="formState[field.label]">
          <el-option v-for="option in field.options" :key="option" :label="option" :value="option" />
        </el-select>
        <el-input
          v-else
          v-model="formState[field.label]"
          :type="field.type === 'password' && !visibleSecrets[secretKey(field.label)] ? 'password' : 'text'"
          :placeholder="field.placeholder"
        >
          <template #suffix>
            <Eye
              v-if="field.type === 'password'"
              :size="18"
              class="suffix-icon"
              @click="toggleSecret(field.label)"
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
      <div v-if="item.diagnostics?.length" class="integration-diagnostics">
        <div
          v-for="diagnostic in item.diagnostics"
          :key="diagnostic.label"
          :class="['integration-diagnostic', diagnostic.status ?? 'info']"
        >
          <span>{{ diagnostic.label }}</span>
          <strong>{{ diagnostic.value }}</strong>
        </div>
      </div>
      <div class="integration-actions">
        <el-button
          type="primary"
          :disabled="!canManage"
          :loading="saving"
          @click="$emit('save-config', item.id)"
        >
          <Save :size="17" />
          保存本项
        </el-button>
        <el-button
          type="primary"
          plain
          :disabled="!canManage"
          :loading="testing"
          @click="$emit('test-connection', item.id)"
        >
          <RadioTower :size="17" />
          测试连接
        </el-button>
      </div>
    </aside>
  </article>
</template>

<script setup lang="ts">
import { ChevronDown, CircleCheck, Copy, Eye, RadioTower, Save, TriangleAlert } from "@lucide/vue";
import type { Component } from "vue";
import type { IntegrationConfig } from "@/types";

const props = defineProps<{
  item: IntegrationConfig;
  icon: Component;
  formState: Record<string, string>;
  visibleSecrets: Record<string, boolean>;
  canManage?: boolean;
  saving?: boolean;
  testing?: boolean;
}>();

defineEmits<{
  (event: "save-config", id: string): void;
  (event: "test-connection", id: string): void;
}>();

const secretKey = (label: string) => `${props.item.id}-${label}`;

const toggleSecret = (label: string) => {
  const key = secretKey(label);
  props.visibleSecrets[key] = !props.visibleSecrets[key];
};
</script>
