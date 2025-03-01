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
package org.apache.dubbo.common.extension.inject;

import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionAccessor;
import org.apache.dubbo.common.extension.ExtensionInjector;
import org.apache.dubbo.common.extension.ExtensionLoader;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AdaptiveExtensionInjector
 */
@Adaptive
public class AdaptiveExtensionInjector implements ExtensionInjector, Lifecycle {

    /**
     * <p>
     * {@link AdaptiveExtensionInjector#initialize()}方法中赋值
     * </p>
     * adaptive={@link org.apache.dubbo.common.extension.inject.AdaptiveExtensionInjector}
     * spi={@link org.apache.dubbo.common.extension.inject.SpiExtensionInjector}
     * scopeBean={@link org.apache.dubbo.common.beans.ScopeBeanExtensionInjector}
     */
    private Collection<ExtensionInjector> injectors = Collections.emptyList();
    private ExtensionAccessor extensionAccessor;

    public AdaptiveExtensionInjector() {
    }

    @Override
    public void setExtensionAccessor(final ExtensionAccessor extensionAccessor) {
        this.extensionAccessor = extensionAccessor;
    }

    /**
     * <p>
     * 因为实现了{@link Lifecycle}接口
     * {@link ExtensionLoader#initExtension(java.lang.Object)}中调用
     * </p>
     *
     * @throws IllegalStateException
     */
    @Override
    public void initialize() throws IllegalStateException {
        /**
         * ExtensionInjector的{@link org.apache.dubbo.common.extension.SPI}有下面四个扩展
         * adaptive={@link org.apache.dubbo.common.extension.inject.AdaptiveExtensionInjector}
         * spi={@link org.apache.dubbo.common.extension.inject.SpiExtensionInjector}
         * scopeBean={@link org.apache.dubbo.common.beans.ScopeBeanExtensionInjector}
         * spring={@link org.apache.dubbo.config.spring.extension.SpringExtensionInjector}
         */
        ExtensionLoader<ExtensionInjector> loader = extensionAccessor.getExtensionLoader(ExtensionInjector.class);
        injectors = loader
            /**
             * 这个是获取扩展的key
             */
            .getSupportedExtensions()
            .stream()
            /**
             * 根据name去获取{@link ExtensionInjector}
             */
            .map(loader::getExtension)
            .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
    }

    /**
     * <p>
     * {@link ExtensionLoader#injectExtension(java.lang.Object)}中调用
     * </p>
     *
     * @param type object type. setXxx方法的参数类型
     * @param name object name. setXxx方法的xxx，首字母小写
     * @param <T>
     * @return
     */
    @Override
    public <T> T getInstance(final Class<T> type, final String name) {
        return injectors.stream()
            /**
             * adaptive={@link org.apache.dubbo.common.extension.inject.AdaptiveExtensionInjector}
             * {@link }
             * spi={@link org.apache.dubbo.common.extension.inject.SpiExtensionInjector}
             * scopeBean={@link org.apache.dubbo.common.beans.ScopeBeanExtensionInjector}
             */
            .map(injector -> injector.getInstance(type, name))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    @Override
    public void start() throws IllegalStateException {
    }

    @Override
    public void destroy() throws IllegalStateException {
    }
}
