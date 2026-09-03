export const routeNames = {
  login: "login",
  overview: "overview",
  tasks: "tasks",
  taskDetail: "task-detail",
  rules: "rules",
  integrations: "integrations",
  messageQueue: "message-queue",
  notificationOps: "notification-ops",
  users: "users",
  tenants: "tenants",
  settings: "settings",
  notFound: "not-found"
} as const;

export type RouteName = (typeof routeNames)[keyof typeof routeNames];
