import type { RouteMeta } from "vue-router";

export type RouteAccessContext = {
  authenticated: boolean;
  managementAllowed: boolean;
};

export const canAccessRouteMeta = (
  meta: RouteMeta,
  { authenticated, managementAllowed }: RouteAccessContext
) => {
  if (meta.requiresAuth && !authenticated) {
    return false;
  }
  return !meta.requiresManage || authenticated && managementAllowed;
};
