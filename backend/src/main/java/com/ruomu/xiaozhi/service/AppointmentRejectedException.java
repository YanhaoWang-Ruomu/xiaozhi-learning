package com.ruomu.xiaozhi.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// 仅表示 MySQL 已提交的、可以安全关闭草稿的业务拒绝。
public class AppointmentRejectedException
        extends ResponseStatusException {

    private static final long serialVersionUID = 1L;

    public AppointmentRejectedException(String reason) {
        super(HttpStatus.CONFLICT, reason);
    }
}