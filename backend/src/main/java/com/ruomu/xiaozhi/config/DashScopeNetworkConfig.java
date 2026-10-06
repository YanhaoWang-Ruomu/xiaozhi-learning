package com.ruomu.xiaozhi.config;

import com.alibaba.dashscope.protocol.ConnectionConfigurations;
import com.alibaba.dashscope.utils.Constants;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Configure the SDK singleton before any chat or embedding client is constructed. */
@Configuration(proxyBeanMethods = false)
public class DashScopeNetworkConfig {
    private static final Logger log = LoggerFactory.getLogger(DashScopeNetworkConfig.class);

    @Bean
    public static BeanFactoryPostProcessor dashScopeNetworkPolicy(Environment environment) {
        int connect = seconds(environment, "connect", 10, 30);
        int read = seconds(environment, "read", 60, 120);
        int write = seconds(environment, "write", 20, 60);
        return beanFactory -> {
            Constants.connectionConfigurations = ConnectionConfigurations.builder()
                .connectTimeout(Duration.ofSeconds(connect))
                .readTimeout(Duration.ofSeconds(read))
                .writeTimeout(Duration.ofSeconds(write))
                .build();
            log.info("DASHSCOPE_NETWORK connectSeconds={} readIdleSeconds={} writeSeconds={}",
                connect, read, write);
        };
    }

    private static int seconds(Environment environment, String kind, int fallback, int maximum) {
        String key = "xiaozhi.ai.network." + kind + "-timeout-seconds";
        int value = environment.getProperty(key, Integer.class, fallback);
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(key + " must be between 1 and " + maximum);
        }
        return value;
    }
}
