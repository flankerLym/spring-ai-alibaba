package com.alibaba.cloud.ai.studio.domain.model;

import java.util.Date;

/** 消息历史的筛选条件，不包含对外排序参数。 */
public record MessageSearchCriteria(
        Long conversationId, Long appId, String role, String status, Date startTime, Date endTime) {
}
