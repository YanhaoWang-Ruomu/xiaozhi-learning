USE xiaozhi_learning;

CREATE TABLE IF NOT EXISTS demo_appointments (
                                                 appointment_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    hospital_id VARCHAR(64) NOT NULL,
    department VARCHAR(64) NOT NULL,
    visit_date DATE NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    accepted_at VARCHAR(40) NULL,
    created_at VARCHAR(40) NOT NULL,
    cancelled_at VARCHAR(40) NULL,
    PRIMARY KEY (appointment_id),
    UNIQUE KEY uk_demo_appointments_request_id (request_id),
    CONSTRAINT ck_demo_appointments_status CHECK (
(status = 'DEMO_CREATED' AND cancelled_at IS NULL)
    OR (status = 'DEMO_CANCELLED' AND cancelled_at IS NOT NULL)
    )
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 修改已经按初版脚本创建的表。
-- 保留已有数据和唯一索引，允许历史记录的 request_id 为 NULL。
ALTER TABLE demo_appointments
    MODIFY COLUMN request_id VARCHAR(128) NULL;

CREATE TABLE IF NOT EXISTS app_data_migrations (
                                                   migration_key VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    imported_count BIGINT NOT NULL DEFAULT 0,
    completed_at VARCHAR(40) NULL,
    PRIMARY KEY (migration_key),
    CONSTRAINT ck_app_data_migrations_status
    CHECK (status IN ('PENDING', 'COMPLETED'))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

INSERT INTO app_data_migrations (migration_key, status)
SELECT 'mongo-demo-appointments-v1', 'PENDING'
    WHERE NOT EXISTS (
    SELECT 1 FROM app_data_migrations
    WHERE migration_key = 'mongo-demo-appointments-v1'
);