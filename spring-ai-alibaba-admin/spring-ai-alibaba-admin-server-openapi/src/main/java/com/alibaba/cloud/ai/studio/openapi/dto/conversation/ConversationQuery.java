package com.alibaba.cloud.ai.studio.openapi.dto.conversation;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

/** 对外会话接口数据对象，不包含查询逻辑。 */
@Data public class ConversationQuery {
        private String appId;
        private List<String> appIds;
        private String conversationId;
        private String userId;
        private String invokeSource;
        private String name;
        private String status;
        private String startTime;
        private String endTime;
        private Integer minMessageCount;
        private Integer maxMessageCount;
        // 暂不开放排序字段；原 sortBy 逻辑保留在持久化适配器的中文注释中。
        @JsonAlias({"current", "page"}) private Integer pageNum = 1;
        @JsonAlias("size") private Integer pageSize = 20;
    }
