/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.utils;

import org.apache.dubbo.common.convert.ConverterUtil;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.model.FrameworkModel;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableSet;
import static org.apache.dubbo.common.function.Streams.filterAll;
import static org.apache.dubbo.common.utils.ArrayUtils.isNotEmpty;
import static org.apache.dubbo.common.utils.CollectionUtils.flip;
import static org.apache.dubbo.common.utils.CollectionUtils.ofSet;
import static org.apache.dubbo.common.utils.StringUtils.isEmpty;

public class ClassUtils {
    /**
     * Suffix for array class names: "[]"
     */
    public static final String ARRAY_SUFFIX = "[]";
    /**
     * Simple Types including:
     * <ul>
     *     <li>{@link Void}</li>
     *     <li>{@link Boolean}</li>
     *     <li>{@link Character}</li>
     *     <li>{@link Byte}</li>
     *     <li>{@link Integer}</li>
     *     <li>{@link Float}</li>
     *     <li>{@link Double}</li>
     *     <li>{@link String}</li>
     *     <li>{@link BigDecimal}</li>
     *     <li>{@link BigInteger}</li>
     *     <li>{@link Date}</li>
     *     <li>{@link Object}</li>
     * </ul>
     *
     * @see javax.management.openmbean.SimpleType
     * @since 2.7.6
     */
    public static final Set<Class<?>> SIMPLE_TYPES = ofSet(
        Void.class,
        Boolean.class,
        Character.class,
        Byte.class,
        Short.class,
        Integer.class,
        Long.class,
        Float.class,
        Double.class,
        String.class,
        BigDecimal.class,
        BigInteger.class,
        Date.class,
        Object.class,
        Duration.class);
    /**
     * Prefix for internal array class names: "[L"
     */
    private static final String INTERNAL_ARRAY_PREFIX = "[L";
    /**
     * Map with primitive type name as key and corresponding primitive type as
     * value, for example: "int" -> "int.class".
     * 8中基本数据类型及void,还有它们的数组
     */
    private static final Map<String, Class<?>> PRIMITIVE_TYPE_NAME_MAP = new HashMap<>(32);
    /**
     * Map with primitive wrapper type as key and corresponding primitive type
     * as value, for example: Integer.class -> int.class.
     */
    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPER_TYPE_MAP = new HashMap<>(16);

    static {
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Boolean.class, boolean.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Byte.class, byte.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Character.class, char.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Double.class, double.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Float.class, float.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Integer.class, int.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Long.class, long.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Short.class, short.class);
        PRIMITIVE_WRAPPER_TYPE_MAP.put(Void.class, void.class);

