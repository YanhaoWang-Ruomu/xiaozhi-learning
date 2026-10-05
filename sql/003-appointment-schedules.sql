USE xiaozhi_learning;

CREATE TABLE IF NOT EXISTS demo_appointment_schedules (
                                                          hospital_id VARCHAR(64) NOT NULL,
    department VARCHAR(64) NOT NULL,
    visit_date DATE NOT NULL,
    total_capacity INT NOT NULL,

    PRIMARY KEY (hospital_id, department, visit_date),

    CONSTRAINT ck_demo_schedule_capacity
    CHECK (total_capacity >= 0)
    ) ENGINE=InnoDB
    DEFAULT CHARSET=utf8mb4
    COLLATE=utf8mb4_0900_bin;

-- 根据上海时间计算业务日期。
SET @business_date = DATE(UTC_TIMESTAMP() + INTERVAL 8 HOUR);

-- 创建未来三天的本地虚构排班，每天20个演示名额。
-- 日期已经存在时，保留原来的总名额。
INSERT INTO demo_appointment_schedules (
    hospital_id,
    department,
    visit_date,
    total_capacity
)
VALUES
    (
        'DEMO001',
        '内科',
        DATE_ADD(@business_date, INTERVAL 1 DAY),
        20
    ),
    (
        'DEMO001',
        '内科',
        DATE_ADD(@business_date, INTERVAL 2 DAY),
        20
    ),
    (
        'DEMO001',
        '内科',
        DATE_ADD(@business_date, INTERVAL 3 DAY),
        20
    )
    ON DUPLICATE KEY UPDATE
                         total_capacity = demo_appointment_schedules.total_capacity;

SELECT *
FROM demo_appointment_schedules
WHERE visit_date BETWEEN DATE_ADD(@business_date, INTERVAL 1 DAY)
          AND DATE_ADD(@business_date, INTERVAL 3 DAY)
ORDER BY hospital_id, department, visit_date;