/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.sqlserver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.annotation.ThreadSafe;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.relational.Column;
import io.debezium.relational.DefaultValueConverter;
import io.debezium.relational.ValueConverter;
import io.debezium.util.HexConverter;

import microsoft.sql.DateTimeOffset;

/**
 * Parses and converts column default values.
 */
@ThreadSafe
class SqlServerDefaultValueConverter implements DefaultValueConverter {

    private static Logger LOGGER = LoggerFactory.getLogger(SqlServerDefaultValueConverter.class);

    /**
     * Provides SQL connection instance.
     */
    @FunctionalInterface
    interface ConnectionProvider {
        Connection get() throws SQLException;
    }

    /**
     * Converts JDBC string representation of a default column value to an object.
     */
    @FunctionalInterface
    private interface DefaultValueMapper {

        /**
         * Parses string to an object.
         *
         * @param value string representation
         * @return value
         * @throws Exception if there is an parsing error
         */
        Object parse(String value) throws Exception;
    }

    private final ConnectionProvider connectionProvider;
    private final SqlServerValueConverters valueConverters;
    private final Map<String, DefaultValueMapper> defaultValueMappers;

    SqlServerDefaultValueConverter(ConnectionProvider connectionProvider, SqlServerValueConverters valueConverters) {
        this.connectionProvider = connectionProvider;
        this.valueConverters = valueConverters;
        this.defaultValueMappers = Collections.unmodifiableMap(createDefaultValueMappers());
    }

    @Override
    public Optional<Object> parseDefaultValue(Column column, String defaultValueExpression) {
        if (defaultValueExpression == null) {
            return Optional.empty();
        }

        final String dataType = column.typeName();
        final DefaultValueMapper mapper = defaultValueMappers.get(dataType);
        if (mapper == null) {
            LOGGER.warn("Mapper for type '{}' not found.", dataType);
            return Optional.empty();
        }

        try {
            Object rawDefaultValue = mapper.parse(defaultValueExpression);
            Object convertedDefaultValue = convertDefaultValue(rawDefaultValue, column);
            return Optional.ofNullable(convertedDefaultValue);
        }
        catch (Exception e) {
            LOGGER.warn("Cannot parse column default value '{}' to type '{}'. Expression evaluation is not supported.", defaultValueExpression, dataType);
            LOGGER.debug("Parsing failed due to error", e);
            return Optional.empty();
        }
    }

    private Object convertDefaultValue(Object defaultValue, Column column) {
        // if converters is not null and the default value is not null, we need to convert default value
        if (valueConverters != null && defaultValue != null) {
            final SchemaBuilder schemaBuilder = valueConverters.schemaBuilder(column);
            if (schemaBuilder == null) {
                return defaultValue;
            }
            final Schema schema = schemaBuilder.build();
            // In order to get the valueConverter for this column, we have to create a field;
            // The index value -1 in the field will never used when converting default value;
            // So we can set any number here;
            final Field field = new Field(column.name(), -1, schema);
            final ValueConverter valueConverter = valueConverters.converter(column, field);
            Object result = valueConverter.convert(defaultValue);
            if ((result instanceof BigDecimal) && column.scale().isPresent() && column.scale().get() > ((BigDecimal) result).scale()) {
                // Note that as the scale is increased only, the rounding is more cosmetic.
                result = ((BigDecimal) result).setScale(column.scale().get(), RoundingMode.HALF_EVEN);
            }
            return result;
        }
        return defaultValue;
    }

