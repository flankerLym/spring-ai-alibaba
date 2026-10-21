package com.alibaba.cloud.ai.studio.admin.trace.service;

import com.alibaba.cloud.ai.studio.core.workflow.WorkflowContext;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeResult;
import com.alibaba.cloud.ai.studio.runtime.domain.workflow.NodeStatusEnum;
import com.alibaba.cloud.ai.studio.runtime.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Sends failed workflow details to a DingTalk custom robot webhook. */
@Slf4j
@Component
public class DingTalkWorkflowFailureNotifier {

    private static final String TITLE = "[agent] 工作流执行失败告警";
    private static final int MAX_INPUT_LENGTH = 6000;
    private static final int MAX_REASON_LENGTH = 1500;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${workflow.failure-notification.dingtalk.enabled:false}")
    private boolean enabled;

    @Value("${workflow.failure-notification.dingtalk.webhook-url:}")
    private String webhookUrl;

    @Value("${workflow.failure-notification.dingtalk.secret:}")
    private String secret;

    public void notifyFailure(WorkflowContext context) {
        if (!enabled || context == null
                || !NodeStatusEnum.FAIL.getCode().equals(context.getTaskStatus())) {
            return;
        }
        if (StringUtils.isBlank(webhookUrl)) {
            log.warn("DingTalk workflow-failure notification is enabled but webhook URL is empty, taskId={}",
                    context.getTaskId());
            return;
        }

        try {
            String inputJson = JsonUtils.toJson(buildInput(context));
            String reason = resolveFailureReason(context);
            String markdown = buildMarkdown(context, inputJson, reason);
            Map<String, Object> markdownContent = new LinkedHashMap<>();
            markdownContent.put("title", TITLE);
            markdownContent.put("text", markdown);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("msgtype", "markdown");
            payload.put("markdown", markdownContent);

            HttpRequest request = HttpRequest.newBuilder(buildWebhookUri())
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            Map<Object, Object> responseBody = JsonUtils.fromJsonToMap(response.body());
            Object errorCode = responseBody == null ? null : responseBody.get("errcode");
            Object errorMessage = responseBody == null ? null : responseBody.get("errmsg");
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || (errorCode != null && !"0".equals(String.valueOf(errorCode)))) {
                log.error("DingTalk workflow-failure notification rejected, taskId={}, httpStatus={}, errcode={}, errmsg={}",
                        context.getTaskId(), response.statusCode(), errorCode, errorMessage);
            }
            else {
                log.info("DingTalk workflow-failure notification sent, taskId={}, appId={}, conversationId={}",
                        context.getTaskId(), context.getAppId(), context.getConversationId());
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("DingTalk workflow-failure notification interrupted, taskId={}", context.getTaskId(), e);
        }
        catch (Exception e) {
            // Notification errors must never interrupt or change the workflow result.
            log.error("DingTalk workflow-failure notification failed, taskId={}", context.getTaskId(), e);
        }
    }

    private Map<String, Object> buildInput(WorkflowContext context) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("user", context.getUserMap() == null ? Map.of() : context.getUserMap());
        input.put("system", context.getSysMap() == null ? Map.of() : context.getSysMap());
        return input;
    }

    private String buildMarkdown(WorkflowContext context, String inputJson, String reason) {
        return "### " + TITLE + "\n\n"
                + "- **应用ID：** " + markdownValue(context.getAppId()) + "\n"
                + "- **会话ID：** " + markdownValue(context.getConversationId()) + "\n"
                + "- **来源渠道：** " + markdownValue(context.getInvokeSource()) + "\n"
                + "- **任务ID：** " + markdownValue(context.getTaskId()) + "\n\n"
                + "**输入参数（user / system）**\n\n```json\n"
                + clip(inputJson, MAX_INPUT_LENGTH).replace("```", "'''" ) + "\n```\n\n"
                + "**失败原因**\n\n```text\n"
                + clip(reason, MAX_REASON_LENGTH).replace("```", "'''" ) + "\n```";
    }

    private String resolveFailureReason(WorkflowContext context) {
        if (StringUtils.isNotBlank(context.getErrorInfo())) {
            return context.getErrorInfo();
        }

        if (context.getExecuteOrderList() != null && context.getNodeResultMap() != null) {
            for (int i = context.getExecuteOrderList().size() - 1; i >= 0; i--) {
                String nodeId = context.getExecuteOrderList().get(i);
                NodeResult result = context.getNodeResultMap().get(nodeId);
                if (result != null && NodeStatusEnum.FAIL.getCode().equals(result.getNodeStatus())) {
                    String name = StringUtils.defaultIfBlank(result.getNodeName(), nodeId);
                    String detail = StringUtils.defaultIfBlank(result.getErrorInfo(), result.getErrorCode());
                    return "节点 [" + name + "] 执行失败"
                            + (StringUtils.isBlank(detail) ? "" : "：" + detail);
                }
            }
        }

        if (context.getError() != null) {
            return JsonUtils.toJson(context.getError());
        }
        if (StringUtils.isNotBlank(context.getErrorCode())) {
            return context.getErrorCode();
        }
        return "工作流状态为 FAIL，但未提供具体失败原因";
    }

    private URI buildWebhookUri() throws Exception {
        if (StringUtils.isBlank(secret)) {
            return URI.create(webhookUrl.trim());
        }

        long timestamp = System.currentTimeMillis();
        String toSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sign = Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
        String separator = webhookUrl.contains("?") ? "&" : "?";
        return URI.create(webhookUrl.trim() + separator + "timestamp=" + timestamp
                + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8));
    }

    private String markdownValue(String value) {
        return StringUtils.isBlank(value) ? "-" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "\n...(内容过长，已截断)";
    }
}
