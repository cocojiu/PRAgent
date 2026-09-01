import { readFile, mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const frontendDirectory = resolve(scriptDirectory, "..");
const openApiPath = resolve(
  frontendDirectory,
  "../repoguard-backend/src/test/resources/contracts/openapi.json"
);
const typeAliasesPath = resolve(
  frontendDirectory,
  "../repoguard-backend/src/test/resources/contracts/typescript-type-aliases.json"
);
const outputPath = resolve(
  frontendDirectory,
  "src/api/generated/openapiOperations.ts"
);
const reviewDetailTypesPath = resolve(
  frontendDirectory,
  "src/api/generated/reviewDetailTypes.ts"
);
const checkOnly = process.argv.includes("--check");
const migratedOperationPrefixes = [
  "cacheStatsController",
  "dataRetentionController",
  "dashboardController",
  "frontendPerformanceController",
  "messageQueueHealthController",
  "reviewCalibrationController",
  "reviewController",
  "reviewExecutionAttemptController",
  "notificationController",
  "notificationIntegrationController",
  "systemConfigController",
  "systemHealthController",
  "userManagementController"
];
const httpMethods = ["get", "post", "put", "delete"];
const reviewDetailSchemaAliases = new Map([
  ["ChangedFileDto", "ChangedFile"],
  ["ChunkedReviewDto", "ChunkedReview"],
  ["FindingFeedbackRequest", "FindingFeedbackRequest"],
  ["FindingFeedbackResponse", "FindingFeedbackResponse"],
  ["FindingSeverityCountsDto", "FindingSeverityCounts"],
  ["HumanReviewRequest", "HumanReviewRequest"],
  ["HumanReviewResponse", "HumanReviewResponse"],
  ["LlmStatusDto", "LlmStatus"],
  ["MissingTestDto", "MissingTest"],
  ["PrReviewSummaryDto", "PrReviewSummary"],
  ["PrRiskFileDto", "PrRiskFile"],
  ["PrRiskProfileDto", "PrRiskProfile"],
  ["RabbitMqStatusDto", "RabbitMqStatus"],
  ["ReviewFindingDto", "ReviewFinding"],
  ["ReviewFindingTraceDto", "ReviewFindingTrace"],
  ["ReviewRetryResponse", "ReviewRetryResponse"],
  ["ReviewTaskStatusResponse", "ReviewTaskStatus"],
  ["ReviewTaskSummary", "ReviewTaskSummary"],
  ["ReviewTimelineItem", "TimelineItem"]
]);
const reviewDetailGeneratedTypeNames = new Set(reviewDetailSchemaAliases.values());

const javaTypeAliases = new Map(
  Object.entries(JSON.parse(await readFile(typeAliasesPath, "utf8")))
);

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
const reviewDetailTypesSource = renderReviewDetailTypes(document);
if (checkOnly) {
  const current = await readFile(outputPath, "utf8").catch(() => "");
  const currentReviewDetailTypes = await readFile(reviewDetailTypesPath, "utf8").catch(() => "");
  let stale = false;
  if (normalizeNewlines(current) !== normalizeNewlines(source)) {
    console.error(
      "Generated OpenAPI client metadata is stale. Run `npm run generate:api` in repoguard-frontend."
    );
    stale = true;
  }
  if (normalizeNewlines(currentReviewDetailTypes) !== normalizeNewlines(reviewDetailTypesSource)) {
    console.error(
      "Generated review detail DTO types are stale. Run `npm run generate:api` in repoguard-frontend."
    );
    stale = true;
  }
  if (stale) {
    process.exitCode = 1;
  }
} else {
  await mkdir(dirname(outputPath), { recursive: true });
  await writeFile(outputPath, source, "utf8");
  await writeFile(reviewDetailTypesPath, reviewDetailTypesSource, "utf8");
  console.log(`Generated ${operations.length} OpenAPI operations at ${outputPath}`);
  console.log(`Generated review detail DTO types at ${reviewDetailTypesPath}`);
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
  const bodyType = typescriptDataType(javaType);
  return operation.requestBody.required ? bodyType : `${bodyType} | undefined`;
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
  const generatedTypes = importedTypes.filter(type => reviewDetailGeneratedTypeNames.has(type));
  const legacyTypes = importedTypes.filter(type => !reviewDetailGeneratedTypeNames.has(type));
  const imports = [
    renderTypeImport(legacyTypes, "@/types"),
    renderTypeImport(generatedTypes, "@/api/generated/reviewDetailTypes")
  ].filter(Boolean).join("\n\n");
  const importBlock = imports.length > 0 ? `${imports}\n\n` : "";
  const operationTypes = generatedOperations
    .map(operation => renderOperationType(operation))
    .join("\n");
  const operationMetadata = generatedOperations
    .map(operation => renderOperationMetadata(operation))
    .join("\n");

  return `// Generated by scripts/generate-openapi-client.mjs from the reviewed backend OpenAPI contract.\n`
    + `// Do not edit this file manually.\n\n`
    + importBlock
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

function renderTypeImport(types, modulePath) {
  if (types.length === 0) {
    return "";
  }
  return `import type {\n${types.map(type => `  ${type},`).join("\n")}\n} from "${modulePath}";`;
}

function renderReviewDetailTypes(document) {
  const schemas = document.components?.schemas ?? {};
  const declarations = [...reviewDetailSchemaAliases.entries()]
    .map(([schemaName, typeName]) => renderReviewDetailType(schemaName, typeName, schemas[schemaName]))
    .join("\n\n");
  return `// Generated by scripts/generate-openapi-client.mjs from the reviewed backend OpenAPI contract.\n`
    + `// Do not edit this file manually.\n\n`
    + `${declarations}\n`;
}

function renderReviewDetailType(schemaName, typeName, schema) {
  if (schemaName === "FindingSeverityCountsDto" && !schema) {
    return `export type ${typeName} = {\n`
      + `  critical?: number;\n`
      + `  high?: number;\n`
      + `  medium?: number;\n`
      + `  low?: number;\n`
      + `  info?: number;\n`
      + `};`;
  }
  if (!schema || schema.type !== "object") {
    throw new Error(`Review detail schema ${schemaName} must be an object`);
  }
  const required = new Set(schema.required ?? []);
  const properties = Object.entries(schema.properties ?? {})
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([propertyName, propertySchema]) => {
      const optional = required.has(propertyName) ? "" : "?";
      return `  ${renderPropertyName(propertyName)}${optional}: ${schemaType(propertySchema)};`;
    })
    .join("\n");
  return `export type ${typeName} = {\n${properties}\n};`;
}

function renderPropertyName(propertyName) {
  return /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(propertyName) ? propertyName : JSON.stringify(propertyName);
}

function schemaType(schema) {
  if (!schema || typeof schema !== "object") {
    return "unknown";
  }
  if (schema.$ref) {
    const schemaName = schema.$ref.split("/").pop();
    return reviewDetailSchemaAliases.get(schemaName)
      ?? (schemaName.endsWith("Dto") ? schemaName.slice(0, -"Dto".length) : schemaName);
  }
  if (typeof schema["x-java-type"] === "string") {
    const javaType = schema["x-java-type"];
    return reviewDetailSchemaAliases.get(javaType)
      ?? (javaType.endsWith("Dto") ? javaType.slice(0, -"Dto".length) : javaType);
  }
  if (schema.type === "array") {
    return `${schemaType(schema.items)}[]`;
  }
  if (schema.type === "integer" || schema.type === "number") {
    return "number";
  }
  if (schema.type === "boolean") {
    return "boolean";
  }
  if (schema.type === "string") {
    return stringSchemaType(schema);
  }
  if (schema.type === "object") {
    return "Record<string, unknown>";
  }
  return "unknown";
}

function stringSchemaType(schema) {
  if (Array.isArray(schema.enum) && schema.enum.length > 0) {
    return schema.enum.map(value => JSON.stringify(value)).join(" | ");
  }
  const pattern = typeof schema.pattern === "string" ? schema.pattern : "";
  const match = pattern.match(/^\(\?i\)\^?([A-Za-z0-9_]+(?:\|[A-Za-z0-9_]+)*)\$?$/);
  if (match) {
    return match[1].split("|").map(value => JSON.stringify(value)).join(" | ");
  }
  return "string";
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
