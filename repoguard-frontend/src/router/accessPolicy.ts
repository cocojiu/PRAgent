import type { RouteMeta } from "vue-router";

export type RouteAccessContext = {
  authenticated: boolean;
  managementAllowed: boolean;
  enterpriseEnabled: boolean;
  role?: string;
};

export const canAccessRouteMeta = (
  meta: RouteMeta,
  { authenticated, managementAllowed, enterpriseEnabled, role }: RouteAccessContext
) => {
  if (meta.requiresAuth && !authenticated) {
    return false;
  }
  if (meta.requiresEnterprise && !enterpriseEnabled) {
    return false;
  }
  if (meta.requiresManage && (!authenticated || !managementAllowed)) {
    return false;
  }
  const requiredRoles = Array.isArray(meta.requiresRole)
    ? meta.requiresRole.filter((requiredRole): requiredRole is string => typeof requiredRole === "string")
    : undefined;
  if (requiredRoles && !requiredRoles.some(requiredRole =>
    requiredRole.toUpperCase() === (role || "").toUpperCase()
  )) {
    return false;
  }
  return true;
};
