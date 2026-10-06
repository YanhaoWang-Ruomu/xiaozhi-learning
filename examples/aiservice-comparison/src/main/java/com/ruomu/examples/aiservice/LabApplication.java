package com.ruomu.examples.aiservice;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@SpringBootApplication
public class LabApplication {
    public static void main(String[] args) {
        try (var context = new SpringApplicationBuilder(LabApplication.class)
                .web(WebApplicationType.NONE).run(args)) {
            var manual = context.getBean(ManualAssistant.class);
            var declarative = context.getBean(DeclarativeAssistant.class);
            String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
            for (String input : List.of("round-1", "round-2", "查询演示规则")) {
                String left = manual.chat("comparison-a", input, date);
                String right = declarative.chat("comparison-a", input, date);
                if (!left.equals(right)) throw new IllegalStateException("两种装配结果不一致");
                System.out.println("MANUAL      " + left);
                System.out.println("DECLARATIVE " + right);
            }
            String isolated = declarative.chat("comparison-b", "new-conversation", date);
            if (!isolated.endsWith("USER_MESSAGES=1")) throw new IllegalStateException("会话发生串话");
            System.out.println("ISOLATED    " + isolated);
            System.out.println("AISERVICE_COMPARISON_OK");
        }
    }
}
