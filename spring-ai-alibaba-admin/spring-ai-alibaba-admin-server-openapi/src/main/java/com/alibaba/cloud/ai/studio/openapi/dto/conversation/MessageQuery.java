package com.alibaba.cloud.ai.studio.openapi.dto.conversation;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/** 一问一答历史查询：不再按角色过滤，排序字段暂不对外开放。 */
@Data
public class MessageQuery {
    private String conversationId;
    private String status;
    private String startTime;
    private String endTime;
    @JsonAlias({"current", "page"})
    private Integer pageNum = 1;
    @JsonAlias("size")
    private Integer pageSize = 20;
}
