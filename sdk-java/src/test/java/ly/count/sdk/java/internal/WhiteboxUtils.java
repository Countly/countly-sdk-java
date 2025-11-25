package ly.count.sdk.java.internal;

import java.lang.reflect.Field;

public class WhiteboxUtils {

    public static Object getInternalState(Object target, String fieldName) {
        Class<?> clazz = target.getClass();

        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass(); // search parent class
            } catch (Exception e) {
                throw new RuntimeException("Failed to get internal state for field: " + fieldName, e);
            }
        }

        throw new RuntimeException("Field not found: " + fieldName + " in class hierarchy of " + target.getClass());
    }

    public static <T> T getInternalState(Object target, String fieldName, Class<T> fieldType) {
        Class<?> clazz = target.getClass();

        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);

                Object value = field.get(target);
                return fieldType.cast(value);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                throw new RuntimeException("Failed to read field '" + fieldName + "'", e);
            }
        }

        throw new RuntimeException("Field '" + fieldName + "' not found in " + target.getClass());
    }

    public static <T> T getInternalStaticState(Class<?> clazz, String fieldName, Class<T> fieldType) {
        Class<?> current = clazz;

        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return fieldType.cast(field.get(null)); // static field -> null target
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (Exception e) {
                throw new RuntimeException("Failed to read static field '" + fieldName + "'", e);
            }
        }

        throw new RuntimeException("Static field '" + fieldName + "' not found in " + clazz);
    }
}
