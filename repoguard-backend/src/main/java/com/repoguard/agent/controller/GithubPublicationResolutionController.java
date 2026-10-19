package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.github.comment.GithubPublicationResolutionService;
import com.repoguard.agent.github.comment.GithubPublicationResolutionService.PendingGithubPublication;
import com.repoguard.agent.security.RequireRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/reviews/{taskId}/github-comments/resolutions")
public class GithubPublicationResolutionController {
    private final GithubPublicationResolutionService service;
    public GithubPublicationResolutionController(GithubPublicationResolutionService service) { this.service = service; }
    @GetMapping
    @RequireRole("ADMIN")
    public ApiResponse<List<PendingGithubPublication>> list(@PathVariable @Min(1) Long taskId,
        @RequestParam(defaultValue = "0") @Min(0) long afterId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return ApiResponse.ok(service.list(taskId, afterId, limit));
    }
    @PostMapping("/{publicationId}/verify")
    @RequireRole("ADMIN")
    public ApiResponse<PendingGithubPublication> verify(@PathVariable @Min(1) Long taskId,
        @PathVariable @Min(1) Long publicationId, @RequestBody @Valid GithubPublicationVerifyRequest request) {
        return ApiResponse.ok(service.verify(taskId, publicationId, request.version(), request.kind(), request.remoteId()));
    }
    @PostMapping("/{publicationId}/close")
    @RequireRole("ADMIN")
    public ApiResponse<PendingGithubPublication> close(@PathVariable @Min(1) Long taskId,
        @PathVariable @Min(1) Long publicationId, @RequestBody @Valid GithubPublicationCloseRequest request) {
        return ApiResponse.ok(service.close(taskId, publicationId, request.version(), request.reason(), request.confirmNoRetry()));
    }
    public record GithubPublicationVerifyRequest(@NotBlank @Size(max = 64) String version,
        @NotBlank @Pattern(regexp = "issue_comment|review_comment|review") String kind, @Min(1) long remoteId) { }
    public record GithubPublicationCloseRequest(@NotBlank @Size(max = 64) String version,
        @NotBlank @Size(min = 5, max = 240) String reason, boolean confirmNoRetry) { }
}
