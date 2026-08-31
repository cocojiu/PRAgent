import type { RouteMeta } from "vue-router";

export type RouteAccessContext = {
  authenticated: boolean;
  managementAllowed: boolean;
  enterpriseEnabled: boolean;
};

export const canAccessRouteMeta = (
  meta: RouteMeta,
  { authenticated, managementAllowed, enterpriseEnabled }: RouteAccessContext
) => {
  if (meta.requiresAuth && !authenticated) {
    return false;
  }
  if (meta.requiresEnterprise && !enterpriseEnabled) {
    return false;
  }
  return !meta.requiresManage || authenticated && managementAllowed;
};
