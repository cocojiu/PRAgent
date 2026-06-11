package com.repoguard.agent.service;

import com.repoguard.agent.dto.UserManagementItemDto;
import java.util.List;

public interface UserManagementService {

    List<UserManagementItemDto> listUsers();

    UserManagementItemDto updateRole(Long operatorId, Long userId, String role);

    UserManagementItemDto updateStatus(Long operatorId, Long userId, String status);
}
