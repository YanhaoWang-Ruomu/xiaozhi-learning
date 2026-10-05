package com.ruomu.xiaozhi.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

public final class BusinessDateContext {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private BusinessDateContext() {
    }

    // 每次调用重新读取时间，不在应用启动时缓存日期。
    public static String now() {
        return from(Clock.systemUTC());
    }

    // 可传入固定时钟测试跨日、跨月和跨年。
    public static String from(Clock clock) {
        Objects.requireNonNull(clock, "clock 不能为空");
        LocalDate today = LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);

        // 只读取一次时钟，避免午夜前后各行使用不同的基准日期。
        return "业务时区：" + BUSINESS_ZONE.getId() + "\n"
                + "今天：" + today + "\n"
                + "明天：" + today.plusDays(1) + "\n"
                + "后天：" + today.plusDays(2) + "\n"
                + "大后天：" + today.plusDays(3);
    }
}