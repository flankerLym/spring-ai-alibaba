package com.alibaba.cloud.ai.studio.domain.service;

import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/** 权限领域规则：请求的应用集合必须是工作空间与 API Key 可访问集合的子集。 */
@Component
public class ConversationScopePolicy {
    public Set<Long> restrict(Set<Long> visible, Set<Long> requested) {
        Set<Long> result = new LinkedHashSet<>(visible);
        if (requested.isEmpty()) return result;
        if (!result.containsAll(requested)) {
            throw new BizException(ErrorCode.PERMISSION_DENIED.toError());
        }
        result.retainAll(requested);
        return result;
    }
}
