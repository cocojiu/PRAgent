package com.repoguard.agent.service;

import com.repoguard.agent.dto.ConnectionTestResultDto;

public interface NotificationBindingConnectionTestService {

    ConnectionTestResultDto testBinding(Long id);
}
