package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.OperationalPayloadRetentionPreview;
import com.repoguard.agent.retention.OperationalPayloadRetentionQuery;
import com.repoguard.agent.security.RequireRole;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ApiRuntimeEnabled
@RequireRole("ADMIN")
@RequestMapping("/api/v1/config/data-retention")
public class OperationalPayloadRetentionController {
    private final OperationalPayloadRetentionQuery query;

    public OperationalPayloadRetentionController(OperationalPayloadRetentionQuery query) { this.query = query; }

    @GetMapping("/payload-preview")
    public ApiResponse<List<OperationalPayloadRetentionPreview>> preview() { return ApiResponse.ok(query.preview()); }
}
