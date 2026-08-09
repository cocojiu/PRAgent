import { readFile, mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const frontendDirectory = resolve(scriptDirectory, "..");
const openApiPath = resolve(
  frontendDirectory,
  "../repoguard-backend/src/test/resources/contracts/openapi.json"
);
const outputPath = resolve(
  frontendDirectory,
  "src/api/generated/openapiOperations.ts"
);
const checkOnly = process.argv.includes("--check");
const migratedOperationPrefixes = [
  "dashboardController",
  "reviewController",
  "notificationController",
  "notificationIntegrationController"
];
const httpMethods = ["get", "post", "put", "delete"];

const javaTypeAliases = new Map([
  ["AuthCurrentUserDto", "CurrentUser"],
  ["CacheStatsResponse", "CacheStats"],
  ["ChartSliceDto", "Required<ChartSlice>"],
  ["ConnectionTestResultDto", "ConnectionTestResult"],
  ["DashboardLlmQualityResponse", "DashboardLlmQuality"],
  ["DashboardOverviewResponse", "DashboardOverview"],
  ["DashboardRulesResponse", "DashboardRules"],
  ["GithubCommentPreviewResponse", "GithubCommentPreview"],
  ["GithubCommentPublicationHistoryResponse", "GithubCommentPublicationHistory"],
  ["GithubCommentPublishResponse", "GithubCommentPublish"],
  ["GithubPullRequestOptionsResponse", "GithubPullRequestOptions"],
  ["MessageQueueHealthResponse", "MessageQueueHealth"],
  ["NotificationCenterDto", "NotificationCenter"],
  ["ReviewTaskListItem", "ReviewTask"],
  ["ReviewTaskStatusResponse", "ReviewTaskStatus"],
  ["ReviewTimelineItem", "TimelineItem"],
  ["ServiceIntegrationConfigDto", "ServiceIntegrationConfig"],
  ["SystemSettingsDto", "SystemSettings"],
  ["UserManagementItemDto", "ManagedUser"],
  ["UserOperationAuditDto", "UserOperationAudit"]
]);

const document = JSON.parse(await readFile(openApiPath, "utf8"));
const operations = [];

for (const [path, pathItem] of Object.entries(document.paths ?? {})) {
  for (const method of httpMethods) {
    const operation = pathItem?.[method];
    if (!operation?.operationId || !isMigratedOperation(operation.operationId)) {
      continue;
    }
    operations.push({
      operationId: operation.operationId,
      method: method.toUpperCase(),
      path,
      pathParameters: parameters(operation, "path"),
      queryParameters: parameters(operation, "query"),
      requestBodyType: requestBodyType(operation),
      requestBodyRequired: Boolean(operation.requestBody?.required),
      responseType: responseType(operation)
    });
  }
}

operations.sort((left, right) => left.operationId.localeCompare(right.operationId));
if (operations.length === 0) {
  throw new Error(`No migrated OpenAPI operations found in ${openApiPath}`);
}

const source = renderSource(operations);
if (checkOnly) {
  const current = await readFile(outputPath, "utf8").catch(() => "");
  if (normalizeNewlines(current) !== normalizeNewlines(source)) {
    console.error(
      "Generated OpenAPI client metadata is stale. Run `npm run generate:api` in repoguard-frontend."
    );
    process.exitCode = 1;
  }
} else {
  await mkdir(dirname(outputPath), { recursive: true });
  await writeFile(outputPath, source, "utf8");
  console.log(`Generated ${operations.length} OpenAPI operations at ${outputPath}`);
}

function isMigratedOperation(operationId) {
  return migratedOperationPrefixes.some(prefix => operationId.startsWith(prefix));
}

function parameters(operation, location) {
  return (operation.parameters ?? [])
    .filter(parameter => parameter.in === location)
    .map(parameter => ({
      name: parameter.name,
      required: Boolean(parameter.required),
      type: scalarType(parameter.schema ?? {})
    }))
    .sort((left, right) => left.name.localeCompare(right.name));
}

function requestBodyType(operation) {
  if (!operation.requestBody) {
    return "never";
  }
  const javaType = operation.requestBody["x-java-type"];
  if (typeof javaType !== "string") {
    throw new Error(`Operation ${operation.operationId} request body is missing x-java-type`);
  }
  return typescriptDataType(javaType);
}

function responseType(operation) {
  const javaType = operation.responses?.["200"]?.["x-java-response-data"];
  if (typeof javaType !== "string") {
    throw new Error(`Operation ${operation.operationId} response is missing x-java-response-data`);
  }
  return typescriptDataType(javaType);
}

function scalarType(schema) {
  if (schema.type === "integer" || schema.type === "number") {
    return "number";
  }
  if (schema.type === "boolean") {
    return "boolean";
  }
  if (schema.type === "array") {
    return `${scalarType(schema.items ?? {})}[]`;
  }
  return "string";
}

function typescriptDataType(javaType) {
  const normalized = javaType.replaceAll(" ", "");
  if (normalized.endsWith("[]")) {
    return `${typescriptDataType(normalized.slice(0, -2))}[]`;
  }
  if (normalized.startsWith("List<") && normalized.endsWith(">")) {
    return `${typescriptDataType(genericContent(normalized))}[]`;
  }
  if (normalized.startsWith("PageResponse<") && normalized.endsWith(">")) {
    return `PageResponse<${typescriptDataType(genericContent(normalized))}>`;
  }
  if (normalized.startsWith("Required<") && normalized.endsWith(">")) {
    return typescriptDataType(genericContent(normalized));
  }
  if (normalized === "Void") {
    return "void";
  }
  if (normalized === "String") {
    return "string";
  }
  if (["Long", "Integer", "Double", "BigDecimal"].includes(normalized)) {
    return "number";
  }
  if (normalized === "Boolean") {
    return "boolean";
  }
  return javaTypeAliases.get(normalized)
    ?? (normalized.endsWith("Dto") ? normalized.slice(0, -"Dto".length) : normalized);
}

function genericContent(type) {
  return type.slice(type.indexOf("<") + 1, -1);
}

function renderSource(generatedOperations) {
  const importedTypes = collectImportedTypes(generatedOperations);
  const imports = importedTypes.length > 0
    ? `import type {\n${importedTypes.map(type => `  ${type},`).join("\n")}\n} from "@/types";\n\n`
    : "";
  const operationTypes = generatedOperations
    .map(operation => renderOperationType(operation))
    .join("\n");
  const operationMetadata = generatedOperations
    .map(operation => renderOperationMetadata(operation))
    .join("\n");

  return `// Generated by scripts/generate-openapi-client.mjs from the reviewed backend OpenAPI contract.\n`
    + `// Do not edit this file manually.\n\n`
    + imports
    + `export type GeneratedOpenApiOperationMap = {\n${operationTypes}\n};\n\n`
    + `export type GeneratedOpenApiOperationId = keyof GeneratedOpenApiOperationMap;\n`
    + `export type GeneratedOpenApiPathParams<Operation extends GeneratedOpenApiOperationId> =\n`
    + `  GeneratedOpenApiOperationMap[Operation]["pathParams"];\n`
    + `export type GeneratedOpenApiQuery<Operation extends GeneratedOpenApiOperationId> =\n`
    + `  GeneratedOpenApiOperationMap[Operation]["query"];\n`
    + `export type GeneratedOpenApiBody<Operation extends GeneratedOpenApiOperationId> =\n`
    + `  GeneratedOpenApiOperationMap[Operation]["body"];\n`
    + `export type GeneratedOpenApiResponse<Operation extends GeneratedOpenApiOperationId> =\n`
    + `  GeneratedOpenApiOperationMap[Operation]["response"];\n\n`
    + `type GeneratedOpenApiOperationMetadata = {\n`
    + `  readonly method: "GET" | "POST" | "PUT" | "DELETE";\n`
    + `  readonly path: string;\n`
    + `  readonly pathParamNames: readonly string[];\n`
    + `  readonly queryParamNames: readonly string[];\n`
    + `  readonly hasRequestBody: boolean;\n`
    + `  readonly requestBodyRequired: boolean;\n`
    + `};\n\n`
    + `export const generatedOpenApiOperations = {\n${operationMetadata}\n`
    + `} as const satisfies Record<GeneratedOpenApiOperationId, GeneratedOpenApiOperationMetadata>;\n`;
}

function renderOperationType(operation) {
  return `  ${JSON.stringify(operation.operationId)}: {\n`
    + `    method: ${JSON.stringify(operation.method)};\n`
    + `    pathParams: ${objectType(operation.pathParameters)};\n`
    + `    query: ${objectType(operation.queryParameters)};\n`
    + `    body: ${operation.requestBodyType};\n`
    + `    response: ${operation.responseType};\n`
    + `  };`;
}

function renderOperationMetadata(operation) {
  return `  ${JSON.stringify(operation.operationId)}: {\n`
    + `    method: ${JSON.stringify(operation.method)},\n`
    + `    path: ${JSON.stringify(operation.path)},\n`
    + `    pathParamNames: ${stringArray(operation.pathParameters.map(parameter => parameter.name))},\n`
    + `    queryParamNames: ${stringArray(operation.queryParameters.map(parameter => parameter.name))},\n`
    + `    hasRequestBody: ${operation.requestBodyType !== "never"},\n`
    + `    requestBodyRequired: ${operation.requestBodyRequired}\n`
    + `  },`;
}

function objectType(generatedParameters) {
  if (generatedParameters.length === 0) {
    return "never";
  }
  return `{ ${generatedParameters
    .map(parameter => `${parameter.name}${parameter.required ? "" : "?"}: ${parameter.type}`)
    .join("; ")} }`;
}

function stringArray(values) {
  return `[${values.map(value => JSON.stringify(value)).join(", ")}]`;
}

function collectImportedTypes(generatedOperations) {
  const typeExpressions = generatedOperations.flatMap(operation => [
    operation.requestBodyType,
    operation.responseType
  ]);
  const importedTypes = new Set();
  const builtInTypes = new Set(["Required"]);
  for (const expression of typeExpressions) {
    for (const match of expression.matchAll(/\b[A-Z][A-Za-z0-9]*\b/g)) {
      if (!builtInTypes.has(match[0])) {
        importedTypes.add(match[0]);
      }
    }
  }
  return [...importedTypes].sort();
}

function normalizeNewlines(value) {
  return value.replaceAll("\r\n", "\n");
}
