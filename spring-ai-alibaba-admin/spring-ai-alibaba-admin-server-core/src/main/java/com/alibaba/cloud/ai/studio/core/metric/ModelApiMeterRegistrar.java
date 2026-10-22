package com.alibaba.cloud.ai.studio.core.metric;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.netty.resources.ConnectionPoolMetrics;
import reactor.netty.resources.ConnectionProvider;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.ToDoubleFunction;

public class ModelApiMeterRegistrar implements ConnectionProvider.MeterRegistrar {
    private final MeterRegistry meterRegistry;

    public ModelApiMeterRegistrar(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void registerMetrics(String poolName, String id, SocketAddress remoteAddress,
                                ConnectionPoolMetrics metrics) {
        // ⭐ 核心防御：安全提取地址字符串，绝对避免阻塞式 DNS 反查
        String host = safeExtractHost(remoteAddress);
        String fixedUri = "/dashscope/api";

        Gauge.builder("dashscope.pool.active.connections",
                        metrics, ConnectionPoolMetrics::acquiredSize)
                .tag("pool.name", poolName)
                .tag("remote.address", host)
                .tag("uri", fixedUri)
                .register(meterRegistry);

        Gauge.builder("dashscope.pool.idle.connections",
                        metrics, ConnectionPoolMetrics::idleSize)
                .tag("pool.name", poolName)
                .tag("remote.address", host)
                .tag("uri", fixedUri)
                .register(meterRegistry);

        Gauge.builder("dashscope.pool.pending.requests",
                        metrics, ConnectionPoolMetrics::pendingAcquireSize)
                .tag("pool.name", poolName)
                .tag("remote.address", host)
                .tag("uri", fixedUri)
                .register(meterRegistry);
    }

    @Override
    public void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) {
        String host = safeExtractHost(remoteAddress);
        meterRegistry.forEachMeter(meter -> {
            if (meter.getId().getName().startsWith("dashscope.pool.") &&
                    poolName.equals(meter.getId().getTag("pool.name")) &&
                    host.equals(meter.getId().getTag("remote.address"))) {
                meterRegistry.remove(meter);
            }
        });
    }

    /**
     * ⭐ 防御性地址提取工具方法
     * 1. 优先使用 getHostString() 获取 IP/主机名（不触发反向 DNS）
     * 2. 降级使用 getAddress().getHostAddress() 获取纯 IP
     * 3. 兜底返回 "unknown" 防止 NPE 或 ClassCastException
     */
    private String safeExtractHost(SocketAddress address) {
        if (address instanceof InetSocketAddress inetAddr) {
            // getHostString() 如果内部已有主机名则返回主机名，否则返回 IP，不会触发反向解析
            String hostString = inetAddr.getHostString();
            return hostString != null ? hostString : inetAddr.getAddress().getHostAddress();
        }
        // 兼容 UnixDomainSocketAddress 等非 IP 地址类型（JDK 16+ / Netty 扩展）
        return address != null ? address.toString() : "unknown";
    }
}