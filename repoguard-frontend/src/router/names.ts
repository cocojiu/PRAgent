export const routeNames = {
  overview: "overview",
  tasks: "tasks",
  taskDetail: "task-detail",
  rules: "rules",
  integrations: "integrations",
  settings: "settings",
  notFound: "not-found"
} as const;

export type RouteName = (typeof routeNames)[keyof typeof routeNames];
