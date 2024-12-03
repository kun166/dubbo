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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelAwareExtensionProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ExtensionDirector is a scoped extension loader manager.
 *
 * <p></p>
 * <p>ExtensionDirector supports multiple levels, and the child can inherit the parent's extension instances. </p>
 * <p>The way to find and create an extension instance is similar to Java classloader.</p>
 */
public class ExtensionDirector implements ExtensionAccessor {

    private final ConcurrentMap<Class<?>, ExtensionLoader<?>> extensionLoadersMap = new ConcurrentHashMap<>(64);

    /**
     * 这是一个缓存,key是一个标有{@link SPI}注解的接口class
     * value是这个{@link SPI}的{@link SPI#scope()}
     */
    private final ConcurrentMap<Class<?>, ExtensionScope> extensionScopeMap = new ConcurrentHashMap<>(64);
    private final ExtensionDirector parent;
    /**
     * 如果是{@link FrameworkModel},这个是{@link ExtensionScope#FRAMEWORK}
     */
    private final ExtensionScope scope;

    /**
     * 在{@link ScopeModel#initialize()}中添加
     * {@link ScopeModelAwareExtensionProcessor}
     */
    private final List<ExtensionPostProcessor> extensionPostProcessors = new ArrayList<>();
    /**
     * 在{@link ScopeModel#initialize()}中传递了
     * {@link FrameworkModel}
     */
    private final ScopeModel scopeModel;
    private final AtomicBoolean destroyed = new AtomicBoolean();

    /**
     * <p>
     * {@link ScopeModel#initialize()}中调用
     * </p>
     * <p>
     * 根据创建的地方不同，传参也不同
     * {@link org.apache.dubbo.rpc.model.ApplicationModel}
     * {@link FrameworkModel}
     * {@link org.apache.dubbo.rpc.model.ModuleModel}
     * </p>
     *
     * @param parent
     * @param scope      传递的是{@link ExtensionScope#FRAMEWORK}
     * @param scopeModel
     */
    public ExtensionDirector(ExtensionDirector parent, ExtensionScope scope, ScopeModel scopeModel) {
        this.parent = parent;
        this.scope = scope;
        this.scopeModel = scopeModel;
    }

    /**
     * {@link ScopeModel#initialize()}中调用
     *
     * @param processor
     */
    public void addExtensionPostProcessor(ExtensionPostProcessor processor) {
        if (!this.extensionPostProcessors.contains(processor)) {
            this.extensionPostProcessors.add(processor);
        }
    }

    public List<ExtensionPostProcessor> getExtensionPostProcessors() {
        return extensionPostProcessors;
    }

    @Override
    public ExtensionDirector getExtensionDirector() {
        return this;
    }

    /**
     * {@link org.apache.dubbo.config.spring.context.DubboSpringInitializer#customize}
     * 中调用
     * <p>
     * 这个方法很重要
     *
     * @param type
     * @param <T>
     * @return
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        checkDestroyed();
        if (type == null) {
            // 传递的参数不能为空
            throw new IllegalArgumentException("Extension type == null");
        }
        if (!type.isInterface()) {
            // 参数必须为接口
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        if (!withExtensionAnnotation(type)) {
            /**
             * 接口的注解必须有{@link SPI}
             */
            throw new IllegalArgumentException("Extension type (" + type
                + ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }

        // 1. find in local cache
        // 先从缓存中找
        ExtensionLoader<T> loader = (ExtensionLoader<T>) extensionLoadersMap.get(type);
        /**
         * 先根据传入的参数type，去缓存中查找对应的{@link ExtensionScope}
         * 如果缓存中找不到，则从注解里面获取，然后放到缓存中
         */
        ExtensionScope scope = extensionScopeMap.get(type);
        if (scope == null) {
            // 获取type的SPI
            SPI annotation = type.getAnnotation(SPI.class);
            scope = annotation.scope();
            extensionScopeMap.put(type, scope);
        }

        if (loader == null && scope == ExtensionScope.SELF) {
            // create an instance in self scope
            /**
             * 如果scope是{@link ExtensionScope#SELF},则通过下面的方式创建
             */
            loader = createExtensionLoader0(type);
        }

        // 2. find in parent
        if (loader == null) {
            if (this.parent != null) {
                loader = this.parent.getExtensionLoader(type);
            }
        }

        // 3. create it
        if (loader == null) {
            loader = createExtensionLoader(type);
        }

        return loader;
    }

    /**
     * {@link ExtensionDirector#getExtensionLoader(java.lang.Class)}中调用
     * <p>
     * type的注解{@link SPI#scope()}返回的不是{@link ExtensionScope#SELF}
     * </p>
     *
     * @param type
     * @param <T>
     * @return
     */
    private <T> ExtensionLoader<T> createExtensionLoader(Class<T> type) {
        ExtensionLoader<T> loader = null;
        if (isScopeMatched(type)) {
            // if scope is matched, just create it
            loader = createExtensionLoader0(type);
        }
        return loader;
    }

    /**
     * type的{@link SPI}注解,{@link SPI#scope()}为{@link ExtensionScope#SELF}的,通过此方法创建
     * type的{@link SPI}注解,{@link SPI#scope()}，和本实例的{@link ExtensionDirector#scopeModel}相同，也通过此方法创建
     * <p>
     * 关于第二点，每一个{@link ExtensionDirector}都有且唯一属于一个{@link ScopeModel},
     * 在创建它的对象创建它的时候就指明了{@link ExtensionDirector#scopeModel},这个值即为创建它的对象
     * {@link FrameworkModel}
     * {@link org.apache.dubbo.rpc.model.ApplicationModel}
     * {@link ModuleModel}
     *
     * <p>
     * {@link ExtensionDirector#getExtensionLoader(java.lang.Class)}
     * 中调用
     * </p>
     *
     * @param type
     * @param <T>
     * @return
     */
    @SuppressWarnings("unchecked")
    private <T> ExtensionLoader<T> createExtensionLoader0(Class<T> type) {
        checkDestroyed();
        ExtensionLoader<T> loader;
        /**
         * 如果没有的话,就创建了
         */
        extensionLoadersMap.putIfAbsent(type, new ExtensionLoader<T>(type, this, scopeModel));
        loader = (ExtensionLoader<T>) extensionLoadersMap.get(type);
        return loader;
    }

    /**
     * {@link ExtensionDirector#createExtensionLoader(java.lang.Class)}中调用
     *
     * @param type
     * @return
     */
    private boolean isScopeMatched(Class<?> type) {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        return defaultAnnotation.scope().equals(scope);
    }

    /**
     * 判断传入的type是否有{@link SPI}注解
     * <p>
     * {@link ExtensionDirector#getExtensionLoader(java.lang.Class)}中调用
     * </p>
     *
     * @param type
     * @return
     */
    private static boolean withExtensionAnnotation(Class<?> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    public ExtensionDirector getParent() {
        return parent;
    }

    public void removeAllCachedLoader() {
    }

    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            for (ExtensionLoader<?> extensionLoader : extensionLoadersMap.values()) {
                extensionLoader.destroy();
            }
            extensionLoadersMap.clear();
            extensionScopeMap.clear();
            extensionPostProcessors.clear();
        }
    }

    private void checkDestroyed() {
        if (destroyed.get()) {
            throw new IllegalStateException("ExtensionDirector is destroyed");
        }
    }
}
