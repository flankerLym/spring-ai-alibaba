package com.alibaba.cloud.ai.studio.openapi.dto.conversation;

import lombok.Data;
import com.alibaba.cloud.ai.studio.runtime.domain.PagingList;

/** 对外会话接口数据对象，不包含查询逻辑。 */
@Data public class ConversationDetail {
        private ConversationView conversation;
        private PagingList<MessageView> messages;
    }
