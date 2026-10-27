package com.alibaba.cloud.ai.studio.controller;

import com.alibaba.cloud.ai.studio.application.OpenApiCompletionService;
import com.alibaba.cloud.ai.studio.core.base.service.ApiKeyService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.utils.LogUtils;
import com.alibaba.cloud.ai.studio.infrastructure.transport.OpenApiSseTransport;
import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.Result;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentRequest;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.WorkflowStatus;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.*;
import com.alibaba.cloud.ai.studio.runtime.utils.ExceptionUtils;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.alibaba.cloud.ai.studio.core.utils.LogUtils.FAIL;
import static com.alibaba.cloud.ai.studio.core.utils.LogUtils.SUCCESS;

/** OpenAPI HTTP entry points; workflow/business logic belongs to application services. */
@RestController
@RequiredArgsConstructor
@Tag(name = "chat")
@RequestMapping("/api/v1/dify")
public class DifyChatController {

    private final OpenApiCompletionService completionService;

    private final OpenApiSseTransport transport;

    private final ApiKeyService apiKeyService;


    @PostMapping("/workflow/completions")
    public Object completion(@RequestBody WorkflowRequest request, HttpServletResponse response) {
        RequestContext context = RequestContextHolder.getRequestContext();
        Set<String> allowedAppIds = apiKeyService.getAccessibleAppIds(context.getApiKeyId(), context.getWorkspaceId());
        if(CollectionUtils.size(allowedAppIds) !=1){
            return Result.error("该模式下一个apikey只能绑定单个应用");
        }
        return transport.workflow(request, response, new Function<>() {
            @Override
            public Object apply(WorkflowResponse res) {
                return null;
            }
        }, new Function<WorkflowResponse, String>() {
            @Override
            public String apply(WorkflowResponse workflowResponse) {
                return "";
            }
        });
    }



}
