package com.ruomu.xiaozhi.config;

import com.alibaba.dashscope.protocol.ConnectionConfigurations;
import com.alibaba.dashscope.utils.Constants;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class DashScopeNetworkConfigTest {
    private final ConnectionConfigurations original = Constants.connectionConfigurations;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(DashScopeNetworkConfig.class);

    @AfterEach void restore() { Constants.connectionConfigurations = original; }

    @Test void configuresSdkBeforeClientsExist() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var options = Constants.connectionConfigurations;
            assertThat(options.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(options.getReadTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(options.getWriteTimeout()).isEqualTo(Duration.ofSeconds(20));
        });
    }
    @Test void acceptsProjectOverrides() {
        runner.withPropertyValues("xiaozhi.ai.network.connect-timeout-seconds=5",
            "xiaozhi.ai.network.read-timeout-seconds=45", "xiaozhi.ai.network.write-timeout-seconds=15")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(Constants.connectionConfigurations.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
                assertThat(Constants.connectionConfigurations.getReadTimeout()).isEqualTo(Duration.ofSeconds(45));
                assertThat(Constants.connectionConfigurations.getWriteTimeout()).isEqualTo(Duration.ofSeconds(15));
            });
    }
    @Test void rejectsUnlimitedOrOversizedTimeouts() {
        for (String value : new String[]{"0", "-1", "121", "bad"}) {
            runner.withPropertyValues("xiaozhi.ai.network.read-timeout-seconds=" + value)
                .run(context -> assertThat(context).hasFailed());
        }
    }
}
