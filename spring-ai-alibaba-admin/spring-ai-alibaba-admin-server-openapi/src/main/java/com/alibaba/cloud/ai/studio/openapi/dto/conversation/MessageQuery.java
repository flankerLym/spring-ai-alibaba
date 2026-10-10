package com.alibaba.cloud.ai.studio.openapi.dto.conversation;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonAlias;

/** 对外会话接口数据对象，不包含查询逻辑。 */
@Data public class MessageQuery {
        private String conversationId;
        private String role;
        private String status;
        private String startTime;
        private String endTime;
        // 暂不开放排序字段，消息按原默认顺序返回。
        @JsonAlias({"current", "page"}) private Integer pageNum = 1;
        @JsonAlias("size") private Integer pageSize = 20;
    }
