package com.alibaba.cloud.ai.studio.domain.service;

import com.alibaba.cloud.ai.studio.runtime.enums.AppStatus;
import com.alibaba.cloud.ai.studio.runtime.enums.AppType;
import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** 应用筛选领域规则：规范化应用类型与状态，不依赖控制器或持久化对象。 */
@Component
public class OpenApiAppFilterRules {
	public String normalizeType(String type) {
		if (StringUtils.isBlank(type)) {
			return null;
		}

		String normalized = type.trim().toLowerCase();
		for (AppType appType : AppType.values()) {
			if (appType.getValue().equalsIgnoreCase(normalized)
					|| appType.name().equalsIgnoreCase(normalized)) {
				return appType.getValue();
			}
		}

		throw new BizException(ErrorCode.INVALID_PARAMS
			.toError("type", "supported values: basic, workflow"));
	}

	public AppStatus parseStatus(String status) {
		if (StringUtils.isBlank(status)) {
			return null;
		}

		String normalized = status.trim();
		for (AppStatus appStatus : AppStatus.values()) {
			if (appStatus.getValue().equalsIgnoreCase(normalized)
					|| appStatus.name().equalsIgnoreCase(normalized)) {
				if (appStatus == AppStatus.DELETED) {
					throw new BizException(ErrorCode.INVALID_PARAMS
						.toError("status", "deleted applications are not externally queryable"));
				}
				return appStatus;
			}
		}

		throw new BizException(ErrorCode.INVALID_PARAMS
			.toError("status", "supported values: draft, published, published_editing"));
	}

}
