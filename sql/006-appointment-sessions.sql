USE xiaozhi_learning;
SET NAMES utf8mb4;

-- 先执行过005目录脚本，再创建本表。
-- 本表暂为配置预览；旧预约仍使用旧排班，不迁移或删除历史数据。
CREATE TABLE IF NOT EXISTS demo_appointment_sessions (
    session_id BIGINT NOT NULL AUTO_INCREMENT,
    hospital_id VARCHAR(64) NOT NULL,
    department VARCHAR(64) NOT NULL,
    visit_date DATE NOT NULL,
    doctor_id VARCHAR(64) NOT NULL,
    slot_id VARCHAR(64) NOT NULL,
    total_capacity INT NOT NULL,
    PRIMARY KEY (session_id),
    UNIQUE KEY uk_demo_session (
        hospital_id, department, visit_date, doctor_id, slot_id
    ),
    CONSTRAINT fk_demo_session_doctor
        FOREIGN KEY (hospital_id, department, doctor_id)
        REFERENCES demo_appointment_doctors (hospital_id, department, doctor_id),
    CONSTRAINT fk_demo_session_slot
        FOREIGN KEY (hospital_id, department, slot_id)
        REFERENCES demo_appointment_time_slots (hospital_id, department, slot_id),
    CONSTRAINT ck_demo_session_capacity CHECK (total_capacity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- 数据由后端启动时补齐。刚建表时为空是正常的。
SELECT COUNT(*) AS configured_sessions FROM demo_appointment_sessions;
