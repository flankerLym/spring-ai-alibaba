package com.alibaba.cloud.ai.studio.infrastructure.transport;

import com.alibaba.cloud.ai.studio.application.OpenApiCompletionService;
import com.alibaba.cloud.ai.studio.core.context.RequestContextHolder;
import com.alibaba.cloud.ai.studio.core.utils.LogUtils;
import com.alibaba.cloud.ai.studio.runtime.domain.Error;
import com.alibaba.cloud.ai.studio.runtime.domain.RequestContext;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentRequest;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentResponse;
import com.alibaba.cloud.ai.studio.runtime.domain.agent.AgentStatus;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.WorkflowStatus;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.WorkflowRequest;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.debug.WorkflowResponse;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.utils.ExceptionUtils;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.alibaba.cloud.ai.studio.core.utils.LogUtils.FAIL;
import static com.alibaba.cloud.ai.studio.core.utils.LogUtils.SUCCESS;

/** HTTP/SSE adapter: all servlet concerns and Reactor subscription lifecycles stay here. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenApiSseTransport {
    private final OpenApiCompletionService completionService;

    public Object chat(AgentRequest request, HttpServletResponse response) {
        long start = System.currentTimeMillis();
        RequestContext context = RequestContextHolder.getRequestContext();
        context.setStartTime(System.currentTimeMillis());
        LogUtils.trace(context, "startCall", SUCCESS, start, request, null);
        if (Boolean.TRUE.equals(request.getStream())) {
            return streamChat(request, response, context);
        }
        try {
            String result = JsonUtils.toJson(completionService.chat(request));
            LogUtils.monitor(context, "ChatController", "endCall", context.getStartTime(), SUCCESS, request, result);
            return result;
        } catch (Exception e) {
            Error error = ExceptionUtils.convertError(e);
            response.setStatus(error.getStatusCode());
            String result = JsonUtils.toJson(error);
            LogUtils.monitor(context, "ChatController", "endCallError", context.getStartTime(), FAIL, request, result, e);
            return result;
        }
    }

    /** Existing workflow transport contract retained for existing callers. */
    public Object workflow(WorkflowRequest request, HttpServletResponse response,
                           Function<WorkflowResponse, Object> blockConvertor,
                           Function<WorkflowResponse, String> streamConvertor) {
        long start = System.currentTimeMillis();
        RequestContext context = RequestContextHolder.getRequestContext();
        context.setStartTime(System.currentTimeMillis());
        LogUtils.trace(context, "startCall", SUCCESS, start, request, null);
        if (Boolean.TRUE.equals(request.getStream())) {
            return streamWorkflow(request, response, context, streamConvertor);
        }
        try {
            if (blockConvertor != null) {
                return blockConvertor.apply(completionService.workflow(request));
            }
            String result = JsonUtils.toJson(completionService.workflow(request));
            LogUtils.monitor(context, "ChatController", "endCall", context.getStartTime(), SUCCESS, request, result);
            return result;
        } catch (Exception e) {
            Error error = ExceptionUtils.convertError(e);
            response.setStatus(error.getStatusCode());
            String result = JsonUtils.toJson(error);
            LogUtils.monitor(context, "ChatController", "endCallError", context.getStartTime(), FAIL, request, result, e);
            return result;
        }
    }

    /**
     * Dify-specific workflow adapter. Unlike the legacy single-payload converter, the
     * Dify converter can return multiple independent event payloads for one internal
     * workflow item (for example node_started + message + node_finished).
     */
    public Object workflowEvents(WorkflowRequest request, HttpServletResponse response,
                                 Function<WorkflowResponse, Object> blockConvertor,
                                 Function<WorkflowResponse, List<String>> streamConvertor,
                                 Supplier<List<String>> completionConvertor,
                                 Function<Error, Object> errorConvertor) {
        long start = System.currentTimeMillis();
        RequestContext context = RequestContextHolder.getRequestContext();
        context.setStartTime(System.currentTimeMillis());
        LogUtils.trace(context, "startCall", SUCCESS, start, request, null);
        if (Boolean.TRUE.equals(request.getStream())) {
            return streamWorkflowEvents(request, response, context, streamConvertor, completionConvertor);
        }
        try {
            WorkflowResponse result = completionService.workflow(request);
            if (result != null && (result.getError() != null || result.getStatus() == WorkflowStatus.FAILED)) {
                Error error = result.getError() == null
                        ? ErrorCode.WORKFLOW_EXECUTE_ERROR.toError("Workflow execution failed")
                        : result.getError();
                response.setStatus(error.getStatusCode() == null ? 500 : error.getStatusCode());
                return errorConvertor.apply(error);
            }
            if (result != null && result.getStatus() == WorkflowStatus.PAUSE) {
                response.setStatus(409);
                return errorConvertor.apply(new Error(409, "invalid_request_error", "completion_request_error",
                        "Workflow paused before returning a final chat answer", null));
            }
            Object body = blockConvertor.apply(result);
            LogUtils.monitor(context, "DifyChatController", "endCall", context.getStartTime(), SUCCESS, request, body);
            return body;
        } catch (Exception e) {
            Error error = ExceptionUtils.convertError(e);
            response.setStatus(error.getStatusCode() == null ? 500 : error.getStatusCode());
            Object body = errorConvertor.apply(error);
            LogUtils.monitor(context, "DifyChatController", "endCallError", context.getStartTime(), FAIL,
                    request, body, e);
            return body;
        }
    }

    private SseEmitter streamChat(AgentRequest request, HttpServletResponse response, RequestContext context) {
        Flux<AgentResponse> source = completionService.streamChat(request);
        prepareHeaders(response);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        bindEmitterLifecycle(emitter, closed, subscriptionRef);
        Disposable subscription = source
                .onErrorResume(err -> {
                    LogUtils.monitor(context, "ChatController", "endStreamCallError", context.getStartTime(),
                            FAIL, request, err.getMessage(), err);
                    Error error = ExceptionUtils.convertError(err);
                    return Mono.just(AgentResponse.builder().requestId(context.getRequestId()).error(error).build());
                })
                .doOnNext(chunk -> {
                    writeChunk(context, request, emitter, chunk, response, closed);
                    if (chunk.getStatus() == AgentStatus.COMPLETED) {
                        LogUtils.trace(context, "ChatController", "endStreamCall", context.getStartTime(), SUCCESS,
                                request, JsonUtils.toJson(chunk));
                    }
                })
                .onErrorResume(StreamWriteException.class, err -> Mono.empty())
                .doFinally(signal -> handleComplete(context, signal, emitter, closed))
                .subscribe();
        subscriptionRef.set(subscription);
        if (closed.get()) disposeSubscription(subscriptionRef);
        return emitter;
    }

    private SseEmitter streamWorkflow(WorkflowRequest request, HttpServletResponse response, RequestContext context,
                                     Function<WorkflowResponse, String> streamConvertor) {
        Flux<WorkflowResponse> source = completionService.streamWorkflow(request);
        prepareHeaders(response);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        bindEmitterLifecycle(emitter, closed, subscriptionRef);
        Disposable subscription = source
                .onErrorResume(err -> {
                    LogUtils.monitor(context, "ChatController", "endStreamCallError", context.getStartTime(),
                            FAIL, request, err.getMessage(), err);
                    Error error = ExceptionUtils.convertError(err);
                    return Mono.just(WorkflowResponse.builder().requestId(context.getRequestId())
                            .error(error).status(WorkflowStatus.FAILED).build());
                })
                .doOnNext(chunk -> {
                    writeChunk(context, request, emitter, chunk, response, closed, streamConvertor);
                    if (chunk.getStatus() == WorkflowStatus.COMPLETED) {
                        LogUtils.trace(context, "ChatController", "endStreamCall", context.getStartTime(), SUCCESS,
                                request, JsonUtils.toJson(chunk));
                    }
                })
                .onErrorResume(StreamWriteException.class, err -> Mono.empty())
                .doFinally(signal -> handleComplete(context, signal, emitter, closed))
                .subscribe();
        subscriptionRef.set(subscription);
        if (closed.get()) disposeSubscription(subscriptionRef);
        return emitter;
    }

    private SseEmitter streamWorkflowEvents(WorkflowRequest request, HttpServletResponse response,
                                            RequestContext context,
                                            Function<WorkflowResponse, List<String>> streamConvertor,
                                            Supplier<List<String>> completionConvertor) {
        Flux<WorkflowResponse> source = completionService.streamWorkflow(request);
        prepareHeaders(response);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();
        bindEmitterLifecycle(emitter, closed, subscriptionRef);
        ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dify-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        emitter.onCompletion(heartbeatExecutor::shutdownNow);
        emitter.onTimeout(heartbeatExecutor::shutdownNow);
        emitter.onError(error -> heartbeatExecutor.shutdownNow());
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (closed.get()) return;
            try {
                writeJson(context, emitter, closed, JsonUtils.toJson(Collections.singletonMap("event", "ping")),
                        "dify-heartbeat");
            } catch (RuntimeException ignored) {
                heartbeatExecutor.shutdownNow();
            }
        }, 10, 10, TimeUnit.SECONDS);
        Disposable subscription = source
                .onErrorResume(err -> {
                    LogUtils.monitor(context, "DifyChatController", "endStreamCallError", context.getStartTime(),
                            FAIL, request, err.getMessage(), err);
                    Error error = ExceptionUtils.convertError(err);
                    return Mono.just(WorkflowResponse.builder().requestId(context.getRequestId())
                            .conversationId(request.getConversationId()).error(error)
                            .status(WorkflowStatus.FAILED).build());
                })
                .doOnNext(chunk -> {
                    if (chunk.getError() != null && !response.isCommitted()) {
                        response.setStatus(chunk.getError().getStatusCode() == null
                                ? 500 : chunk.getError().getStatusCode());
                    }
                    List<String> payloads = streamConvertor == null
                            ? Collections.singletonList(JsonUtils.toJson(chunk)) : streamConvertor.apply(chunk);
                    if (payloads != null) {
                        for (String payload : payloads) {
                            if (payload != null && !payload.isBlank()) {
                                writeJson(context, emitter, closed, payload, "dify-workflow");
                            }
                        }
                    }
                    if (chunk.getStatus() == WorkflowStatus.COMPLETED) {
                        LogUtils.trace(context, "DifyChatController", "endStreamCall", context.getStartTime(),
                                SUCCESS, request, JsonUtils.toJson(chunk));
                    }
                })
                .onErrorResume(StreamWriteException.class, err -> Mono.empty())
                .doOnComplete(() -> {
                    if (!closed.get() && completionConvertor != null) {
                        List<String> payloads = completionConvertor.get();
                        if (payloads != null) {
                            for (String payload : payloads) {
                                if (payload != null && !payload.isBlank()) {
                                    writeJson(context, emitter, closed, payload, "dify-workflow");
                                }
                            }
                        }
                    }
                })
                .doFinally(signal -> {
                    heartbeatExecutor.shutdownNow();
                    handleComplete(context, signal, emitter, closed);
                })
                .subscribe();
        subscriptionRef.set(subscription);
        if (closed.get()) {
            heartbeatExecutor.shutdownNow();
            disposeSubscription(subscriptionRef);
        }
        return emitter;
    }

    private void prepareHeaders(HttpServletResponse response) {
        response.addHeader("X-Accel-Buffering", "no");
        response.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private void writeChunk(RequestContext context, AgentRequest request, SseEmitter emitter,
                            AgentResponse chunk, HttpServletResponse response, AtomicBoolean closed) {
        if (chunk.getError() != null && !response.isCommitted()) response.setStatus(chunk.getError().getStatusCode());
        writeJson(context, emitter, closed, JsonUtils.toJson(chunk), "agent");
    }

    private void writeChunk(RequestContext context, WorkflowRequest request, SseEmitter emitter,
                            WorkflowResponse chunk, HttpServletResponse response, AtomicBoolean closed,
                            Function<WorkflowResponse, String> streamConvertor) {
        if (chunk.getError() != null && !response.isCommitted()) response.setStatus(chunk.getError().getStatusCode());
        if (streamConvertor != null) {
            writeJson(context, emitter, closed, streamConvertor.apply(chunk), "workflow");
        } else {
            writeJson(context, emitter, closed, JsonUtils.toJson(chunk), "workflow");
        }
    }

    private void writeJson(RequestContext context, SseEmitter emitter, AtomicBoolean closed, String json, String type) {
        try {
            if (closed.get()) throw new StreamWriteException("SSE emitter is already closed");
            emitter.send(json, MediaType.TEXT_EVENT_STREAM);
        } catch (StreamWriteException e) {
            throw e;
        } catch (Exception e) {
            closed.set(true);
            log.debug("SSE {} response write stopped, requestId={}, reason={}", type,
                    context.getRequestId(), e.getMessage());
            throw new StreamWriteException(e);
        }
    }

    private void bindEmitterLifecycle(SseEmitter emitter, AtomicBoolean closed,
                                      AtomicReference<Disposable> subscriptionRef) {
        emitter.onCompletion(() -> { closed.set(true); disposeSubscription(subscriptionRef); });
        emitter.onTimeout(() -> { closed.set(true); disposeSubscription(subscriptionRef); });
        emitter.onError(error -> { closed.set(true); disposeSubscription(subscriptionRef); });
    }

    private void disposeSubscription(AtomicReference<Disposable> subscriptionRef) {
        Disposable disposable = subscriptionRef.get();
        if (disposable != null && !disposable.isDisposed()) disposable.dispose();
    }

    private void handleComplete(RequestContext context, SignalType signal, SseEmitter emitter, AtomicBoolean closed) {
        if (signal == SignalType.CANCEL || closed.get()) {
            LogUtils.monitor(context, "ChatController", "endStreamCall", context.getStartTime(), "cancel", null, null);
            return;
        }
        if (closed.compareAndSet(false, true)) {
            try { emitter.complete(); }
            catch (Exception e) {
                log.debug("SSE emitter complete ignored, requestId={}, reason={}",
                        context.getRequestId(), e.getMessage());
            }
        }
        if (signal == SignalType.ON_COMPLETE) {
            LogUtils.monitor(context, "ChatController", "endStreamCall", context.getStartTime(), SUCCESS, null, null);
        } else {
            LogUtils.monitor(context, "ChatController", "endStreamCall", context.getStartTime(), FAIL, null,
                    signal.name());
        }
    }

    private static final class StreamWriteException extends RuntimeException {
        private StreamWriteException(String message) { super(message); }
        private StreamWriteException(Throwable cause) { super(cause); }
    }
}
