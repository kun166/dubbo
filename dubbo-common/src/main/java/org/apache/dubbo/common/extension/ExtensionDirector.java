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
     * 存放key上的{@link SPI}
     */
    private final ConcurrentMap<Class<?>, ExtensionScope> extensionScopeMap = new ConcurrentHashMap<>(64);
    private final ExtensionDirector parent;
    private final ExtensionScope scope;

    /**
     * 在{@link ScopeModel#initialize()}中添加
     * {@link ScopeModelAwareExtensionProcessor}
     */
    private final List<ExtensionPostProcessor> extensionPostProcessors = new ArrayList<>();
    private final ScopeModel scopeModel;
    private final AtomicBoolean destroyed = new AtomicBoolean();

    /**
     * {@link ScopeModel#initialize()}中调用
     *
     * @param parent
     * @param scope
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
            throw new IllegalArgumentException("Extension type == null");
        }
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type
                + ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }

        // 1. find in local cache
        ExtensionLoader<T> loader = (ExtensionLoader<T>) extensionLoadersMap.get(type);

        ExtensionScope scope = extensionScopeMap.get(type);
        if (scope == null) {
            // 获取type的SPI
            SPI annotation = type.getAnnotation(SPI.class);
            scope = annotation.scope();
            extensionScopeMap.put(type, scope);
        }

        if (loader == null && scope == ExtensionScope.SELF) {
            // create an instance in self scope
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
        extensionLoadersMap.putIfAbsent(type, new ExtensionLoader<T>(type, this, scopeModel));
        loader = (ExtensionLoader<T>) extensionLoadersMap.get(type);
        return loader;
    }

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
