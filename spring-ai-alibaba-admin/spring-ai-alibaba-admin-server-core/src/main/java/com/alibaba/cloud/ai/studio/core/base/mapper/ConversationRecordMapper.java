package com.alibaba.cloud.ai.studio.core.base.mapper;

import com.alibaba.cloud.ai.studio.core.base.entity.ConversationRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Conversation mapper.
 */
public interface ConversationRecordMapper extends BaseMapper<ConversationRecordEntity> {

	/**
	 * Locks one conversation row while appending messages so sequence/message_count
	 * remain consistent for concurrent requests.
	 */
	@Select("select * from conversation_record where id = #{id} for update")
	ConversationRecordEntity selectByIdForUpdate(@Param("id") Long id);

}
