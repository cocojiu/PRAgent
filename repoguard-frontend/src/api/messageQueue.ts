import { request } from "@/api/client";
import type { MessageQueueHealth } from "@/types";

export const fetchMessageQueueHealth = () => request<MessageQueueHealth>("/api/v1/message-queue/health");
