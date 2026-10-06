package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed instants: never depends on the machine's clock or timezone, and never books an appointment. */
class AppointmentBookingPolicyTest {
    private static final Instant OPEN = Instant.parse("2026-10-05T01:30:00Z");
    private static CreateAppointmentRequest request(String date) {
        return new CreateAppointmentRequest("DEMO001", "内科", LocalDate.parse(date));
    }
    private static void status(int code, Runnable action) {
        assertEquals(code, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }

    @Test void draftCanBePreparedBeforeReleaseButConfirmationMustWait() {
        var request = request("2026-10-08");
        Instant before = OPEN.minusNanos(1);
        assertDoesNotThrow(() -> AppointmentBookingPolicy.validateSelection(request, before));
        status(409, () -> AppointmentBookingPolicy.validateConfirmation(request, before));
        assertEquals(OPEN, AppointmentBookingPolicy.releaseAt(request.visitDate()).toInstant());
        assertDoesNotThrow(() -> AppointmentBookingPolicy.validateConfirmation(request, OPEN));
    }

    @Test void previouslyReleasedDateDoesNotCloseAgainBeforeNextMorningReleaseTime() {
        assertDoesNotThrow(() -> AppointmentBookingPolicy.validateConfirmation(
                request("2026-10-08"), Instant.parse("2026-10-06T00:00:00Z")));
    }

    @Test void businessDateChangesAtShanghaiMidnight() {
        assertEquals(LocalDate.parse("2026-10-05"), AppointmentBookingPolicy.businessDate(Instant.parse("2026-10-05T15:59:59Z")));
        assertEquals(LocalDate.parse("2026-10-06"), AppointmentBookingPolicy.businessDate(Instant.parse("2026-10-05T16:00:00Z")));
    }

    @ParameterizedTest @ValueSource(strings = {"2026-10-04", "2026-10-05", "2026-10-09"})
    void rejectsPastTodayAndBeyondWindow(String date) {
        status(400, () -> AppointmentBookingPolicy.validateSelection(request(date), OPEN));
    }

    @ParameterizedTest @ValueSource(strings = {"2026-10-06", "2026-10-07", "2026-10-08"})
    void acceptsOnlyFutureThreeDayWindow(String date) {
        assertDoesNotThrow(() -> AppointmentBookingPolicy.validateConfirmation(request(date), OPEN));
    }

    @Test void rejectsMissingRequestDateAndUnsupportedHospitalDepartment() {
        status(400, () -> AppointmentBookingPolicy.validateSelection(null, OPEN));
        status(400, () -> AppointmentBookingPolicy.validateSelection(new CreateAppointmentRequest("DEMO001", "内科", null), OPEN));
        status(400, () -> AppointmentBookingPolicy.validateSelection(new CreateAppointmentRequest("OTHER", "内科", LocalDate.parse("2026-10-06")), OPEN));
        status(400, () -> AppointmentBookingPolicy.validateSelection(new CreateAppointmentRequest("DEMO001", "外科", LocalDate.parse("2026-10-06")), OPEN));
    }
}
