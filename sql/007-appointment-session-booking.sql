USE xiaozhi_learning;
SET NAMES utf8mb4;

-- 请停止所有后端实例后执行。原预约表和原确认记录不删除、不改写。
-- NULL表示历史/按日期预约；场次目标也需要参与幂等内容校验。
CREATE TABLE IF NOT EXISTS demo_appointment_request_targets (
                                                                request_id VARCHAR(128) NOT NULL,
    session_id BIGINT NULL,
    PRIMARY KEY (request_id),
    CONSTRAINT fk_demo_target_attempt FOREIGN KEY (request_id)
    REFERENCES demo_appointment_attempts (request_id),
    CONSTRAINT ck_demo_target_session CHECK (session_id IS NULL OR session_id > 0)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS demo_appointment_session_bookings (
                                                                 appointment_id VARCHAR(64) NOT NULL,
    session_id BIGINT NOT NULL,
    doctor_id VARCHAR(64) NOT NULL,
    doctor_name VARCHAR(64) NOT NULL,
    slot_id VARCHAR(64) NOT NULL,
    slot_name VARCHAR(64) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    PRIMARY KEY (appointment_id),
    KEY ix_demo_booking_session (session_id),
    CONSTRAINT fk_demo_booking_appointment FOREIGN KEY (appointment_id)
    REFERENCES demo_appointments (appointment_id),
    CONSTRAINT fk_demo_booking_session FOREIGN KEY (session_id)
    REFERENCES demo_appointment_sessions (session_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 已有确认尝试全部属于旧模式。再次执行时不覆盖已经绑定的新场次。
INSERT INTO demo_appointment_request_targets (request_id, session_id)
SELECT a.request_id, NULL
FROM demo_appointment_attempts a
WHERE NOT EXISTS (
    SELECT 1 FROM demo_appointment_request_targets t
    WHERE t.request_id = a.request_id
);

SELECT COUNT(*) AS target_rows FROM demo_appointment_request_targets;
SELECT COUNT(*) AS session_booking_rows FROM demo_appointment_session_bookings;