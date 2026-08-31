import type {
  ConnectionTestResult,
  GithubIntegrationConfig,
  IntegrationConfig,
  IntegrationDiagnosticItem,
  IntegrationField,
  ReviewPolicyConfig,
  SecretStatus,
  ServiceIntegrationConfig
} from "@/types";
import { formatDateTime } from "@/utils/dateTime";
import { providerMap } from "./integrationDefaults";

type IntegrationPatch = Pick<IntegrationConfig, "fields" | "message" | "metaLabel" | "metaValue" | "status" | "statusText"> & {
  diagnostics?: IntegrationDiagnosticItem[];
};

export const buildGithubIntegrationPatch = (config: GithubIntegrationConfig): IntegrationPatch => ({
  status: secretIntegrationStatus(config.secretStatus, config.status),
  statusText: secretIntegrationStatusText(config.secretStatus, "Token", config.status),
  metaLabel: "更新时间",
  metaValue: config.updatedAt ? formatDateTime(config.updatedAt) : "未更新",
  message: config.lastError ?? secretIntegrationMessage(config.secretStatus, "GitHub Token", "GitHub 配置已保存", config.status),
  diagnostics: secretDiagnostics(config.secretStatus, config.status),
  fields: [
    { label: "API Base URL", value: config.baseUrl, type: "text" },
    { label: "Token", value: config.token ?? "", type: "password", placeholder: "GitHub token" },
    { label: "Default Owner", value: config.defaultOwner ?? "", type: "text" },
    { label: "Default Repo", value: config.defaultRepo ?? "", type: "text" }
  ]
});

export const buildServiceIntegrationPatch = (id: "mysql" | "rabbitmq", config: ServiceIntegrationConfig): IntegrationPatch => {
  const isConfigured = config.status === "configured";
  const isFailed = config.status === "failed";
  const secretBroken = isSecretBroken(config.secretStatus);
  const serviceName = id === "mysql" ? "MySQL" : "RabbitMQ";
  return {
    status: secretBroken ? "failed" : isConfigured ? "connected" : isFailed ? "failed" : "missing_secret",
    statusText: secretBroken ? "密文异常" : isConfigured ? "已连接" : isFailed ? "连接失败" : "未配置",
    metaLabel: config.lastCheckedAt ? "检测时间" : "更新时间",
    metaValue: config.lastCheckedAt || config.updatedAt
      ? formatDateTime(config.lastCheckedAt ?? config.updatedAt)
      : "未更新",
    message: config.lastError ?? (secretBroken
      ? `${serviceName} 保存的密文不可解密，请重新填写密钥或执行密钥轮换修复`
      : isConfigured
      ? `${serviceName} 检测配置已保存，不会切换当前运行连接`
      : `可选：填写用于检测的 ${serviceName} 配置，不会切换当前运行连接`),
    diagnostics: [
      ...secretDiagnostics(config.secretStatus, config.status),
      {
        label: "保存配置",
        value: serviceConfigStatusText(config.status),
        status: isConfigured ? "success" : isFailed ? "danger" : "warning"
      }
    ],
    fields: serviceFields(id, config)
  };
};

const serviceFields = (id: "mysql" | "rabbitmq", config: ServiceIntegrationConfig): IntegrationField[] => {
  if (id === "mysql") {
    return [
      { label: "JDBC URL", value: config.baseUrl ?? "", type: "text", placeholder: "jdbc:mysql://localhost:3306/repoguard" },
      { label: "Username", value: config.username ?? "", type: "text" },
      { label: "Password", value: config.secret ?? "", type: "password", placeholder: "MySQL password" },
      { label: "Database", value: config.resource ?? "", type: "text" }
    ];
  }
  return [
    { label: "AMQP URL", value: config.baseUrl ?? "", type: "text", placeholder: "amqp://localhost:5672" },
    { label: "Username", value: config.username ?? "", type: "text" },
    { label: "Password", value: config.secret ?? "", type: "password", placeholder: "RabbitMQ password" },
    { label: "Virtual Host", value: config.resource ?? "/", type: "text" }
  ];
};

export const buildReviewPolicyIntegrationPatch = (config: ReviewPolicyConfig): IntegrationPatch => ({
  status: secretIntegrationStatus(config.secretStatus, config.apiKey ? "configured" : "not_configured"),
  statusText: secretIntegrationStatusText(config.secretStatus, "API Key", config.apiKey ? "configured" : "not_configured"),
  metaLabel: "模型名称",
  metaValue: config.modelName,
  message: secretIntegrationMessage(
    config.secretStatus,
    "LLM API Key",
    "LLM 配置已保存",
    config.apiKey ? "configured" : "not_configured"
  ),
  diagnostics: secretDiagnostics(config.secretStatus, config.apiKey ? "configured" : "not_configured"),
  fields: [
    {
      label: "Provider",
      value: providerMap[config.llmProvider] ?? config.llmProvider,
      type: "select",
      options: Object.values(providerMap)
    },
    { label: "API Key", value: config.apiKey ?? "", type: "password", placeholder: "LLM API key" },
    { label: "Model", value: config.modelName, type: "text" },
    { label: "Base URL", value: config.baseUrl ?? "", type: "text" },
    { label: "Chunk File Threshold", value: String(config.chunkFileThreshold ?? 6), type: "text" },
    { label: "Chunk Line Threshold", value: String(config.chunkLineThreshold ?? 700), type: "text" },
    { label: "Chunk Max Files", value: String(config.chunkMaxFiles ?? 4), type: "text" },
    { label: "Chunk Max Lines", value: String(config.chunkMaxLines ?? 450), type: "text" },
    { label: "Input $/1M Tokens", value: String(config.inputTokenPricePerMillion ?? 0), type: "text" },
    { label: "Output $/1M Tokens", value: String(config.outputTokenPricePerMillion ?? 0), type: "text" }
  ]
});

