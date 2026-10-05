USE xiaozhi_learning;
SET NAMES utf8mb4;

-- 本文件只增加目录，不修改已有排班、预约或草稿。
CREATE TABLE IF NOT EXISTS demo_appointment_doctors (
                                                        hospital_id VARCHAR(64) NOT NULL,
    department VARCHAR(64) NOT NULL,
    doctor_id VARCHAR(64) NOT NULL,
    doctor_name VARCHAR(64) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,

    PRIMARY KEY (hospital_id, department, doctor_id),

    CONSTRAINT ck_demo_doctor_enabled
    CHECK (enabled IN (0, 1))
    ) ENGINE=InnoDB
    DEFAULT CHARSET=utf8mb4
    COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS demo_appointment_time_slots (
                                                           hospital_id VARCHAR(64) NOT NULL,
    department VARCHAR(64) NOT NULL,
    slot_id VARCHAR(64) NOT NULL,
    slot_name VARCHAR(64) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,

    PRIMARY KEY (hospital_id, department, slot_id),

    CONSTRAINT ck_demo_slot_enabled
    CHECK (enabled IN (0, 1)),

    CONSTRAINT ck_demo_slot_time CHECK (
                                           start_time >= '00:00:00'
                                           AND start_time < end_time
                                           AND end_time <= '23:59:59'
                                       )
    ) ENGINE=InnoDB
    DEFAULT CHARSET=utf8mb4
    COLLATE=utf8mb4_0900_bin;

START TRANSACTION;

-- 以下人员和时间均为虚构教学资料。
-- 重复执行时保留已有名称、启停状态和排序，不覆盖手工调整。
INSERT INTO demo_appointment_doctors (
    hospital_id,
    department,
    doctor_id,
    doctor_name,
    enabled,
    sort_order
)
VALUES
    ('DEMO001', '内科', 'DOC001', '演示医生甲', 1, 10),
    ('DEMO001', '内科', 'DOC002', '演示医生乙', 1, 20)
    ON DUPLICATE KEY UPDATE
                         doctor_id = demo_appointment_doctors.doctor_id;

INSERT INTO demo_appointment_time_slots (
    hospital_id,
    department,
    slot_id,
    slot_name,
    start_time,
    end_time,
    enabled,
    sort_order
)
VALUES
    (
        'DEMO001', '内科', 'AM', '上午',
        '08:00:00', '12:00:00', 1, 10
    ),
    (
        'DEMO001', '内科', 'PM', '下午',
        '14:00:00', '17:00:00', 1, 20
    )
    ON DUPLICATE KEY UPDATE
                         slot_id = demo_appointment_time_slots.slot_id;

COMMIT;

SELECT
    hospital_id,
    department,
    doctor_id,
    doctor_name,
    enabled
FROM demo_appointment_doctors
WHERE hospital_id = 'DEMO001'
  AND department = '内科'
ORDER BY sort_order, doctor_id;

SELECT
    hospital_id,
    department,
    slot_id,
    slot_name,
    start_time,
    end_time,
    enabled
FROM demo_appointment_time_slots
WHERE hospital_id = 'DEMO001'
  AND department = '内科'
ORDER BY sort_order, slot_id;