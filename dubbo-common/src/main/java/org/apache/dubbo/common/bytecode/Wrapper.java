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
package org.apache.dubbo.common.bytecode;

import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.ConcurrentHashMapUtils;
import org.apache.dubbo.common.utils.ReflectUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import javassist.ClassPool;
import javassist.CtMethod;

/**
 * Wrapper.
 */
public abstract class Wrapper {
    private static final ConcurrentMap<Class<?>, Wrapper> WRAPPER_MAP = new ConcurrentHashMap<Class<?>, Wrapper>(); // class wrapper map
    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    private static final String[] OBJECT_METHODS = new String[]{"getClass", "hashCode", "toString", "equals"};
    private static final Wrapper OBJECT_WRAPPER = new Wrapper() {

        /**
         * all method name array.
         * @return
         */
        @Override
        public String[] getMethodNames() {
            return OBJECT_METHODS;
        }

        /**
         * 自身声明的方法
         * @return
         */
        @Override
        public String[] getDeclaredMethodNames() {
            return OBJECT_METHODS;
        }

        /**
         * property name array.
         * @return
         */
        @Override
        public String[] getPropertyNames() {
            return EMPTY_STRING_ARRAY;
        }

        /**
         * 返回属性pn的类型
         * @param pn property name.
         * @return
         */
        @Override
        public Class<?> getPropertyType(String pn) {
            return null;
        }

        @Override
        public Object getPropertyValue(Object instance, String pn) throws NoSuchPropertyException {
            throw new NoSuchPropertyException("Property [" + pn + "] not found.");
        }

        @Override
        public void setPropertyValue(Object instance, String pn, Object pv) throws NoSuchPropertyException {
            throw new NoSuchPropertyException("Property [" + pn + "] not found.");
        }

        @Override
        public boolean hasProperty(String name) {
            return false;
        }

        @Override
        public Object invokeMethod(Object instance, String mn, Class<?>[] types, Object[] args)
            throws NoSuchMethodException {
            if ("getClass".equals(mn)) {
                return instance.getClass();
            }
            if ("hashCode".equals(mn)) {
                return instance.hashCode();
            }
            if ("toString".equals(mn)) {
                return instance.toString();
            }
            if ("equals".equals(mn)) {
                if (args.length == 1) {
                    return instance.equals(args[0]);
                }
                throw new IllegalArgumentException("Invoke method [" + mn + "] argument number error.");
            }
            throw new NoSuchMethodException("Method [" + mn + "] not found.");
        }
    };
    private static AtomicLong WRAPPER_CLASS_COUNTER = new AtomicLong(0);

    /**
     * get wrapper.
     * <p>
     * {@link org.apache.dubbo.rpc.proxy.javassist.JavassistProxyFactory#getInvoker(java.lang.Object, java.lang.Class, org.apache.dubbo.common.URL)}
     * 中调用
     * </p>
     *
     * @param c Class instance.
     * @return Wrapper instance(not null).
     */
    public static Wrapper getWrapper(Class<?> c) {
        while (ClassGenerator.isDynamicClass(c)) // can not wrapper on dynamic class.
        {
            c = c.getSuperclass();
        }

        if (c == Object.class) {
            return OBJECT_WRAPPER;
        }

        return ConcurrentHashMapUtils.computeIfAbsent(WRAPPER_MAP, c, Wrapper::makeWrapper);
    }

