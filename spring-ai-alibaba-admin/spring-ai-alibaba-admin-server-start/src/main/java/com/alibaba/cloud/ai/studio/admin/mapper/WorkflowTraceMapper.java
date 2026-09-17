package com.alibaba.cloud.ai.studio.admin.mapper;

import com.alibaba.cloud.ai.studio.core.base.entity.WorkflowTraceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface WorkflowTraceMapper extends BaseMapper<WorkflowTraceEntity> {

    int insertTrace(@Param("entity") WorkflowTraceEntity entity);
}
