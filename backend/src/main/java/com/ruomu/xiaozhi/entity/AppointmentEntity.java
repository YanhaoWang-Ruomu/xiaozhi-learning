package com.ruomu.xiaozhi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@TableName("demo_appointments")
public class AppointmentEntity {

    @TableId(value = "appointment_id", type = IdType.INPUT)
    private String appointmentId;

    private String requestId;
    private String status;
    private String hospitalId;
    private String department;
    private LocalDate visitDate;
    private String timeZone;
    private String acceptedAt;
    private String createdAt;
    private String cancelledAt;

    public String getAppointmentId() {
        return appointmentId;
    }

    public void setAppointmentId(String value) {
        this.appointmentId = value;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String value) {
        this.requestId = value;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        this.status = value;
    }

    public String getHospitalId() {
        return hospitalId;
    }

    public void setHospitalId(String value) {
        this.hospitalId = value;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String value) {
        this.department = value;
    }

    public LocalDate getVisitDate() {
        return visitDate;
    }

    public void setVisitDate(LocalDate value) {
        this.visitDate = value;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String value) {
        this.timeZone = value;
    }

    public String getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(String value) {
        this.acceptedAt = value;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String value) {
        this.createdAt = value;
    }

    public String getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(String value) {
        this.cancelledAt = value;
    }

    // 用于迁移时逐字段核对，允许历史记录中的可选字段为 null。
    public List<Object> snapshot() {
        return Arrays.asList(
                appointmentId,
                requestId,
                status,
                hospitalId,
                department,
                visitDate,
                timeZone,
                acceptedAt,
                createdAt,
                cancelledAt
        );
    }
}