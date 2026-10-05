USE xiaozhi_learning;

CREATE TABLE IF NOT EXISTS demo_appointment_attempts (
                                                         request_id VARCHAR(128) NOT NULL,
    hospital_id VARCHAR(64) NOT NULL,
    department VARCHAR(64) NOT NULL,
    visit_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(255) NOT NULL DEFAULT '',

    PRIMARY KEY (request_id),

    CONSTRAINT ck_demo_attempt_status
    CHECK (status IN ('PROCESSING', 'CREATED', 'REJECTED'))
    ) ENGINE=InnoDB
    DEFAULT CHARSET=utf8mb4
    COLLATE=utf8mb4_0900_bin;