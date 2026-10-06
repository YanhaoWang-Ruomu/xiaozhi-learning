package com.ruomu.xiaozhi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class XiaozhiApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(XiaozhiApplication.class);
        var context = application.run(args);
        // One-off offline migration must release scheduled tasks and database pools.
        if (application.getWebApplicationType() == org.springframework.boot.WebApplicationType.NONE
                && context.getEnvironment().getProperty(
                    "xiaozhi.appointment.import-from-mongo", Boolean.class, false)) {
            context.close();
        }
    }
}
