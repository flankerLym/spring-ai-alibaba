package com.alibaba.cloud.ai.studio.core.config;

import com.alibaba.cloud.ai.studio.core.metric.ModelApiMeterRegistrar;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorNettyClientRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.client.RestClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;

import java.net.ConnectException;


@Configuration
@EnableConfigurationProperties(ModelApiHttpClientProperties.class)
public class DashScopeHttpClientConfiguration {

    private final ModelApiHttpClientProperties props;

    public DashScopeHttpClientConfiguration(ModelApiHttpClientProperties props) {
        this.props = props;
    }

    /**
     * 1. 核心：创建自定义 Reactor Netty 连接池
     */
    @Bean(destroyMethod = "dispose") // ⭐ 确保应用关闭时优雅释放连接池资源
    public ConnectionProvider dashScopeConnectionProvider(MeterRegistry meterRegistry) {
        ModelApiHttpClientProperties.Pool pool = props.getPool();
        return ConnectionProvider.builder("dashscope-pool")
                .maxConnections(pool.getMaxConnections())
                .maxIdleTime(pool.getMaxIdleTime())       // < 服务端 keep-alive 超时
                .maxLifeTime(pool.getMaxLifeTime())       // 强制生命周期上限
                .evictInBackground(pool.getEvictInterval())// 定期清理僵死连接
                .pendingAcquireTimeout(pool.getAcquireTimeout())
                .metrics(true, () -> new ModelApiMeterRegistrar(meterRegistry))             // ⭐ 开启 Micrometer 指标监控
                .build();
    }

    /**
     * 1. 核心：创建自定义 Reactor Netty 连接池
     */
    @Bean(destroyMethod = "dispose") // ⭐ 确保应用关闭时优雅释放连接池资源
    public ConnectionProvider deepSeekConnectionProvider(MeterRegistry meterRegistry) {
        ModelApiHttpClientProperties.Pool pool = props.getPool();
        return ConnectionProvider.builder("deepseek-pool")
                .maxConnections(pool.getMaxConnections())
                .maxIdleTime(pool.getMaxIdleTime())       // < 服务端 keep-alive 超时
                .maxLifeTime(pool.getMaxLifeTime())       // 强制生命周期上限
                .evictInBackground(pool.getEvictInterval())// 定期清理僵死连接
                .pendingAcquireTimeout(pool.getAcquireTimeout())
                .metrics(true, () -> new ModelApiMeterRegistrar(meterRegistry))             // ⭐ 开启 Micrometer 指标监控
                .build();
    }


    /**
     * 2. 创建共享的 Reactor Netty HttpClient 实例
     */
    @Bean
    public HttpClient deepSeekHttpClient(ConnectionProvider deepSeekConnectionProvider) {
        ModelApiHttpClientProperties.Timeout timeout = props.getTimeout();
        return HttpClient.create(deepSeekConnectionProvider)
                .responseTimeout(timeout.getResponse())  // 响应超时
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) timeout.getConnect().toMillis()); // 连接超时
    }

    /**
     * 2. 创建共享的 Reactor Netty HttpClient 实例
     */
    @Bean
    public HttpClient dashScopeHttpClient(ConnectionProvider dashScopeConnectionProvider) {
        ModelApiHttpClientProperties.Timeout timeout = props.getTimeout();
        return HttpClient.create(dashScopeConnectionProvider)
                .responseTimeout(timeout.getResponse())  // 响应超时
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) timeout.getConnect().toMillis()); // 连接超时
    }


    /**
     * 3. 覆盖全局 WebClient.Builder (供 Spring AI SDK clone 使用)
     */
    @Bean
    public WebClient.Builder dashScopeWebClientBuilder(HttpClient dashScopeHttpClient) {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(dashScopeHttpClient))
                .filter(retryFilter()); // 注入重试逻辑
    }

    /**
     * 4. 覆盖全局 RestClient.Builder (强制使用 Reactor Netty 底层，共享连接池)
     */
    @Bean
    public RestClient.Builder dashScopeRestClientBuilder(HttpClient dashScopeHttpClient) {
        // ⭐ 强制 RestClient 使用 Reactor Netty 作为底层引擎，避免使用 JDK HttpClient 导致连接池分裂
        ReactorNettyClientRequestFactory requestFactory = new ReactorNettyClientRequestFactory(dashScopeHttpClient);
        return RestClient.builder()
                .requestFactory(requestFactory);
    }

    /**
     * 3. 覆盖全局 WebClient.Builder (供 Spring AI SDK clone 使用)
     */
    @Bean
    public WebClient.Builder deepSeekWebClientBuilder(HttpClient deepSeekHttpClient) {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(deepSeekHttpClient))
                .filter(retryFilter()); // 注入重试逻辑
    }

    /**
     * 4. 覆盖全局 RestClient.Builder (强制使用 Reactor Netty 底层，共享连接池)
     */
    @Bean
    public RestClient.Builder deepSeekRestClientBuilder(HttpClient deepSeekHttpClient) {
        // ⭐ 强制 RestClient 使用 Reactor Netty 作为底层引擎，避免使用 JDK HttpClient 导致连接池分裂
        ReactorNettyClientRequestFactory requestFactory = new ReactorNettyClientRequestFactory(deepSeekHttpClient);
        return RestClient.builder()
                .requestFactory(requestFactory);
    }


    /**
     * 5. 应用层重试过滤器 (针对 WebClient)
     */
    private ExchangeFilterFunction retryFilter() {
        ModelApiHttpClientProperties.Retry retryProps = props.getRetry();
        return (request, next) -> next.exchange(request)
                .retryWhen(Retry.backoff(retryProps.getMaxAttempts(), retryProps.getBackoffDelay())
                        //.multiplier(retryProps.getBackoffMultiplier())
                        .filter(throwable -> {
                            // ⭐ 仅针对网络抖动和 Connection Reset 进行重试
                            String msg = throwable.getMessage();
                            return throwable instanceof ConnectException ||
                                    (msg != null && msg.toLowerCase().contains("connection reset"));
                        })
                        .doBeforeRetry(signal ->
                                System.out.println("Retrying DashScope request due to: " + signal.failure().getMessage()))
                );
    }
}