        Set<Class<?>> primitiveTypeNames = new HashSet<>(32);
        primitiveTypeNames.addAll(PRIMITIVE_WRAPPER_TYPE_MAP.values());
        primitiveTypeNames.addAll(Arrays.asList(
            boolean[].class,
            byte[].class,
            char[].class,
            double[].class,
            float[].class,
            int[].class,
            long[].class,
            short[].class));
        for (Class<?> primitiveTypeName : primitiveTypeNames) {
            PRIMITIVE_TYPE_NAME_MAP.put(primitiveTypeName.getName(), primitiveTypeName);
        }
    }

    /**
     * Map with primitive type as key and corresponding primitive wrapper type
     * as value, for example: int.class -> Integer.class.
     */
    private static final Map<Class<?>, Class<?>> WRAPPER_PRIMITIVE_TYPE_MAP = flip(PRIMITIVE_WRAPPER_TYPE_MAP);

    /**
     * Separator char for package
     */
    private static final char PACKAGE_SEPARATOR_CHAR = '.';

    public static Class<?> forNameWithThreadContextClassLoader(String name) throws ClassNotFoundException {
        return forName(name, Thread.currentThread().getContextClassLoader());
    }

    public static Class<?> forNameWithCallerClassLoader(String name, Class<?> caller) throws ClassNotFoundException {
        return forName(name, caller.getClassLoader());
    }

    public static ClassLoader getCallerClassLoader(Class<?> caller) {
        return caller.getClassLoader();
    }

    /**
     * get class loader
     *
     * @param clazz
     * @return class loader
     */
    public static ClassLoader getClassLoader(Class<?> clazz) {
        ClassLoader cl = null;
        if (!clazz.getName().startsWith("org.apache.dubbo")) {
            cl = clazz.getClassLoader();
        }
        if (cl == null) {
            try {
                cl = Thread.currentThread().getContextClassLoader();
            } catch (Exception ignored) {
                // Cannot access thread context ClassLoader - falling back to system class loader...
            }
            if (cl == null) {
                // No thread context class loader -> use class loader of this class.
                cl = clazz.getClassLoader();
                if (cl == null) {
                    // getClassLoader() returning null indicates the bootstrap ClassLoader
                    try {
                        cl = ClassLoader.getSystemClassLoader();
                    } catch (Exception ignored) {
                        // Cannot access system ClassLoader - oh well, maybe the caller can live with null...
                    }
                }
            }
        }

        return cl;
    }

    /**
     * Return the default ClassLoader to use: typically the thread context
     * ClassLoader, if available; the ClassLoader that loaded the ClassUtils
     * class will be used as fallback.
     * <p>
     * Call this method if you intend to use the thread context ClassLoader in a
     * scenario where you absolutely need a non-null ClassLoader reference: for
     * example, for class path resource loading (but not necessarily for
     * <code>Class.forName</code>, which accepts a <code>null</code> ClassLoader
     * reference as well).
     *
     * @return the default ClassLoader (never <code>null</code>)
     * @see java.lang.Thread#getContextClassLoader()
     */
    public static ClassLoader getClassLoader() {
        return getClassLoader(ClassUtils.class);
    }

    /**
     * Same as <code>Class.forName()</code>, except that it works for primitive
     * types.
     */
    public static Class<?> forName(String name) throws ClassNotFoundException {
        return forName(name, getClassLoader());
    }

    /**
     * Replacement for <code>Class.forName()</code> that also returns Class
     * instances for primitives (like "int") and array class names (like
     * "String[]").
     * <p>
     * {@link ClassUtils#isPresent(java.lang.String, java.lang.ClassLoader)}中调用
     * </p>
     *
     * @param name        the name of the Class
     * @param classLoader the class loader to use (may be <code>null</code>,
     *                    which indicates the default class loader)
     * @return Class instance for the supplied name
     * @throws ClassNotFoundException if the class was not found
     * @throws LinkageError           if the class file could not be loaded
     * @see Class#forName(String, boolean, ClassLoader)
     */
    public static Class<?> forName(String name, ClassLoader classLoader) throws ClassNotFoundException, LinkageError {
        /**
         * 是否是8种基本数据类型或者void，或者这9种数据类型的数组形式
         * 如果是，返回
         */
        Class<?> clazz = resolvePrimitiveClassName(name);
        if (clazz != null) {
            return clazz;
        }

        // "java.lang.String[]" style arrays
        if (name.endsWith(ARRAY_SUFFIX)) {
            // 数组
            String elementClassName = name.substring(0, name.length() - ARRAY_SUFFIX.length());
            Class<?> elementClass = forName(elementClassName, classLoader);
            return Array.newInstance(elementClass, 0).getClass();
        }

        // "[Ljava.lang.String;" style arrays
        int internalArrayMarker = name.indexOf(INTERNAL_ARRAY_PREFIX);
        if (internalArrayMarker != -1 && name.endsWith(";")) {
            String elementClassName = null;
            if (internalArrayMarker == 0) {
                elementClassName = name.substring(INTERNAL_ARRAY_PREFIX.length(), name.length() - 1);
            } else if (name.startsWith("[")) {
                elementClassName = name.substring(1);
            }
            Class<?> elementClass = forName(elementClassName, classLoader);
            return Array.newInstance(elementClass, 0).getClass();
        }

        ClassLoader classLoaderToUse = classLoader;
        if (classLoaderToUse == null) {
            classLoaderToUse = getClassLoader();
        }
        return classLoaderToUse.loadClass(name);
    }

    /**
     * Resolve the given class name as primitive class, if appropriate,
     * according to the JVM's naming rules for primitive classes.
     * <p>
     * Also supports the JVM's internal class names for primitive arrays. Does
     * <i>not</i> support the "[]" suffix notation for primitive arrays; this is
     * only supported by {@link #forName}.
     *
     * @param name the name of the potentially primitive class
     * @return the primitive class, or <code>null</code> if the name does not
     * denote a primitive class or primitive array class
     */
    public static Class<?> resolvePrimitiveClassName(String name) {
        Class<?> result = null;
        // Most class names will be quite long, considering that they
        // SHOULD sit in a package, so a length check is worthwhile.
        if (name != null && name.length() <= 8) {
            // Could be a primitive - likely.
            result = (Class<?>) PRIMITIVE_TYPE_NAME_MAP.get(name);
        }
        return result;
    }

    public static String toShortString(Object obj) {
        if (obj == null) {
            return "null";
        }
        return obj.getClass().getSimpleName() + "@" + System.identityHashCode(obj);
    }

    public static String simpleClassName(Class<?> clazz) {
        if (clazz == null) {
            throw new NullPointerException("clazz");
        }
        String className = clazz.getName();
        final int lastDotIdx = className.lastIndexOf(PACKAGE_SEPARATOR_CHAR);
        if (lastDotIdx > -1) {
            return className.substring(lastDotIdx + 1);
        }
        return className;
    }

    /**
     * The specified type is primitive type or simple type
     *
     * @param type the type to test
     * @return
     * @deprecated as 2.7.6, use {@link Class#isPrimitive()} plus {@link #isSimpleType(Class)} instead
     */
    public static boolean isPrimitive(Class<?> type) {
        return type != null && (type.isPrimitive() || isSimpleType(type));
    }

    /**
     * The specified type is simple type or not
     *
     * @param type the type to test
     * @return if <code>type</code> is one element of {@link #SIMPLE_TYPES}, return <code>true</code>, or <code>false</code>
     * @see #SIMPLE_TYPES
     * @since 2.7.6
     */
    public static boolean isSimpleType(Class<?> type) {
        return SIMPLE_TYPES.contains(type);
    }

    public static Object convertPrimitive(Class<?> type, String value) {
        return convertPrimitive(FrameworkModel.defaultModel(), type, value);
    }

    public static Object convertPrimitive(FrameworkModel frameworkModel, Class<?> type, String value) {
        if (isEmpty(value)) {
            return null;
        }
        Class<?> wrapperType = WRAPPER_PRIMITIVE_TYPE_MAP.getOrDefault(type, type);
        Object result = null;
        try {
            result =
                frameworkModel.getBeanFactory().getBean(ConverterUtil.class).convertIfPossible(value, wrapperType);
        } catch (Exception e) {
            // ignore exception
        }
        return result;
    }

    /**
     * We only check boolean value at this moment.
     *
     * @param type
     * @param value
     * @return
     */
    public static boolean isTypeMatch(Class<?> type, String value) {
        if ((type == boolean.class || type == Boolean.class) && !("true".equals(value) || "false".equals(value))) {
            return false;
        }
        return true;
    }

    /**
     * Get all super classes from the specified type
     *
     * @param type         the specified type
     * @param classFilters the filters for classes
     * @return non-null read-only {@link Set}
     * @since 2.7.6
     */
    public static Set<Class<?>> getAllSuperClasses(Class<?> type, Predicate<Class<?>>... classFilters) {

        Set<Class<?>> allSuperClasses = new LinkedHashSet<>();

        Class<?> superClass = type.getSuperclass();
        while (superClass != null) {
            // add current super class
            allSuperClasses.add(superClass);
            superClass = superClass.getSuperclass();
        }

        return unmodifiableSet(filterAll(allSuperClasses, classFilters));
    }

    /**
     * Get all interfaces from the specified type
     *
     * @param type             the specified type
     * @param interfaceFilters the filters for interfaces
     * @return non-null read-only {@link Set}
     * @since 2.7.6
     */
    public static Set<Class<?>> getAllInterfaces(Class<?> type, Predicate<Class<?>>... interfaceFilters) {
        if (type == null || type.isPrimitive()) {
            return emptySet();
        }

        Set<Class<?>> allInterfaces = new LinkedHashSet<>();
        Set<Class<?>> resolved = new LinkedHashSet<>();
        Queue<Class<?>> waitResolve = new LinkedList<>();

        resolved.add(type);
        Class<?> clazz = type;
        while (clazz != null) {

            Class<?>[] interfaces = clazz.getInterfaces();

            if (isNotEmpty(interfaces)) {
                // add current interfaces
                Arrays.stream(interfaces).filter(resolved::add).forEach(cls -> {
                    allInterfaces.add(cls);
                    waitResolve.add(cls);
                });
            }

            // add all super classes to waitResolve
            getAllSuperClasses(clazz).stream().filter(resolved::add).forEach(waitResolve::add);

            clazz = waitResolve.poll();
        }

        return filterAll(allInterfaces, interfaceFilters);
    }

    /**
     * Get all inherited types from the specified type
     *
     * @param type        the specified type
     * @param typeFilters the filters for types
     * @return non-null read-only {@link Set}
     * @since 2.7.6
     */
    public static Set<Class<?>> getAllInheritedTypes(Class<?> type, Predicate<Class<?>>... typeFilters) {
        // Add all super classes
        Set<Class<?>> types = new LinkedHashSet<>(getAllSuperClasses(type, typeFilters));
        // Add all interface classes
        types.addAll(getAllInterfaces(type, typeFilters));
        return unmodifiableSet(types);
    }

    /**
     * the semantics is same as {@link Class#isAssignableFrom(Class)}
     *
     * @param superType  the super type
     * @param targetType the target type
     * @return see {@link Class#isAssignableFrom(Class)}
     * @since 2.7.6
     */
    public static boolean isAssignableFrom(Class<?> superType, Class<?> targetType) {
        // any argument is null
        if (superType == null || targetType == null) {
            return false;
        }
        // equals
        if (Objects.equals(superType, targetType)) {
            return true;
        }
        // isAssignableFrom
        return superType.isAssignableFrom(targetType);
    }

    /**
     * Test the specified class name is present in the {@link ClassLoader}
     * 通过classLoader加载className，如果有异常返回false，否则返回true
     * <p>
     * {@link ExtensionLoader#loadClassIfActive(java.lang.ClassLoader, java.lang.Class)}中调用
     * </p>
     *
     * @param className   the name of {@link Class}
     * @param classLoader {@link ClassLoader}
     * @return If found, return <code>true</code>
     * @since 2.7.6
     */
    public static boolean isPresent(String className, ClassLoader classLoader) {
        try {
            forName(className, classLoader);
        } catch (Exception ignored) { // Ignored
            return false;
        }
        return true;
    }

    /**
     * Resolve the {@link Class} by the specified name and {@link ClassLoader}
     *
     * @param className   the name of {@link Class}
     * @param classLoader {@link ClassLoader}
     * @return If can't be resolved , return <code>null</code>
     * @since 2.7.6
     */
    public static Class<?> resolveClass(String className, ClassLoader classLoader) {
        Class<?> targetClass = null;
        try {
            targetClass = forName(className, classLoader);
        } catch (Exception ignored) { // Ignored
        }
        return targetClass;
    }

    /**
     * Is generic class or not?
     *
     * @param type the target type
     * @return if the target type is not null or <code>void</code> or Void.class, return <code>true</code>, or false
     * @since 2.7.6
     */
    public static boolean isGenericClass(Class<?> type) {
        return type != null && !void.class.equals(type) && !Void.class.equals(type);
    }

    public static boolean hasMethods(Method[] methods) {
        if (methods == null || methods.length == 0) {
            return false;
        }
        for (Method m : methods) {
            if (m.getDeclaringClass() != Object.class) {
                return true;
            }
        }
        return false;
    }

    private static final String[] OBJECT_METHODS = new String[]{"getClass", "hashCode", "toString", "equals"};

    /**
     * get method name array.
     *
     * @return method name array.
     */
    public static String[] getMethodNames(Class<?> tClass) {
        if (tClass == Object.class) {
            return OBJECT_METHODS;
        }
        Method[] methods =
            Arrays.stream(tClass.getMethods()).collect(Collectors.toList()).toArray(new Method[]{});
        List<String> mns = new ArrayList<>(); // method names.
        boolean hasMethod = hasMethods(methods);
        if (hasMethod) {
            for (Method m : methods) {
                // ignore Object's method.
                if (m.getDeclaringClass() == Object.class) {
                    continue;
                }
                String mn = m.getName();
                mns.add(mn);
            }
        }
        return mns.toArray(new String[0]);
    }

    public static boolean isMatch(Class<?> from, Class<?> to) {
        if (from == to) {
            return true;
        }
        boolean isMatch;
        if (from.isPrimitive()) {
            isMatch = matchPrimitive(from, to);
        } else if (to.isPrimitive()) {
            isMatch = matchPrimitive(to, from);
        } else {
            isMatch = to.isAssignableFrom(from);
        }
        return isMatch;
    }

    private static boolean matchPrimitive(Class<?> from, Class<?> to) {
        if (from == boolean.class) {
            return to == Boolean.class;
        } else if (from == byte.class) {
            return to == Byte.class;
        } else if (from == char.class) {
            return to == Character.class;
        } else if (from == short.class) {
            return to == Short.class;
        } else if (from == int.class) {
            return to == Integer.class;
        } else if (from == long.class) {
            return to == Long.class;
        } else if (from == float.class) {
            return to == Float.class;
        } else if (from == double.class) {
            return to == Double.class;
        } else if (from == void.class) {
            return to == Void.class;
        }
        return false;
    }

    /**
     * get method name array.
     *
     * @return method name array.
     */
    public static String[] getDeclaredMethodNames(Class<?> tClass) {
        if (tClass == Object.class) {
            return OBJECT_METHODS;
        }
        Method[] methods =
            Arrays.stream(tClass.getMethods()).collect(Collectors.toList()).toArray(new Method[]{});
        List<String> dmns = new ArrayList<>(); // method names.
        boolean hasMethod = hasMethods(methods);
        if (hasMethod) {
            for (Method m : methods) {
                // ignore Object's method.
                if (m.getDeclaringClass() == Object.class) {
                    continue;
                }
                String mn = m.getName();
                if (m.getDeclaringClass() == tClass) {
                    dmns.add(mn);
                }
            }
        }
        dmns.sort(Comparator.naturalOrder());
        return dmns.toArray(new String[0]);
    }
}
