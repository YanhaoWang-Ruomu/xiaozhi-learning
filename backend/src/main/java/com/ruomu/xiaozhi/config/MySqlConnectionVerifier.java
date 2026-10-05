package com.ruomu.xiaozhi.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.ruomu.xiaozhi.mapper.DatabaseCheckMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(
        name = "xiaozhi.mysql.verify-on-startup",
        havingValue = "true"
)
public class MySqlConnectionVerifier implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(MySqlConnectionVerifier.class);

    private final DatabaseCheckMapper databaseCheckMapper;
    private final SqlSessionFactory sqlSessionFactory;

    public MySqlConnectionVerifier(
            DatabaseCheckMapper databaseCheckMapper,
            SqlSessionFactory sqlSessionFactory) {
        this.databaseCheckMapper = databaseCheckMapper;
        this.sqlSessionFactory = sqlSessionFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!(sqlSessionFactory.getConfiguration() instanceof MybatisConfiguration)) {
            throw new IllegalStateException("未启用 MyBatis-Plus 配置，请检查 Maven 依赖");
        }

        Map<String, Object> result = databaseCheckMapper.checkConnection();
        Object connectionOk = result == null ? null : result.get("connection_ok");

        if (!(connectionOk instanceof Number number) || number.intValue() != 1) {
            throw new IllegalStateException("MySQL 连接检查未返回预期结果");
        }

        if (!"xiaozhi_learning".equals(result.get("database_name"))) {
            throw new IllegalStateException("MySQL 当前数据库不是 xiaozhi_learning，请检查连接配置");
        }

        log.info(
                "MYSQL_CONNECTION_OK database={} version={} account={} mapper=MyBatis-Plus",
                result.get("database_name"),
                result.get("database_version"),
                result.get("login_account")
        );
    }
}