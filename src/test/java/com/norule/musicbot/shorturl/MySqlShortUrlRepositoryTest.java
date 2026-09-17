package com.norule.musicbot.shorturl;

import com.norule.musicbot.ShortUrlService;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlShortUrlRepositoryTest {
    @Test
    void managementWritesIncludeOwnerAndCreationIdentityInSqlPredicate() {
        java.util.List<String> statements = new java.util.ArrayList<>();
        java.util.List<Map<Integer, Object>> bindings = new java.util.ArrayList<>();
        DataSource source = proxy(DataSource.class, (ds, method, args) -> {
            if (!"getConnection".equals(method.getName())) return defaultValue(method.getReturnType());
            return proxy(Connection.class, (connection, call, params) -> {
                if (!"prepareStatement".equals(call.getName())) return defaultValue(call.getReturnType());
                statements.add((String) params[0]);
                Map<Integer, Object> values = new HashMap<>();
                bindings.add(values);
                return proxy(PreparedStatement.class, (statement, operation, arguments) -> {
                    if (operation.getName().startsWith("set")) values.put((Integer) arguments[0], arguments[1]);
                    if ("executeUpdate".equals(operation.getName())) return 1;
                    return defaultValue(operation.getReturnType());
                });
            });
        });
        var repository = new MySqlShortUrlRepository(source);
        assertTrue(repository.updateOwnedTarget("code", "owner", 123L, "https://example.com"));
        assertTrue(repository.deleteOwned("code", "owner", 123L));
        assertTrue(statements.stream().allMatch(sql -> sql.contains("WHERE code = ? AND owner_user_id = ? AND created_at = ?")));
        assertEquals(Map.of(1, "https://example.com", 2, "code", 3, "owner", 4, 123L), bindings.get(0));
        assertEquals(Map.of(1, "code", 2, "owner", 3, 123L), bindings.get(1));
    }

    @Test
    void declaresCaseInsensitiveCollationForTheCodePrimaryKey() {
        assertTrue(MySqlShortUrlRepository.CREATE_TABLE.contains("PRIMARY KEY (code)"));
        assertTrue(MySqlShortUrlRepository.CREATE_TABLE.contains("COLLATE=utf8mb4_unicode_ci"));
    }

    @Test
    void rejectsCaseVariantOfLegacyCodeUsingMysqlDuplicateKeySemantics() {
        ShortUrlService.ShortUrlEntry legacy = new ShortUrlService.ShortUrlEntry(
                "AbC123", "https://example.com/legacy", 1L, 4102444800000L);
        MySqlShortUrlRepository repository = new MySqlShortUrlRepository(mysqlLikeDataSource(legacy));
        ShortUrlService.ShortUrlEntry caseVariant = new ShortUrlService.ShortUrlEntry(
                "abc123", "https://example.com/new", 2L, 4102444800000L);

        assertEquals("AbC123", repository.findByCodeIgnoreCase("abc123").getCode());
        assertFalse(repository.saveIfAbsent(caseVariant));
    }

    private DataSource mysqlLikeDataSource(ShortUrlService.ShortUrlEntry legacy) {
        return proxy(DataSource.class, (proxy, method, args) -> {
            if ("getConnection".equals(method.getName())) {
                return mysqlLikeConnection(legacy);
            }
            return defaultValue(method.getReturnType());
        });
    }

    private Connection mysqlLikeConnection(ShortUrlService.ShortUrlEntry legacy) {
        return proxy(Connection.class, (proxy, method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                return mysqlLikeStatement((String) args[0], legacy);
            }
            return defaultValue(method.getReturnType());
        });
    }

    private PreparedStatement mysqlLikeStatement(String sql, ShortUrlService.ShortUrlEntry legacy) {
        Map<Integer, Object> parameters = new HashMap<>();
        return proxy(PreparedStatement.class, (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length == 2) {
                parameters.put((Integer) args[0], args[1]);
                return null;
            }
            if ("executeQuery".equals(name)) {
                String code = String.valueOf(parameters.get(1));
                boolean found = sql.contains("LOWER(code)") && legacy.code().equalsIgnoreCase(code);
                return resultSet(found ? legacy : null);
            }
            if ("executeUpdate".equals(name)) {
                String code = String.valueOf(parameters.get(1));
                if (sql.startsWith("INSERT INTO short_urls") && legacy.code().equalsIgnoreCase(code)) {
                    throw new SQLException("Duplicate entry", "23000", 1062);
                }
                return 1;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private ResultSet resultSet(ShortUrlService.ShortUrlEntry entry) {
        boolean[] available = {entry != null};
        return proxy(ResultSet.class, (proxy, method, args) -> {
            if ("next".equals(method.getName())) {
                boolean next = available[0];
                available[0] = false;
                return next;
            }
            if ("getString".equals(method.getName())) {
                return switch (String.valueOf(args[0])) {
                    case "code" -> entry.code();
                    case "target" -> entry.target();
                    case "owner_user_id" -> entry.ownerUserId();
                    default -> "";
                };
            }
            if ("getLong".equals(method.getName())) {
                return switch (String.valueOf(args[0])) {
                    case "created_at" -> entry.createdAt();
                    case "expires_at" -> entry.expiresAt();
                    case "view_count" -> entry.viewCount();
                    case "last_accessed_at" -> entry.lastAccessedAt();
                    default -> 0L;
                };
            }
            return defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
