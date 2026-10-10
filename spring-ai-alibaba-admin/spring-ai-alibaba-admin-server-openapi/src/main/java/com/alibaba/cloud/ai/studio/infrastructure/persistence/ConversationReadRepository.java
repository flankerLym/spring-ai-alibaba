package com.alibaba.cloud.ai.studio.infrastructure.persistence;

import com.alibaba.cloud.ai.studio.core.base.entity.AppEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.ConversationMessageEntity;
import com.alibaba.cloud.ai.studio.core.base.entity.ConversationRecordEntity;
import com.alibaba.cloud.ai.studio.core.base.mapper.AppMapper;
import com.alibaba.cloud.ai.studio.core.base.mapper.ConversationMessageMapper;
import com.alibaba.cloud.ai.studio.core.base.mapper.ConversationRecordMapper;
import com.alibaba.cloud.ai.studio.domain.model.ConversationSearchCriteria;
import com.alibaba.cloud.ai.studio.domain.model.MessageSearchCriteria;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;
import com.alibaba.cloud.ai.studio.runtime.enums.AppStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/** MyBatis 持久化适配器：只负责读取，领域对象和接口 DTO 不负责构造 SQL。 */
@Repository
@RequiredArgsConstructor
public class ConversationReadRepository {
    private final ConversationRecordMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final AppMapper appMapper;

    public Set<String> workspaceAppIds(String workspaceId) {
        List<AppEntity> apps = appMapper.selectList(new LambdaQueryWrapper<AppEntity>()
                .select(AppEntity::getAppId)
                .eq(AppEntity::getWorkspaceId, workspaceId)
                .ne(AppEntity::getStatus, AppStatus.DELETED));
        Set<String> ids = new LinkedHashSet<>();
        for (AppEntity app : apps) ids.add(app.getAppId());
        return ids;
    }

    public boolean appInWorkspace(String workspaceId, Long appId) {
        return appMapper.selectCount(new LambdaQueryWrapper<AppEntity>()
                .eq(AppEntity::getWorkspaceId, workspaceId)
                .eq(AppEntity::getAppId, appId.toString())
                .ne(AppEntity::getStatus, AppStatus.DELETED)) > 0;
    }

    public ConversationRecordEntity findById(Long id) { return conversationMapper.selectById(id); }

    public PagingList<ConversationRecordEntity> findConversations(
            ConversationSearchCriteria criteria, int pageNum, int pageSize) {
        LambdaQueryWrapper<ConversationRecordEntity> query = new LambdaQueryWrapper<>();
        query.in(ConversationRecordEntity::getAppId, criteria.appIds());
        if (criteria.conversationId() != null) {
            query.eq(ConversationRecordEntity::getId, criteria.conversationId());
        }
        query.eq(StringUtils.isNotBlank(criteria.userId()), ConversationRecordEntity::getUserId, criteria.userId());
        query.eq(StringUtils.isNotBlank(criteria.invokeSource()), ConversationRecordEntity::getInvokeSource, criteria.invokeSource());
        query.eq(StringUtils.isNotBlank(criteria.status()), ConversationRecordEntity::getStatus, criteria.status());
        query.like(StringUtils.isNotBlank(criteria.name()), ConversationRecordEntity::getName, criteria.name());
        query.ge(criteria.startTime() != null, ConversationRecordEntity::getCreatedAt, criteria.startTime());
        query.le(criteria.endTime() != null, ConversationRecordEntity::getCreatedAt, criteria.endTime());
        query.ge(criteria.minMessageCount() != null, ConversationRecordEntity::getMessageCount, criteria.minMessageCount());
        query.le(criteria.maxMessageCount() != null, ConversationRecordEntity::getMessageCount, criteria.maxMessageCount());

        // 排序能力暂不对外开放。以下保留原有动态排序实现，未来内部启用时可恢复：
        // String sortOrder = StringUtils.defaultIfBlank(filter.getSortOrder(), "desc");
        // boolean asc = "asc".equalsIgnoreCase(sortOrder);
        // if (!asc && !"desc".equalsIgnoreCase(sortOrder)) {
        //     throw invalid("sortOrder", "supported values: asc, desc");
        // }
        // String sortBy = StringUtils.defaultIfBlank(filter.getSortBy(), "updatedAt");
        // switch (sortBy) {
        //     case "createdAt" -> query.orderBy(true, asc, ConversationRecordEntity::getCreatedAt);
        //     case "updatedAt" -> query.orderBy(true, asc, ConversationRecordEntity::getUpdatedAt);
        //     case "messageCount" -> query.orderBy(true, asc, ConversationRecordEntity::getMessageCount);
        //     default -> throw invalid("sortBy", "supported values: createdAt, updatedAt, messageCount");
        // }
        // query.orderBy(true, asc, ConversationRecordEntity::getId);
        // 固定原默认顺序：最近更新的会话在前，同时间按 ID 倒序保证分页稳定。
        query.orderByDesc(ConversationRecordEntity::getUpdatedAt)
                .orderByDesc(ConversationRecordEntity::getId);

        Page<ConversationRecordEntity> result = conversationMapper.selectPage(new Page<>(pageNum, pageSize), query);
        return new PagingList<>(pageNum, pageSize, result.getTotal(), result.getRecords());
    }

    public PagingList<ConversationMessageEntity> findMessages(
            MessageSearchCriteria criteria, int pageNum, int pageSize) {
        LambdaQueryWrapper<ConversationMessageEntity> query = new LambdaQueryWrapper<>();
        query.eq(ConversationMessageEntity::getConversationId, criteria.conversationId())
                .eq(ConversationMessageEntity::getAppId, criteria.appId());
        query.eq(StringUtils.isNotBlank(criteria.role()), ConversationMessageEntity::getRole, criteria.role());
        query.eq(StringUtils.isNotBlank(criteria.status()), ConversationMessageEntity::getStatus, criteria.status());
        query.ge(criteria.startTime() != null, ConversationMessageEntity::getCreatedAt, criteria.startTime());
        query.le(criteria.endTime() != null, ConversationMessageEntity::getCreatedAt, criteria.endTime());

        // 原排序逻辑保留（不开放 sortOrder 参数）：
        // String sortOrder = StringUtils.defaultIfBlank(filter.getSortOrder(), "asc");
        // if (!"asc".equalsIgnoreCase(sortOrder) && !"desc".equalsIgnoreCase(sortOrder)) {
        //     throw invalid("sortOrder", "supported values: asc, desc");
        // }
        // boolean asc = "asc".equalsIgnoreCase(sortOrder);
        // query.orderBy(true, asc, ConversationMessageEntity::getSequence)
        //      .orderBy(true, asc, ConversationMessageEntity::getMessageId);
        // 固定原默认升序，保证消息阅读顺序一致。
        query.orderByAsc(ConversationMessageEntity::getSequence)
                .orderByAsc(ConversationMessageEntity::getMessageId);

        Page<ConversationMessageEntity> result = messageMapper.selectPage(new Page<>(pageNum, pageSize), query);
        return new PagingList<>(pageNum, pageSize, result.getTotal(), result.getRecords());
    }
}
