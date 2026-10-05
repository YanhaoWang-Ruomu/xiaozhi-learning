package com.ruomu.xiaozhi.config;

import com.ruomu.xiaozhi.service.AppointmentSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AppointmentSessionMaintenanceTask implements ApplicationRunner {
    private static final Logger log =
            LoggerFactory.getLogger(AppointmentSessionMaintenanceTask.class);
    private final AppointmentSessionService sessionService;
    private volatile boolean ready;
    private AppointmentSessionService.MaintenanceResult lastResult;

    public AppointmentSessionMaintenanceTask(AppointmentSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public synchronized void run(ApplicationArguments args) {
        // 首次初始化失败会阻止正常启动，避免误报配置已就绪。
        refresh();
        ready = true;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public synchronized void maintain() {
        if (!ready) return;
        try {
            refresh();
        } catch (RuntimeException ex) {
            log.error("DEMO_SESSION_REFRESH_FAILED，下次定时检查将重试", ex);
        }
    }

    private void refresh() {
        var result = sessionService.ensureUpcomingSessions();
        if (!result.equals(lastResult)) {
            log.info("DEMO_SESSION_CONFIG_READY businessDate={} from={} to={} "
                            + "configuredSessions={} bookingEnabled=false",
                    result.businessDate(), result.fromDate(), result.toDate(),
                    result.configuredSessions());
            lastResult = result;
        }
    }
}
