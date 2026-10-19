import { apiRequest, type ApiRequestOptions } from "@/api/contracts";
import type { GithubPublicationCloseRequest, GithubPublicationVerifyRequest } from "./githubPublicationTypes";
export const fetchPendingGithubPublications = (taskId: number, afterId = 0, options?: ApiRequestOptions) =>
  apiRequest("fetchPendingGithubPublications", { taskId, afterId, limit: 20 }, options);
export const verifyGithubPublication = (taskId: number, publicationId: number, payload: GithubPublicationVerifyRequest, options?: ApiRequestOptions) =>
  apiRequest("verifyGithubPublication", { taskId, publicationId, payload }, options);
export const closeGithubPublication = (taskId: number, publicationId: number, payload: GithubPublicationCloseRequest, options?: ApiRequestOptions) =>
  apiRequest("closeGithubPublication", { taskId, publicationId, payload }, options);
