package com.ruomu.xiaozhi.security;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AccountSecurityConfigTest {
    @Test
    void offlineContextStartsWithoutHttpSecurityAndRetainsPasswordEncoder() {
        new ApplicationContextRunner()
            .withUserConfiguration(AccountSecurityConfig.class)
            .withBean(AccountService.class, () -> mock(AccountService.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
                assertThat(context).hasSingleBean(PasswordEncoder.class);
                assertThat(context.getBean(PasswordEncoder.class).matches(
                    "offline-test-password", context.getBean(PasswordEncoder.class).encode("offline-test-password")))
                    .isTrue();
            });
    }

    @Test
    void servletContextRetainsAuthenticationAndCsrfProtection() {
        new WebApplicationContextRunner()
            .withUserConfiguration(AccountSecurityConfig.class, WebSecurity.class)
            .withBean(AccountService.class, () -> mock(AccountService.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(SecurityFilterChain.class);
                Filter filter = context.getBean("springSecurityFilterChain", Filter.class);

                var get = new MockHttpServletRequest("GET", "/api/conversations");
                get.setServletPath("/api/conversations");
                var getResponse = new MockHttpServletResponse();
                filter.doFilter(get, getResponse, new MockFilterChain());
                assertThat(getResponse.getStatus()).isEqualTo(401);

                var post = new MockHttpServletRequest("POST", "/api/auth/register");
                post.setServletPath("/api/auth/register");
                var postResponse = new MockHttpServletResponse();
                filter.doFilter(post, postResponse, new MockFilterChain());
                assertThat(postResponse.getStatus()).isEqualTo(403);
            });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class WebSecurity {}
}
