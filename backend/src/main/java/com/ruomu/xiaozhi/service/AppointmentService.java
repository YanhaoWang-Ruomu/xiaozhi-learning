package com.ruomu.xiaozhi.service;

import com.mongodb.client.MongoCollection;
import com.ruomu.xiaozhi.dto.AppointmentResponse;
import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;

@Service
public class AppointmentService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String DEMO_NOTICE =
            "仅创建本地演示预约记录，不代表真实医院挂号成功。";

    private final MongoCollection<Document> collection;

    public AppointmentService(MongoTemplate mongoTemplate) {
        this.collection =
                mongoTemplate.getCollection("demo_appointments");
    }

    public AppointmentResponse create(
            CreateAppointmentRequest request) {

        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "请求内容不能为空"
            );
        }

        String hospitalId = request.hospitalId() == null
                ? ""
                : request.hospitalId().strip();

        String department = request.department() == null
                ? ""
                : request.department().strip();

        if (!"DEMO001".equals(hospitalId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "当前仅支持演示医院 DEMO001"
            );
        }

        if (!"内科".equals(department)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "当前演示仅支持内科"
            );
        }

        LocalDate visitDate = request.visitDate();
        LocalDate today = LocalDate.now(ZONE);

        if (visitDate == null
                || !visitDate.isAfter(today)
                || visitDate.isAfter(today.plusDays(3))) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "日期必须为上海时区的明天至未来第3天"
            );
        }

        String appointmentId = "DEMO-" + UUID.randomUUID();

        Document document = new Document("_id", appointmentId)
                .append("status", "DEMO_CREATED")
                .append("hospitalId", hospitalId)
                .append("department", department)
                .append("visitDate", visitDate.toString())
                .append("timeZone", ZONE.getId())
                .append("createdAt", Instant.now().toString());

        collection.insertOne(document);

        return toResponse(document);
    }

    public AppointmentResponse findById(String appointmentId) {
        Document document = collection
                .find(eq("_id", appointmentId))
                .first();

        if (document == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "未找到该演示预约记录"
            );
        }

        return toResponse(document);
    }

    private AppointmentResponse toResponse(Document document) {
        return new AppointmentResponse(
                document.getString("_id"),
                document.getString("status"),
                document.getString("hospitalId"),
                document.getString("department"),
                LocalDate.parse(document.getString("visitDate")),
                document.getString("timeZone"),
                DEMO_NOTICE
        );
    }
}