    /**
     * <p>
     * {@link Wrapper#getWrapper(java.lang.Class)}中调用
     * </p>
     * 好好学习下这个javassist
     *
     * @param c
     * @return
     */
    private static Wrapper makeWrapper(Class<?> c) {
        if (c.isPrimitive()) {
            // 是否是8种基本类型
            throw new IllegalArgumentException("Can not create wrapper for primitive type: " + c);
        }
        // 类全名
        String name = c.getName();
        /**
         * 注意这个事CL,是classloader,下面的是C1
         */
        ClassLoader cl = ClassUtils.getClassLoader(c);
        /**
         * setPropertyValue方法。三个参数：1原始实例,2propertyName,3propertyValue
         */
        StringBuilder c1 = new StringBuilder("public void setPropertyValue(Object o, String n, Object v){ ");
        /**
         * getPropertyValue方法。两个参数：1原始实例,2propertyName;返回propertyValue
         */
        StringBuilder c2 = new StringBuilder("public Object getPropertyValue(Object o, String n){ ");
        /**
         * invokeMethod,可以参考
         * {@link Wrapper#invokeMethod(java.lang.Object, java.lang.String, java.lang.Class[], java.lang.Object[])}
         * {@link org.apache.dubbo.rpc.proxy.AbstractProxyInvoker#doInvoke(java.lang.Object, java.lang.String, java.lang.Class[], java.lang.Object[])}
         * 四个参数分别为:1原始实例,2方法名,3参数类型数组,4参数实例数组
         */
        StringBuilder c3 =
            new StringBuilder("public Object invokeMethod(Object o, String n, Class[] p, Object[] v) throws "
                + InvocationTargetException.class.getName() + "{ ");

        /**
         * 注意：在 javassist 中, $0 表示this,$1 表示方法中的第一个参数,以此类推
         */
        c1.append(name)
            .append(" w; try{ w = ((")
            .append(name)
            .append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");
        c2.append(name)
            .append(" w; try{ w = ((")
            .append(name)
            .append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");
        c3.append(name)
            .append(" w; try{ w = ((")
            .append(name)
            .append(")$1); }catch(Throwable e){ throw new IllegalArgumentException(e); }");

        /**
         * get/set属性
         * key为属性名称
         * value为属性类型
         */
        Map<String, Class<?>> pts = new HashMap<>(); // <property name, property types>

        /**
         * key为method desc,方式为{@link ReflectUtils#getDesc(java.lang.reflect.Method)}
         * value为method
         */
        Map<String, Method> ms = new LinkedHashMap<>(); // <method desc, Method instance>
        // methodNames
        List<String> mns = new ArrayList<>(); // method names.
        // declaringMethodNames
        List<String> dmns = new ArrayList<>(); // declaring method names.

        // get all public field.
        // 当前类或者父类的public 属性
        for (Field f : c.getFields()) {
            // 属性名称
            String fn = f.getName();
            // 属性类型
            Class<?> ft = f.getType();
            if (Modifier.isStatic(f.getModifiers())
                || Modifier.isTransient(f.getModifiers())
                || Modifier.isFinal(f.getModifiers())) {
                // 如果属性是static,transient或者final,就略过
                continue;
            }

            /**
             * setPropertyValue方法,第二个参数如果和当前属性名字相同,则调用第一个参数的.属性名,设置该属性
             */
            c1.append(" if( $2.equals(\"")
                .append(fn)
                .append("\") ){ ((")
                .append(f.getDeclaringClass().getName())
                .append(")w).")
                .append(fn)
                .append('=')
                .append(arg(ft, "$3"))
                .append("; return; }");
            /**
             * getPropertyValue方法。如果第二个参数和当前属性名字相同，直接返回第一个参数的.属性名
             */
            c2.append(" if( $2.equals(\"")
                .append(fn)
                .append("\") ){ return ($w)((")
                .append(f.getDeclaringClass().getName())
                .append(")w).")
                .append(fn)
                .append("; }");
            pts.put(fn, ft);
        }

        // 用classloader初始化ClassPool
        final ClassPool classPool = ClassGenerator.getClassPool(cl);

        List<String> allMethod = new ArrayList<>();
        try {
            // 获取所有公共方法，包括继承了父类的
            final CtMethod[] ctMethods = classPool.get(c.getName()).getMethods();
            for (CtMethod method : ctMethods) {
                /**
                 * methodName(参数类型)返回类型
                 */
                allMethod.add(ReflectUtils.getDesc(method));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Method[] methods = Arrays.stream(c.getMethods())
            .filter(method -> allMethod.contains(ReflectUtils.getDesc(method)))
            .collect(Collectors.toList())
            .toArray(new Method[]{});
        // get all public method.
        /**
         * 是否有除了{@link Object}之外的方法
         */
        boolean hasMethod = ClassUtils.hasMethods(methods);
        if (hasMethod) {
            // 同名方法数量
            Map<String, Integer> sameNameMethodCount = new HashMap<>((int) (methods.length / 0.75f) + 1);
            for (Method m : methods) {
                sameNameMethodCount.compute(m.getName(), (key, oldValue) -> oldValue == null ? 1 : oldValue + 1);
            }

            // invokeMethod 方法
            c3.append(" try{");
            for (Method m : methods) {
                // ignore Object's method.
                if (m.getDeclaringClass() == Object.class) {
                    // 如果是Object的方法,继续
                    continue;
                }
                // methodName
                String mn = m.getName();
                /**
                 * 如果有100个方法，这得走100个判断啊……
                 */
                c3.append(" if( \"").append(mn).append("\".equals( $2 ) ");
                // 参数数量
                int len = m.getParameterTypes().length;
                c3.append(" && ").append(" $3.length == ").append(len);
                // 判断该方法是否有重载
                boolean overload = sameNameMethodCount.get(m.getName()) > 1;
                if (overload) {
                    if (len > 0) {
                        for (int l = 0; l < len; l++) {
                            c3.append(" && ")
                                // 方法第三个参数，是参数类型数组
                                .append(" $3[")
                                .append(l)
                                .append("].getName().equals(\"")
                                .append(m.getParameterTypes()[l].getName())
                                .append("\")");
                        }
                    }
                }

                c3.append(" ) { ");

                if (m.getReturnType() == Void.TYPE) {
                    c3.append(" w.")
                        .append(mn)
                        .append('(')
                        .append(args(m.getParameterTypes(), "$4"))
                        .append(");")
                        .append(" return null;");
                } else {
                    c3.append(" return ($w)w.")
                        .append(mn)
                        .append('(')
                        .append(args(m.getParameterTypes(), "$4"))
                        .append(");");
                }

                // 这个对应的是if(){后面的括号
                c3.append(" }");

                // 将方法放入两个数组中
                mns.add(mn);
                if (m.getDeclaringClass() == c) {
                    dmns.add(mn);
                }
                ms.put(ReflectUtils.getDesc(m), m);
            }
            c3.append(" } catch(Throwable e) { ");
            c3.append("     throw new java.lang.reflect.InvocationTargetException(e); ");
            c3.append(" }");
        }

        // 前面如果有符合的方法，就return 了。如果走到这一步就是没找到方法，抛异常
        c3.append(" throw new ")
            .append(NoSuchMethodException.class.getName())
            .append("(\"Not found method \\\"\"+$2+\"\\\" in class ")
            .append(c.getName())
            .append(".\"); }");

        // deal with get/set method.
        /**
         * 下面处理get/set方法
         */
        Matcher matcher;
        for (Map.Entry<String, Method> entry : ms.entrySet()) {
            // 方法描述
            String md = entry.getKey();
            // 方法
            Method method = entry.getValue();
            if ((matcher = ReflectUtils.GETTER_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                /**
                 * 方法名称为getXXX
                 */
                String pn = propertyName(matcher.group(1));
                //  public Object getPropertyValue(Object o, String n)
                c2.append(" if( $2.equals(\"")
                    .append(pn)
                    .append("\") ){ return ($w)w.")
                    .append(method.getName())
                    .append("(); }");
                pts.put(pn, method.getReturnType());
            } else if ((matcher = ReflectUtils.IS_HAS_CAN_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                /**
                 * is/has/can 方法
                 */
                String pn = propertyName(matcher.group(1));
                c2.append(" if( $2.equals(\"")
                    .append(pn)
                    .append("\") ){ return ($w)w.")
                    .append(method.getName())
                    .append("(); }");
                pts.put(pn, method.getReturnType());
            } else if ((matcher = ReflectUtils.SETTER_METHOD_DESC_PATTERN.matcher(md)).matches()) {
                /**
                 * setXXX方法
                 */
                Class<?> pt = method.getParameterTypes()[0];
                String pn = propertyName(matcher.group(1));
                c1.append(" if( $2.equals(\"")
                    .append(pn)
                    .append("\") ){ w.")
                    .append(method.getName())
                    .append('(')
                    .append(arg(pt, "$3"))
                    .append("); return; }");
                pts.put(pn, pt);
            }
        }
        /**
         * 同样的，前面的get/set都return了，如果走到这个代码，就得抛异常了
         */
        c1.append(" throw new ")
            .append(NoSuchPropertyException.class.getName())
            .append("(\"Not found property \\\"\"+$2+\"\\\" field or setter method in class ")
            .append(c.getName())
            .append(".\"); }");
        c2.append(" throw new ")
            .append(NoSuchPropertyException.class.getName())
            .append("(\"Not found property \\\"\"+$2+\"\\\" field or getter method in class ")
            .append(c.getName())
            .append(".\"); }");

        // make class
        long id = WRAPPER_CLASS_COUNTER.getAndIncrement();
        ClassGenerator cc = ClassGenerator.newInstance(cl);
        cc.setClassName(c.getName() + "DubboWrap" + id);
        cc.setSuperClass(Wrapper.class);

        cc.addDefaultConstructor();
        // property name array.
        cc.addField("public static String[] pns;");
        // property type map.
        cc.addField("public static " + Map.class.getName() + " pts;");
        // all method name array.
        cc.addField("public static String[] mns;");
        // declared method name array.
        cc.addField("public static String[] dmns;");
        for (int i = 0, len = ms.size(); i < len; i++) {
            cc.addField("public static Class[] mts" + i + ";");
        }

        cc.addMethod("public String[] getPropertyNames(){ return pns; }");
        cc.addMethod("public boolean hasProperty(String n){ return pts.containsKey($1); }");
        cc.addMethod("public Class getPropertyType(String n){ return (Class)pts.get($1); }");
        cc.addMethod("public String[] getMethodNames(){ return mns; }");
        cc.addMethod("public String[] getDeclaredMethodNames(){ return dmns; }");
        cc.addMethod(c1.toString());
        cc.addMethod(c2.toString());
        cc.addMethod(c3.toString());

        try {
            Class<?> wc = cc.toClass(c);
            // setup static field.
            // 设置声明的static 属性值
            wc.getField("pts").set(null, pts);
            wc.getField("pns").set(null, pts.keySet().toArray(new String[0]));
            wc.getField("mns").set(null, mns.toArray(new String[0]));
            wc.getField("dmns").set(null, dmns.toArray(new String[0]));
            int ix = 0;
            for (Method m : ms.values()) {
                wc.getField("mts" + ix++).set(null, m.getParameterTypes());
            }
            return (Wrapper) wc.getDeclaredConstructor().newInstance();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            cc.release();
            pts.clear();
            ms.clear();
            mns.clear();
            dmns.clear();
        }
    }

    private static String arg(Class<?> cl, String name) {
        if (cl.isPrimitive()) {
            if (cl == Boolean.TYPE) {
                return "((Boolean)" + name + ").booleanValue()";
            }
            if (cl == Byte.TYPE) {
                return "((Byte)" + name + ").byteValue()";
            }
            if (cl == Character.TYPE) {
                return "((Character)" + name + ").charValue()";
            }
            if (cl == Double.TYPE) {
                return "((Number)" + name + ").doubleValue()";
            }
            if (cl == Float.TYPE) {
                return "((Number)" + name + ").floatValue()";
            }
            if (cl == Integer.TYPE) {
                return "((Number)" + name + ").intValue()";
            }
            if (cl == Long.TYPE) {
                return "((Number)" + name + ").longValue()";
            }
            if (cl == Short.TYPE) {
                return "((Number)" + name + ").shortValue()";
            }
            throw new RuntimeException("Unknown primitive type: " + cl.getName());
        }
        return "(" + ReflectUtils.getName(cl) + ")" + name;
    }

    /**
     * <p>
     * {@link Wrapper#makeWrapper(java.lang.Class)}中调用
     * </p>
     *
     * @param cs   参数类型数组
     * @param name 实际传参数组
     * @return
     */
    private static String args(Class<?>[] cs, String name) {
        int len = cs.length;
        if (len == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(arg(cs[i], name + "[" + i + "]"));
        }
        return sb.toString();
    }

    private static String propertyName(String pn) {
        return pn.length() == 1 || Character.isLowerCase(pn.charAt(1))
            ? Character.toLowerCase(pn.charAt(0)) + pn.substring(1)
            : pn;
    }

    /**
     * get property name array.
     *
     * @return property name array.
     */
    public abstract String[] getPropertyNames();

    /**
     * get property type.
     *
     * @param pn property name.
     * @return Property type or nul.
     */
    public abstract Class<?> getPropertyType(String pn);

    /**
     * has property.
     *
     * @param name property name.
     * @return has or has not.
     */
    public abstract boolean hasProperty(String name);

    /**
     * get property value.
     *
     * @param instance instance.
     * @param pn       property name.
     * @return value.
     */
    public abstract Object getPropertyValue(Object instance, String pn)
        throws NoSuchPropertyException, IllegalArgumentException;

    /**
     * set property value.
     *
     * @param instance instance.
     * @param pn       property name.
     * @param pv       property value.
     */
    public abstract void setPropertyValue(Object instance, String pn, Object pv)
        throws NoSuchPropertyException, IllegalArgumentException;

    /**
     * get property value.
     *
     * @param instance instance.
     * @param pns      property name array.
     * @return value array.
     */
    public Object[] getPropertyValues(Object instance, String[] pns)
        throws NoSuchPropertyException, IllegalArgumentException {
        Object[] ret = new Object[pns.length];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = getPropertyValue(instance, pns[i]);
        }
        return ret;
    }

    /**
     * set property value.
     *
     * @param instance instance.
     * @param pns      property name array.
     * @param pvs      property value array.
     */
    public void setPropertyValues(Object instance, String[] pns, Object[] pvs)
        throws NoSuchPropertyException, IllegalArgumentException {
        if (pns.length != pvs.length) {
            throw new IllegalArgumentException("pns.length != pvs.length");
        }

        for (int i = 0; i < pns.length; i++) {
            setPropertyValue(instance, pns[i], pvs[i]);
        }
    }

    /**
     * get method name array.
     *
     * @return method name array.
     */
    public abstract String[] getMethodNames();

    /**
     * get method name array.
     *
     * @return method name array.
     */
    public abstract String[] getDeclaredMethodNames();

    /**
     * has method.
     *
     * @param name method name.
     * @return has or has not.
     */
    public boolean hasMethod(String name) {
        for (String mn : getMethodNames()) {
            if (mn.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * invoke method.
     *
     * @param instance instance.
     * @param mn       method name.
     * @param types
     * @param args     argument array.
     * @return return value.
     */
    public abstract Object invokeMethod(Object instance, String mn, Class<?>[] types, Object[] args)
        throws NoSuchMethodException, InvocationTargetException;
}
