package com.alibaba.cloud.ai.studio.domain.service;

import com.alibaba.cloud.ai.studio.runtime.enums.ErrorCode;
import com.alibaba.cloud.ai.studio.runtime.exception.BizException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

/** 会话领域规则：校验标识、分页、消息数量和时间区间；不访问数据库或 HTTP。 */
@Component
public class ConversationQueryRules {
    private static final int MAX_PAGE_SIZE = 100;

    public int page(Integer num) {
        int page = num == null ? 1 : num;
        if (page < 1) throw invalid("pageNum", "must be >= 1");
        return page;
    }

    public int size(Integer num) {
        int size = num == null ? 20 : num;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("pageSize", "must be between 1 and " + MAX_PAGE_SIZE);
        }
        return size;
    }

    public void checkCountRange(Integer min, Integer max) {
        if ((min != null && min < 0) || (max != null && max < 0)
                || (min != null && max != null && min > max)) {
            throw invalid("messageCount", "invalid message count range");
        }
    }

    public void checkTimeRange(Date start, Date end) {
        if (start != null && end != null && start.after(end)) {
            throw invalid("startTime", "startTime must not be after endTime");
        }
    }

    public Date parseTime(String text, String field) {
        if (StringUtils.isBlank(text)) return null;
        String value = text.trim();
        try { return Date.from(Instant.parse(value)); }
        catch (DateTimeParseException ignored) { }
        try { return Date.from(OffsetDateTime.parse(value).toInstant()); }
        catch (DateTimeParseException ignored) { }
        try {
            LocalDateTime time = value.contains("T") ? LocalDateTime.parse(value)
                    : LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return Date.from(time.atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException ex) {
            throw invalid(field, "use ISO-8601 or yyyy-MM-dd HH:mm:ss");
        }
    }

    public Long numericId(String value, String field) {
        if (StringUtils.isBlank(value)) throw invalid(field, "must be a numeric bigint ID");
        try {
            long id = Long.parseLong(value.trim());
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException ex) {
            throw invalid(field, "must be a positive numeric bigint ID");
        }
    }

    public BizException invalid(String field, String reason) {
        return new BizException(ErrorCode.INVALID_PARAMS.toError(field, reason));
    }
}
