package com.ruomu.xiaozhi.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface DatabaseCheckMapper {

    @Select("""
            SELECT 1 AS connection_ok,
                   DATABASE() AS database_name,
                   VERSION() AS database_version,
                   CURRENT_USER() AS login_account
            """)
    Map<String, Object> checkConnection();
}