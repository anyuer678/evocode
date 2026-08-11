package com.evocode.config;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * jsonb 列专用 TypeHandler：写入时包装为 PGobject（pgjdbc 对 jsonb 不接受裸 setString）。
 * 用法：@TableField(typeHandler = PgJsonbTypeHandler.class) + @TableName(autoResultMap = true)。
 */
public class PgJsonbTypeHandler extends JacksonTypeHandler {

    public PgJsonbTypeHandler(Class<?> type) {
        super(type);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
            throws SQLException {
        String json = toJson(parameter);
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        obj.setValue(json);
        ps.setObject(i, obj);
    }
}