export const buildConnectionTestPatch = (id: string, result: ConnectionTestResult): Partial<IntegrationPatch> => ({
  status: result.success ? "connected" : "failed",
  statusText: result.success ? "已连接" : "连接失败",
  message: result.message,
  metaLabel: "检测时间",
  metaValue: formatDateTime(result.checkedAt),
  ...(id === "mysql" || id === "rabbitmq" ? { diagnostics: serviceDiagnostics(result) } : {})
});

const serviceDiagnostics = (result: ConnectionTestResult): IntegrationDiagnosticItem[] => [
  {
    label: "检测来源",
    value: testedConfigSourceText(result.testedConfigSource),
    status: "info"
  },
  {
    label: "运行时",
    value: healthText(result.runtimeHealthy, result.runtimeConnectionStatus),
    status: healthStatus(result.runtimeHealthy)
  },
  {
    label: "保存配置",
    value: healthText(result.savedConfigHealthy, result.savedConfigStatus),
    status: healthStatus(result.savedConfigHealthy)
  },
  {
    label: "一致性",
    value: result.mismatch == null ? "未比较" : result.mismatch ? "不一致" : "一致",
    status: result.mismatch == null ? "info" : result.mismatch ? "warning" : "success"
  }
];

const testedConfigSourceText = (source?: string) => {
  switch (source) {
    case "submitted_config":
      return "当前表单";
    case "saved_config":
      return "保存配置";
    case "runtime_config":
      return "运行时配置";
    default:
      return "未标记";
  }
};

const healthText = (healthy?: boolean | null, status?: string | null) => {
  if (healthy == null) {
    return status === "not_configured" ? "未配置" : "不可用";
  }
  return healthy ? "健康" : "异常";
};

const healthStatus = (healthy?: boolean | null): IntegrationDiagnosticItem["status"] => {
  if (healthy == null) {
    return "info";
  }
  return healthy ? "success" : "danger";
};

const serviceConfigStatusText = (status: ServiceIntegrationConfig["status"]) => {
  switch (status) {
    case "configured":
      return "健康";
    case "failed":
      return "异常";
    default:
      return "未配置";
  }
};

const secretDiagnostics = (secretStatus: SecretStatus | undefined, configStatus: string): IntegrationDiagnosticItem[] => {
  const normalizedStatus = normalizedSecretStatus(secretStatus, configStatus);
  return [{
    label: "密钥状态",
    value: secretDiagnosticText(normalizedStatus),
    status: secretDiagnosticStatus(normalizedStatus)
  }];
};

const secretDiagnosticText = (status: SecretStatus) => {
  switch (status) {
    case "configured":
      return "已配置";
    case "key_mismatch":
      return "密钥不匹配";
    case "decrypt_failed":
      return "密文不可解密";
    default:
      return "未配置";
  }
};

const secretDiagnosticStatus = (status: SecretStatus): IntegrationDiagnosticItem["status"] => {
  if (status === "configured") {
    return "success";
  }
  return isSecretBroken(status) ? "danger" : "warning";
};

const isSecretBroken = (status?: SecretStatus) => status === "key_mismatch" || status === "decrypt_failed";

const secretIntegrationStatus = (secretStatus: SecretStatus | undefined, configStatus: string): IntegrationConfig["status"] => {
  const normalizedStatus = normalizedSecretStatus(secretStatus, configStatus);
  if (isSecretBroken(normalizedStatus)) {
    return "failed";
  }
  if (normalizedStatus === "configured" || configStatus === "configured") {
    return "connected";
  }
  if (configStatus === "failed") {
    return "failed";
  }
  return "missing_secret";
};

const secretIntegrationStatusText = (
  secretStatus: SecretStatus | undefined,
  secretName: string,
  configStatus: string
) => {
  const normalizedStatus = normalizedSecretStatus(secretStatus, configStatus);
  if (normalizedStatus === "key_mismatch") {
    return "密钥不匹配";
  }
  if (normalizedStatus === "decrypt_failed") {
    return "密文异常";
  }
  if (normalizedStatus === "configured" || configStatus === "configured") {
    return "已连接";
  }
  return `缺少 ${secretName}`;
};

const secretIntegrationMessage = (
  secretStatus: SecretStatus | undefined,
  secretName: string,
  configuredMessage: string,
  configStatus: string
) => {
  const normalizedStatus = normalizedSecretStatus(secretStatus, configStatus);
  if (normalizedStatus === "key_mismatch") {
    return `${secretName} 的 key id 与当前加密密钥不匹配，请重新填写或执行密钥轮换修复`;
  }
  if (normalizedStatus === "decrypt_failed") {
    return `${secretName} 密文不可解密，请重新填写或执行重加密预检`;
  }
  return normalizedStatus === "configured" ? configuredMessage : `请配置 ${secretName}`;
};

const normalizedSecretStatus = (secretStatus: SecretStatus | undefined, configStatus: string): SecretStatus => {
  if (secretStatus) {
    return secretStatus;
  }
  return configStatus === "configured" ? "configured" : "missing";
};
