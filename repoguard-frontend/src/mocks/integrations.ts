import type { IntegrationConfig } from "@/types";

export const integrations: IntegrationConfig[] = [
  {
    id: "github",
    name: "GitHub Webhook",
    description: "用于接收 GitHub 仓库的 Webhook 事件通知",
    status: "connected",
    statusText: "已连接",
    metaLabel: "最后检查时间",
    metaValue: "2025-05-31 15:30:24",
    message: "Webhook 连接正常，最近一次事件接收时间：2025-05-31 15:28:11",
    fields: [
      { label: "Webhook 地址", value: "https://repoguard.example.com/api/webhook/github", type: "text" },
      { label: "Secret Token", value: "********", type: "password" }
    ]
  },
  {
    id: "mysql",
    name: "MySQL",
    description: "用于存储系统数据和审查结果等信息",
    status: "connected",
    statusText: "已连接",
    metaLabel: "数据库版本",
    metaValue: "8.0.36",
    message: "数据库连接正常，连接池状态良好",
    fields: [
      { label: "数据库地址", value: "mysql://127.0.0.1:3306/repoguard", type: "text" },
      { label: "用户名", value: "repoguard_user", type: "text" },
      { label: "密码", value: "********", type: "password" }
    ]
  },
  {
    id: "rabbitmq",
    name: "RabbitMQ",
    description: "用于异步任务处理和消息队列通信",
    status: "connected",
    statusText: "已连接",
    metaLabel: "RabbitMQ 版本",
    metaValue: "3.13.3",
    message: "RabbitMQ 连接正常，队列和交换机状态正常",
    fields: [
      { label: "连接地址", value: "amqp://127.0.0.1:5672", type: "text" },
      { label: "用户名", value: "repoguard", type: "text" },
      { label: "密码", value: "********", type: "password" }
    ]
  },
  {
    id: "spring-ai",
    name: "Spring AI Alibaba",
    description: "用于 AI 代码审查和智能分析能力",
    status: "missing_secret",
    statusText: "缺少密钥",
    metaLabel: "最后检查时间",
    metaValue: "未检测",
    message: "未配置 API Key，无法连接到 AI 服务，请前往系统设置中配置或在此处填写后保存。",
    fields: [
      { label: "服务提供商", value: "阿里云百炼 (DashScope)", type: "select", options: ["阿里云百炼 (DashScope)", "OpenAI Compatible"] },
      { label: "API Key", value: "", type: "password", placeholder: "请输入 API Key" },
      { label: "模型名称", value: "qwen-plus", type: "text" }
    ]
  }
];
