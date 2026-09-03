package com.repoguard.agent.service;

import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.scm.ScmChangeRequestSummary;
import com.repoguard.agent.scm.ScmCommentDraft;
import com.repoguard.agent.scm.ScmCommentResult;
import com.repoguard.agent.scm.ScmProviderRegistry;
import com.repoguard.agent.scm.ScmStatusRequest;
import com.repoguard.agent.scm.ScmStatusResult;
import java.util.List;
import java.util.Map;

/** Application boundary for provider-neutral SCM operations. */
public interface ScmProviderService {

    List<ScmProviderRegistry.ScmProviderDescriptor> providers();

    List<ScmChangeRequestSummary> changeRequests(String provider);

    PullRequestDiff diff(String provider, Long taskId);

    Map<String, String> head(String provider, Long taskId);

    ScmCommentResult comment(String provider, Long taskId, ScmCommentDraft draft);

    ScmStatusResult status(String provider, Long taskId, ScmStatusRequest request);
}
