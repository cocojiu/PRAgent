package com.repoguard.agent.service;

import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.UserCreateRequest;
import com.repoguard.agent.dto.UserManagementItemDto;
import com.repoguard.agent.dto.UserOperationAuditContext;
import com.repoguard.agent.dto.UserOperationAuditDto;

public interface UserManagementService {

    PageResponse<UserManagementItemDto> listUsers(int page, int pageSize, String role, String status, String keyword);

    PageResponse<UserOperationAuditDto> listOperationAudits(int page, int pageSize);

    UserManagementItemDto createUser(UserOperationAuditContext auditContext, UserCreateRequest request);

    UserManagementItemDto updateRole(UserOperationAuditContext auditContext, Long userId, String role);

    UserManagementItemDto updateStatus(UserOperationAuditContext auditContext, Long userId, String status);
}
