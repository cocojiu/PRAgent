import type { ApiEndpointMap } from "@/api/endpoint";
import { createApiRequester } from "@/api/request";
import { reviewEndpoints, type ReviewApiContract } from "@/api/contracts/review";
import { configurationEndpoints, type ConfigurationApiContract } from "@/api/contracts/configuration";
import { modelQualityEndpoints, type ModelQualityApiContract } from "@/api/contracts/modelQuality";
import { identityEndpoints, type IdentityApiContract } from "@/api/contracts/identity";
import { notificationEndpoints, type NotificationApiContract } from "@/api/contracts/notification";
import { operationsEndpoints, type OperationsApiContract } from "@/api/contracts/operations";

export type { ApiRequestOptions } from "@/api/request";

export type ApiContract = ReviewApiContract
  & ConfigurationApiContract
  & ModelQualityApiContract
  & IdentityApiContract
  & NotificationApiContract
  & OperationsApiContract;

const apiEndpoints: ApiEndpointMap<ApiContract> = {
  ...reviewEndpoints,
  ...configurationEndpoints,
  ...modelQualityEndpoints,
  ...identityEndpoints,
  ...notificationEndpoints,
  ...operationsEndpoints
};

export const apiRequest = createApiRequester<ApiContract>(apiEndpoints);
