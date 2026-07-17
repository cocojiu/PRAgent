import type { Component } from "vue";
import { Database, Hexagon, RadioTower } from "@lucide/vue";
import Github from "@/components/icons/GithubIcon.vue";
import type { IntegrationConfig } from "@/types";

export type IntegrationId = "github" | "mysql" | "rabbitmq" | "spring-ai";

export const serviceIcons: Record<string, Component> = {
  github: Github,
  mysql: Database,
  rabbitmq: RadioTower,
  "spring-ai": Hexagon
};

export const providerMap: Record<string, string> = {
  dashscope: "DashScope",
  openai: "OpenAI Compatible",
  mock: "Mock"
};

export const defaultIntegrationItems: IntegrationConfig[] = [
  {
    id: "github",
    name: "GitHub",
    description: "用于读取 Pull Request 信息与回写审查评论",
    status: "missing_secret",
    statusText: "未配置",
    metaLabel: "更新时间",
    metaValue: "未更新",
    message: "请配置 GitHub Token",
    fields: [
      { label: "API Base URL", value: "https://api.github.com", type: "text" },
      { label: "Token", value: "", type: "password", placeholder: "GitHub token" },
      { label: "Default Owner", value: "", type: "text" },
      { label: "Default Repo", value: "", type: "text" }
    ]
  },
  {
    id: "mysql",
    name: "MySQL",
    description: "用于检测数据库连接；当前运行数据源仍由后端启动配置决定",
    status: "missing_secret",
    statusText: "未配置",
    metaLabel: "更新时间",
    metaValue: "未更新",
    message: "请配置用于检测的 MySQL 连接信息",
    fields: [
      { label: "JDBC URL", value: "", type: "text", placeholder: "jdbc:mysql://localhost:3306/repoguard" },
      { label: "Username", value: "", type: "text" },
      { label: "Password", value: "", type: "password", placeholder: "MySQL password" },
      { label: "Database", value: "", type: "text" }
    ]
  },
  {
    id: "rabbitmq",
    name: "RabbitMQ",
    description: "用于检测消息队列连接；当前运行队列仍由后端启动配置决定",
    status: "missing_secret",
    statusText: "未配置",
    metaLabel: "更新时间",
    metaValue: "未更新",
    message: "请配置用于检测的 RabbitMQ 连接信息",
    fields: [
      { label: "AMQP URL", value: "", type: "text", placeholder: "amqp://localhost:5672" },
      { label: "Username", value: "", type: "text" },
      { label: "Password", value: "", type: "password", placeholder: "RabbitMQ password" },
      { label: "Virtual Host", value: "/", type: "text" }
    ]
  },
  {
    id: "spring-ai",
    name: "Spring AI Alibaba",
    description: "用于 AI 代码审查和智能分析能力",
    status: "missing_secret",
    statusText: "缺少 API Key",
    metaLabel: "模型名称",
    metaValue: "qwen-plus",
    message: "请配置 LLM API Key",
    fields: [
      { label: "Provider", value: "DashScope", type: "select", options: ["DashScope", "OpenAI Compatible", "Mock"] },
      { label: "API Key", value: "", type: "password", placeholder: "LLM API key" },
      { label: "Model", value: "qwen-plus", type: "text" },
      { label: "Base URL", value: "https://dashscope.aliyuncs.com/compatible-mode/v1", type: "text" },
      { label: "Chunk File Threshold", value: "6", type: "text" },
      { label: "Chunk Line Threshold", value: "700", type: "text" },
      { label: "Chunk Max Files", value: "4", type: "text" },
      { label: "Chunk Max Lines", value: "450", type: "text" },
      { label: "Input $/1M Tokens", value: "0", type: "text" },
      { label: "Output $/1M Tokens", value: "0", type: "text" }
    ]
  }
];

export const cloneIntegrationItems = () =>
  defaultIntegrationItems.map((item) => ({ ...item, fields: item.fields.map((field) => ({ ...field })) }));
