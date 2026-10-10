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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    public Object workflow(WorkflowRequest request, HttpServletResponse response) {
        long start = System.currentTimeMillis();
        RequestContext context = RequestContextHolder.getRequestContext();
        context.setStartTime(System.currentTimeMillis());
        LogUtils.trace(context, "startCall", SUCCESS, start, request, null);
        if (Boolean.TRUE.equals(request.getStream())) {
            return streamWorkflow(request, response, context);
        }
        try {
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

    private SseEmitter streamWorkflow(WorkflowRequest request, HttpServletResponse response, RequestContext context) {
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
                    writeChunk(context, request, emitter, chunk, response, closed);
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
                            WorkflowResponse chunk, HttpServletResponse response, AtomicBoolean closed) {
        if (chunk.getError() != null && !response.isCommitted()) response.setStatus(chunk.getError().getStatusCode());
        writeJson(context, emitter, closed, JsonUtils.toJson(chunk), "workflow");
    }

    private void writeJson(RequestContext context, SseEmitter emitter, AtomicBoolean closed, String json, String type) {
        try {
            if (closed.get()) throw new StreamWriteException("SSE emitter is already closed");
            emitter.send(json, MediaType.TEXT_EVENT_STREAM);
        } catch (StreamWriteException e) {
            throw e;
        } catch (Exception e) {
            closed.set(true);
            log.debug("SSE {} response write stopped, requestId={}, reason={}", type, context.getRequestId(), e.getMessage());
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
                log.debug("SSE emitter complete ignored, requestId={}, reason={}", context.getRequestId(), e.getMessage());
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
