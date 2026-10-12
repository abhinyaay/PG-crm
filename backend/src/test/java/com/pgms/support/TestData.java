package com.pgms.support;

import com.pgms.user.Role;
import com.pgms.user.RoleName;
import com.pgms.user.User;
import java.lang.reflect.Field;

/** Small reflection helpers for unit tests of entities whose ids/roles are normally set by JPA. */
public final class TestData {

    private TestData() {
    }

    public static <T> T withId(T entity, Long id) {
        set(entity, findField(entity.getClass(), "id"), id);
        return entity;
    }

    /** Sets any (possibly inherited, private) field, e.g. state normally written by the database or a job. */
    public static <T> T with(T entity, String field, Object value) {
        set(entity, findField(entity.getClass(), field), value);
        return entity;
    }

    public static Role role(RoleName name) {
        try {
            var constructor = Role.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Role role = constructor.newInstance();
            set(role, Role.class.getDeclaredField("name"), name);
            set(role, Role.class.getDeclaredField("id"), (short) (name.ordinal() + 1));
            return role;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static User user(Long id, Long organizationId, RoleName roleName, String passwordHash) {
        return withId(new User(organizationId, role(roleName), "user" + id + "@example.com", passwordHash, "User " + id, null), id);
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up the hierarchy
            }
        }
        throw new IllegalArgumentException("No field " + name + " on " + type);
    }

    private static void set(Object target, Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
