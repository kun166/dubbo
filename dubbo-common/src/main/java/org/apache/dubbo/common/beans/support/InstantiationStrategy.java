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
package org.apache.dubbo.common.beans.support;

import org.apache.dubbo.common.beans.factory.ScopeBeanFactory;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.model.*;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * Interface to create instance for specify type, using both in {@link ExtensionLoader} and {@link ScopeBeanFactory}.
 */
public class InstantiationStrategy {

    /**
     * {@link InstantiationStrategy#InstantiationStrategy(org.apache.dubbo.rpc.model.ScopeModelAccessor)}
     * 构造方法中赋值
     * {@link ScopeModelAwareExtensionProcessor}
     */
    private final ScopeModelAccessor scopeModelAccessor;

    public InstantiationStrategy() {
        this(null);
    }

    /**
     * <p>
     * {@link ScopeBeanFactory#initInstantiationStrategy()}中调用
     * 传参 {@link ScopeModelAwareExtensionProcessor}
     * </p>
     *
     * @param scopeModelAccessor
     */
    public InstantiationStrategy(ScopeModelAccessor scopeModelAccessor) {
        this.scopeModelAccessor = scopeModelAccessor;
    }

    /**
     * 根据传入的参数type,先获取构造函数。
     * 1,优先获取构造函数传参为{@link ScopeModel}或者其子类的构造函数
     * 2,如果没有,就使用无参构造函数
     * 3,从{@link ScopeModelAwareExtensionProcessor}中获取构造函数类型的参数值
     * 4,调用适合的构造函数，生成对象
     * <p>
     * {@link ScopeBeanFactory#createAndRegisterBean(java.lang.String, java.lang.Class)}
     * 中调用
     * {@link ExtensionLoader#createExtensionInstance(java.lang.Class)}中调用
     * </p>
     *
     * @param type
     * @param <T>
     * @return
     * @throws ReflectiveOperationException
     */
    @SuppressWarnings("unchecked")
    public <T> T instantiate(Class<T> type) throws ReflectiveOperationException {

        // should not use default constructor directly, maybe also has another constructor matched scope model arguments
        // 1. try to get default constructor
        // 不应直接使用默认构造函数，可能还有另一个与范围模型参数匹配的构造函数
        // 1.尝试获取默认构造函数
        Constructor<T> defaultConstructor = null;
        try {
            // 无参构造器
            defaultConstructor = type.getConstructor();
        } catch (NoSuchMethodException e) {
            // ignore no default constructor
            // 说明没有无参构造器
        }

        // 2. use matched constructor if found
        // 2.如果找到匹配的构造函数,使用该构造器
        List<Constructor<?>> matchedConstructors = new ArrayList<>();
        // 获取所有构造函数
        Constructor<?>[] declaredConstructors = type.getConstructors();
        for (Constructor<?> constructor : declaredConstructors) {
            if (isMatched(constructor)) {
                /**
                 * 构造函数constructor的所有入参，均是{@link ScopeModel}的子类
                 */
                matchedConstructors.add(constructor);
            }
        }
        // remove default constructor from matchedConstructors
        if (defaultConstructor != null) {
            // 如果有默认的无参构造器，从matchedConstructors中排除
            matchedConstructors.remove(defaultConstructor);
        }

        // match order:
        // 1. the only matched constructor with parameters
        // 2. default constructor if absent
        // 匹配顺序：
        // 1. 只有唯一一个匹配的有参构造
        // 2. 默认的无参构造器

        Constructor<?> targetConstructor;
        if (matchedConstructors.size() > 1) {
            // 如果有多个匹配的有参构造器，则抛出异常
            throw new IllegalArgumentException("Expect only one but found " + matchedConstructors.size()
                + " matched constructors for type: " + type.getName() + ", matched constructors: "
                + matchedConstructors);
        } else if (matchedConstructors.size() == 1) {
            // 只有唯一一个匹配的有参构造器
            targetConstructor = matchedConstructors.get(0);
        } else if (defaultConstructor != null) {
            // 默认的无参构造器
            targetConstructor = defaultConstructor;
        } else {
            // 找不到符合的构造函数
            throw new IllegalArgumentException("None matched constructor was found for type: " + type.getName());
        }

        // create instance with arguments
        Class<?>[] parameterTypes = targetConstructor.getParameterTypes();
        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            args[i] = getArgumentValueForType(parameterTypes[i]);
        }
        // 生成对象
        return (T) targetConstructor.newInstance(args);
    }

    /**
     * 获取构造函数constructor的所有参数,如果所有参数均是{@link ScopeModel}的子类，返回true
     * 否则，返回false
     * <p>
     * {@link InstantiationStrategy#instantiate(java.lang.Class)}中调用
     * </p>
     *
     * @param constructor
     * @return
     */
    private boolean isMatched(Constructor<?> constructor) {
        for (Class<?> parameterType : constructor.getParameterTypes()) {
            if (!isSupportedConstructorParameterType(parameterType)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 返回参数parameterType是否是{@link ScopeModel}的实现子类(包括它本身)
     * <p>
     * {@link InstantiationStrategy#isMatched(java.lang.reflect.Constructor)}中调用
     * </p>
     *
     * @param parameterType
     * @return
     */
    private boolean isSupportedConstructorParameterType(Class<?> parameterType) {
        /**
         * https://zhuanlan.zhihu.com/p/317784108
         * 返回参数parameterType是否是{@link ScopeModel}的子类
         */
        return ScopeModel.class.isAssignableFrom(parameterType);
    }

    /**
     * 从{@link ScopeModelAwareExtensionProcessor}中获取{@link ScopeModel}
     * 并根据参数类型，获取{@link FrameworkModel},{@link ApplicationModel},{@link ModuleModel}
     * <p>
     * {@link InstantiationStrategy#instantiate(java.lang.Class)}中调用
     * </p>
     *
     * @param parameterType
     * @return
     */
    private Object getArgumentValueForType(Class<?> parameterType) {
        // get scope mode value
        if (scopeModelAccessor != null) {
            if (parameterType == ScopeModel.class) {
                return scopeModelAccessor.getScopeModel();
            } else if (parameterType == FrameworkModel.class) {
                return scopeModelAccessor.getFrameworkModel();
            } else if (parameterType == ApplicationModel.class) {
                return scopeModelAccessor.getApplicationModel();
            } else if (parameterType == ModuleModel.class) {
                return scopeModelAccessor.getModuleModel();
            }
        }
        return null;
    }
}
