package com.repoguard.agent.service;

import com.repoguard.agent.dto.UserManagementItemDto;
import com.repoguard.agent.dto.UserOperationAuditContext;
import com.repoguard.agent.dto.UserOperationAuditDto;
import java.util.List;

public interface UserManagementService {

    List<UserManagementItemDto> listUsers();

    List<UserOperationAuditDto> listOperationAudits();

    UserManagementItemDto updateRole(UserOperationAuditContext auditContext, Long userId, String role);

    UserManagementItemDto updateStatus(UserOperationAuditContext auditContext, Long userId, String status);
}
