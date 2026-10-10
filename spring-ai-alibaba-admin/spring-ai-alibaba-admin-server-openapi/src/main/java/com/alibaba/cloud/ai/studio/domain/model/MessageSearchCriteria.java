package com.alibaba.cloud.ai.studio.domain.model;

import java.util.Date;

/** 问答历史查询条件，不再包含已废弃的 role 和 sequence。 */
public record MessageSearchCriteria(
        Long conversationId, Long appId, String status, Date startTime, Date endTime) {
}
