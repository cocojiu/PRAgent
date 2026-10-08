package com.repoguard.agent.controller;

import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.review.progress.ReviewProgressStreamService;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@ApiRuntimeEnabled
@ConditionalOnProperty(name = "repoguard.review.progress-stream-enabled", havingValue = "true")
public class ReviewProgressController {
    private final ReviewProgressStreamService streams;

    public ReviewProgressController(ReviewProgressStreamService streams) {
        this.streams = streams;
    }

    @GetMapping(value = "/api/v1/reviews/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable @Min(1) Long id,
                             @RequestHeader(value = "Last-Event-ID", required = false) @Min(0) Long cursor,
                             HttpServletRequest request, HttpServletResponse response) {
        SseEmitter emitter = streams.open(id, RequestAuthentication.require(request), cursor);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return emitter;
    }
}
