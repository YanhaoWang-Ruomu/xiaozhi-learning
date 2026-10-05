package com.ruomu.xiaozhi.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Service
public class AppointmentScheduleMaintenanceService {

    private static final int DEFAULT_CAPACITY = 20;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AppointmentScheduleMaintenanceService(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);

        tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );

        tx.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        tx.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
        tx.setTimeout(15);
    }

    public MaintenanceResult ensureUpcomingSchedules() {
        return ensureAt(Instant.now());
    }

    // 固定时间仅供同包测试使用，
    // 不提供修改业务时间的HTTP接口。
    MaintenanceResult ensureAt(Instant now) {
        LocalDate today = AppointmentBookingPolicy.businessDate(now);
        LocalDate from = today.plusDays(1);
        LocalDate to = today.plusDays(
                AppointmentBookingPolicy.ADVANCE_DAYS
        );

        return Objects.requireNonNull(
                tx.execute(transaction -> {
                    for (int day = 1;
                         day <= AppointmentBookingPolicy.ADVANCE_DAYS;
                         day++) {

                        jdbc.update("""
                                INSERT INTO demo_appointment_schedules
                                    (hospital_id, department,
                                     visit_date, total_capacity)
                                VALUES (?, ?, ?, ?)
                                ON DUPLICATE KEY UPDATE
                                    total_capacity =
                                        demo_appointment_schedules.total_capacity
                                """,
                                AppointmentBookingPolicy.HOSPITAL_ID,
                                AppointmentBookingPolicy.DEPARTMENT,
                                Date.valueOf(today.plusDays(day)),
                                DEFAULT_CAPACITY
                        );
                    }

                    Integer configured = jdbc.queryForObject("""
                            SELECT COUNT(*)
                            FROM demo_appointment_schedules
                            WHERE hospital_id = ?
                              AND department = ?
                              AND visit_date BETWEEN ? AND ?
                            """,
                            Integer.class,
                            AppointmentBookingPolicy.HOSPITAL_ID,
                            AppointmentBookingPolicy.DEPARTMENT,
                            Date.valueOf(from),
                            Date.valueOf(to)
                    );

                    if (configured == null
                            || configured
                            != AppointmentBookingPolicy.ADVANCE_DAYS) {

                        throw new IllegalStateException(
                                "未来演示排班不完整，自动补齐事务已回滚"
                        );
                    }

                    // 表示当前窗口已配置日期数，
                    // 不是本次新增条数。
                    return new MaintenanceResult(
                            today,
                            from,
                            to,
                            configured
                    );
                })
        );
    }

    public record MaintenanceResult(
            LocalDate businessDate,
            LocalDate fromDate,
            LocalDate toDate,
            int configuredDates
    ) {
    }
}