    private Map<String, DefaultValueMapper> createDefaultValueMappers() {
        final Map<String, DefaultValueMapper> result = new HashMap<>();

        // Exact numbers
        result.put("bigint",
                v -> nullableDefaultValueMapper(v, value -> Long.parseLong(value.charAt(value.length() - 1) == '.' ? value.substring(0, value.length() - 1) : value))); // Sample value: ((3147483648.))
        result.put("int", v -> nullableDefaultValueMapper(v, Integer::parseInt)); // Sample value: ((2147483647))
        result.put("smallint", v -> nullableDefaultValueMapper(v, Short::parseShort)); // Sample value: ((32767))
        result.put("tinyint", v -> nullableDefaultValueMapper(v, Short::parseShort)); // Sample value: ((255))
        result.put("bit", v -> v.equals("((1))")); // Either ((1)) or ((0))
        result.put("decimal", v -> new BigDecimal(v.substring(2, v.length() - 2))); // Sample value: ((100.12345))
        result.put("numeric", v -> new BigDecimal(v.substring(2, v.length() - 2))); // Sample value: ((100.12345))
        result.put("money", v -> new BigDecimal(v.substring(2, v.length() - 2))); // Sample value: ((922337203685477.58))
        result.put("smallmoney", v -> new BigDecimal(v.substring(2, v.length() - 2))); // Sample value: ((214748.3647))

        // Approximate numerics
        result.put("float", v -> nullableDefaultValueMapper(v, Double::parseDouble)); // Sample value: ((1.2345000000000000e+003))
        result.put("real", v -> nullableDefaultValueMapper(v, Float::parseFloat)); // Sample value: ((1.2345000000000000e+003))

        // Date and time
        result.put("date", v -> { // Sample value: ('2019-02-03')
            String rawValue = v.substring(2, v.length() - 2);
            return JdbcConnection.querySingleValue(connectionProvider.get(), "SELECT PARSE(? AS date)", st -> st.setString(1, rawValue), rs -> rs.getDate(1));
        });
        result.put("datetime", v -> { // Sample value: ('2019-01-01 00:00:00.000')
            String rawValue = v.substring(2, v.length() - 2);
            return JdbcConnection.querySingleValue(connectionProvider.get(), "SELECT PARSE(? AS datetime)", st -> st.setString(1, rawValue), rs -> rs.getTimestamp(1));
        });
        result.put("datetime2", v -> { // Sample value: ('2019-01-01 00:00:00.1234567')
            String rawValue = v.substring(2, v.length() - 2);
            return JdbcConnection.querySingleValue(connectionProvider.get(), "SELECT PARSE(? AS datetime2)", st -> st.setString(1, rawValue), rs -> rs.getTimestamp(1));
        });
        result.put("datetimeoffset", v -> { // Sample value: ('2019-01-01 00:00:00.1234567+02:00')
            String rawValue = v.substring(2, v.length() - 2);
            return JdbcConnection.querySingleValue(connectionProvider.get(), "SELECT PARSE(? AS datetimeoffset)", st -> st.setString(1, rawValue),
                    rs -> (DateTimeOffset) rs.getObject(1));
        });
        result.put("smalldatetime", v -> { // Sample value: ('2019-01-01 00:00:00')
            String rawValue = v.substring(2, v.length() - 2);
            return JdbcConnection.querySingleValue(connectionProvider.get(), "SELECT PARSE(? AS smalldatetime)", st -> st.setString(1, rawValue),
                    rs -> rs.getTimestamp(1));
        });
        result.put("time", v -> { // Sample value: ('2019-01-01 00:00:00')
            String rawValue = v.substring(2, v.length() - 2);
            return JdbcConnection.querySingleValue(connectionProvider.get(), "SELECT PARSE(? AS time)", st -> st.setString(1, rawValue), rs -> rs.getTime(1));
        });

        // Character strings
        result.put("char", v -> v.substring(2, v.length() - 2)); // Sample value: ('aaa')
        result.put("text", v -> v.substring(2, v.length() - 2)); // Sample value: ('aaa')
        result.put("varchar", v -> v.substring(2, v.length() - 2)); // Sample value: ('aaa')

        // Unicode character strings
        result.put("nchar", v -> v.substring(2, v.length() - 2)); // Sample value: ('aaa')
        result.put("ntext", v -> v.substring(2, v.length() - 2)); // Sample value: ('aaa')
        result.put("nvarchar", v -> v.substring(2, v.length() - 2)); // Sample value: ('aaa')

        // Binary strings
        result.put("binary", v -> HexConverter.convertFromHex(v.substring(3, v.length() - 1))); // Sample value: (0x0102030405)
        result.put("image", v -> HexConverter.convertFromHex(v.substring(3, v.length() - 1))); // Sample value: (0x0102030405)
        result.put("varbinary", v -> HexConverter.convertFromHex(v.substring(3, v.length() - 1))); // Sample value: (0x0102030405)

        // Other data types, such as cursor, xml or uniqueidentifier, have been omitted.
        return result;
    }

    public static Object nullableDefaultValueMapper(String v, DefaultValueMapper mapper) throws Exception {
        int start = v.lastIndexOf('(') == -1 ? 0 : v.lastIndexOf('(') + 1;
        int end = !v.contains(")") ? v.length() : v.indexOf(')');
        final String value = v.substring(start, end); // trim leading and trailing parenthesis
        if ("NULL".equalsIgnoreCase(value)) {
            return null;
        }
        else {
            return mapper.parse(value);
        }
    }
}
