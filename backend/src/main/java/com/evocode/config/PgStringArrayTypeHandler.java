package com.evocode.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL varchar[] 列专用 TypeHandler（project.framework_tags）。
 * 用法：@TableField(typeHandler = PgStringArrayTypeHandler.class)。
 */
public class PgStringArrayTypeHandler extends BaseTypeHandler<List<String>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
            throws SQLException {
        Array array = ps.getConnection().createArrayOf("varchar", parameter.toArray());
        ps.setArray(i, array);
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    private List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Object data = array.getArray();
        List<String> result = new ArrayList<>();
        if (data instanceof Object[] objs) {
            for (Object o : objs) {
                result.add(o == null ? null : o.toString());
            }
        }
        return result;
    }
}
