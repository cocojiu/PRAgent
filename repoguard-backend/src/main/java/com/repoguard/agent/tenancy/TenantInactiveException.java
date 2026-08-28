package com.repoguard.agent.tenancy;

public class TenantInactiveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TenantInactiveException(long tenantId) {
        super("Tenant is not active: " + tenantId);
    }
}
