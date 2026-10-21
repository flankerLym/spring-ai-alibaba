package com.alibaba.cloud.ai.studio.admin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/** Loads the optional DingTalk workflow-failure notification settings. */
@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:dingtalk-notification.properties")
public class DingTalkNotificationConfiguration {
}
