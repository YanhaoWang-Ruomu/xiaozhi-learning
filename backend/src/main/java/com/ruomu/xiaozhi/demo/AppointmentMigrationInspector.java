package com.ruomu.xiaozhi.demo;

import com.mongodb.client.MongoClients;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;

public class AppointmentMigrationInspector {

    public static void main(String[] args) {
        String uri =
                "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000";

        try (var client = MongoClients.create(uri)) {
            var database = client.getDatabase("xiaozhi_learning");

            var appointments = database.getCollection(
                    "demo_appointments"
            );

            var drafts = database.getCollection(
                    "demo_appointment_drafts"
            );

            System.out.println("=== 只读检查开始，不修改任何数据 ===");

            int count = 0;

            try (var cursor = appointments.find().iterator()) {
                while (cursor.hasNext()) {
                    Document appointment = cursor.next();
                    Object requestId = appointment.get("requestId");

                    if (requestId instanceof String text
                            && !text.isBlank()) {
                        continue;
                    }

                    count++;

                    // 最多展示 10 条样本，仍统计所有异常记录。
                    if (count > 10) {
                        continue;
                    }

                    Document sample = new Document(
                            "fieldNames",
                            new ArrayList<>(appointment.keySet())
                    );

                    for (String key : List.of(
                            "_id",
                            "requestId",
                            "idempotencyKey",
                            "draftId",
                            "status",
                            "hospitalId",
                            "department",
                            "visitDate",
                            "timeZone",
                            "acceptedAt",
                            "createdAt",
                            "cancelledAt")) {

                        if (appointment.containsKey(key)) {
                            sample.append(key, appointment.get(key));
                        }
                    }

                    System.out.println(
                            "APPOINTMENT_SAMPLE=" + sample.toJson()
                    );

                    try (var related = drafts.find(
                            eq("appointmentId", appointment.get("_id"))
                    ).limit(10).iterator()) {

                        int relatedCount = 0;

                        while (related.hasNext()) {
                            Document draft = related.next();
                            Document summary = new Document();

                            for (String key : List.of(
                                    "_id",
                                    "appointmentId",
                                    "status",
                                    "hospitalId",
                                    "department",
                                    "visitDate",
                                    "confirmationStartedAt",
                                    "confirmedAt")) {

                                if (draft.containsKey(key)) {
                                    summary.append(key, draft.get(key));
                                }
                            }

                            System.out.println(
                                    "LINKED_DRAFT=" + summary.toJson()
                            );

                            relatedCount++;
                        }

                        if (relatedCount == 0) {
                            System.out.println(
                                    "LINKED_DRAFT=未找到直接关联草稿"
                            );
                        }
                    }
                }
            }

            System.out.println("INVALID_REQUEST_ID_COUNT=" + count);
            System.out.println("=== 只读检查结束 ===");
        }
    }
}