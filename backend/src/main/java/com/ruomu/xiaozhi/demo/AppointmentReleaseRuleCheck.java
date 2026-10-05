package com.ruomu.xiaozhi.demo;

import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import com.ruomu.xiaozhi.service.AppointmentBookingPolicy;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;

public class AppointmentReleaseRuleCheck {

    public static void main(String[] args) {
        CreateAppointmentRequest request = request("2026-10-08");

        Instant before = Instant.parse("2026-10-05T01:29:59Z");
        Instant exact = Instant.parse("2026-10-05T01:30:00Z");

        check(
                AppointmentBookingPolicy.releaseAt(request.visitDate())
                        .toInstant()
                        .equals(exact),
                "就诊日前3天的上海09:30放号"
        );

        AppointmentBookingPolicy.validateSelection(request, before);
        System.out.println("PASS 未放号时允许准备草稿");

        expectStatus(
                409,
                () -> AppointmentBookingPolicy.validateConfirmation(
                        request,
                        before
                )
        );
        System.out.println("PASS 09:29:59拒绝确认");

        AppointmentBookingPolicy.validateConfirmation(request, exact);
        System.out.println("PASS 09:30:00允许进入名额检查");

        AppointmentBookingPolicy.validateConfirmation(
                request,
                Instant.parse("2026-10-06T00:00:00Z")
        );
        System.out.println("PASS 次日上海08:00不会重新锁住已放号日期");

        expectStatus(
                400,
                () -> AppointmentBookingPolicy.validateConfirmation(
                        request("2026-10-05"),
                        exact
                )
        );

        expectStatus(
                400,
                () -> AppointmentBookingPolicy.validateConfirmation(
                        request("2026-10-09"),
                        exact
                )
        );

        System.out.println("PASS 当天预约和超出3天范围均被拒绝");

        check(
                AppointmentBookingPolicy.businessDate(
                        Instant.parse("2026-10-05T15:59:59Z")
                ).equals(LocalDate.parse("2026-10-05")),
                "上海午夜前的业务日期"
        );

        check(
                AppointmentBookingPolicy.businessDate(
                        Instant.parse("2026-10-05T16:00:00Z")
                ).equals(LocalDate.parse("2026-10-06")),
                "上海午夜后的业务日期"
        );

        System.out.println("RELEASE_RULE_CHECK_PASSED");
    }

    private static CreateAppointmentRequest request(String date) {
        return new CreateAppointmentRequest(
                "DEMO001",
                "内科",
                LocalDate.parse(date)
        );
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("FAIL " + message);
        }

        System.out.println("PASS " + message);
    }

    private static void expectStatus(int status, Runnable action) {
        try {
            action.run();
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == status) {
                return;
            }

            throw exception;
        }

        throw new IllegalStateException("预期被拒绝，实际通过");
    }
}