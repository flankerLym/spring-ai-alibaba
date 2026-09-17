package com.alibaba.cloud.ai.studio.admin.mapper;

import com.alibaba.cloud.ai.studio.core.base.entity.WorkflowSpanEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface WorkflowSpanMapper extends BaseMapper<WorkflowSpanEntity> {

    int batchInsert(@Param("list") List<WorkflowSpanEntity> list);
}
