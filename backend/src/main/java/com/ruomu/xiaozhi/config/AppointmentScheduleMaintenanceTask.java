package com.ruomu.xiaozhi.config;

import com.ruomu.xiaozhi.service.AppointmentScheduleMaintenanceService;
import com.ruomu.xiaozhi.service.AppointmentScheduleMaintenanceService.MaintenanceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class AppointmentScheduleMaintenanceTask
        implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(
            AppointmentScheduleMaintenanceTask.class
    );

    private final AppointmentScheduleMaintenanceService service;

    private volatile boolean ready;
    private volatile LocalDate lastLoggedDate;

    public AppointmentScheduleMaintenanceTask(
            AppointmentScheduleMaintenanceService service) {

        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 启动补齐失败则让启动失败，
        // 避免误报准备完成。
        MaintenanceResult result = service.ensureUpcomingSchedules();

        logReady(result, "STARTUP");
        lastLoggedDate = result.businessDate();
        ready = true;
    }

    @Scheduled(
            initialDelay = 60000,
            fixedDelay = 60000
    )
    public void maintain() {
        if (!ready) {
            return;
        }

        try {
            MaintenanceResult result =
                    service.ensureUpcomingSchedules();

            if (!result.businessDate().equals(lastLoggedDate)) {
                logReady(result, "DATE_CHANGE");
                lastLoggedDate = result.businessDate();
            }
        } catch (Exception exception) {
            log.error(
                    "DEMO_SCHEDULE_MAINTENANCE_FAILED："
                            + "本轮补齐失败，下轮自动重试",
                    exception
            );
        }
    }

    private void logReady(
            MaintenanceResult result,
            String trigger) {

        log.info(
                "DEMO_SCHEDULE_READY trigger={} "
                        + "businessDate={} from={} to={} configuredDates={}",
                trigger,
                result.businessDate(),
                result.fromDate(),
                result.toDate(),
                result.configuredDates()
        );
    }
}