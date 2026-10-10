package com.alibaba.cloud.ai.studio.domain.model;

import java.util.Date;
import java.util.Set;

/** 领域查询条件：仅保存已校验的筛选数据，不依赖 Web 请求对象。 */
public record ConversationSearchCriteria(
        Set<Long> appIds, Long conversationId, String userId, String invokeSource,
        String name, String status, Date startTime, Date endTime,
        Integer minMessageCount, Integer maxMessageCount) {
}
