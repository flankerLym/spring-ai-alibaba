package com.alibaba.cloud.ai.studio.controller;

import com.alibaba.cloud.ai.studio.application.OpenApiCompletionService;
import com.alibaba.cloud.ai.studio.infrastructure.transport.OpenApiSseTransport;
import com.alibaba.cloud.ai.studio.runtime.domain.Result;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentRequest;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** OpenAPI HTTP entry points; workflow/business logic belongs to application services. */
@RestController
@RequiredArgsConstructor
@Tag(name = "chat")
@RequestMapping("/api/v1/apps")
public class ChatController {
    private final OpenApiCompletionService completionService;
    private final OpenApiSseTransport transport;

    @PostMapping("/chat/completions")
    public Object completion(@RequestBody AgentRequest request, HttpServletResponse response) {
        return transport.chat(request, response);
    }

    @PostMapping("/workflow/completions")
    public Object completion(@RequestBody WorkflowRequest request, HttpServletResponse response) {
        return transport.workflow(request, response);
    }

    @PostMapping("/workflow/async-completions")
    public Result<TaskRunResponse> asyncCompletion(@RequestBody WorkflowRequest request) {
        return completionService.startWorkflow(request);
    }

    @PostMapping("/workflow/stop-completions")
    public Result<Boolean> stopCompletion(@RequestBody TaskStopRequest request) {
        return completionService.stopWorkflow(request);
    }

    @PostMapping("/workflow/async-results")
    public Result<AsyncResultResponse> getAsyncResults(@RequestBody AsyncResultRequest request) {
        return completionService.workflowResult(request);
    }
}
