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
package org.apache.dubbo.config.spring.context;

import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ModelConstants;
import org.apache.dubbo.rpc.model.ModuleModel;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;

/**
 * Dubbo spring initialization context object
 * <p>
 * dubbo的上下文对象
 */
public class DubboSpringInitContext {

    /**
     * 在{@link DubboSpringInitializer#initContext(DubboSpringInitContext, BeanDefinitionRegistry, ConfigurableListableBeanFactory)}
     * 中被赋值
     */
    private BeanDefinitionRegistry registry;

    /**
     * 在{@link DubboSpringInitializer#initContext(DubboSpringInitContext, BeanDefinitionRegistry, ConfigurableListableBeanFactory)}
     * 中被赋值
     */
    private ConfigurableListableBeanFactory beanFactory;

    private ApplicationContext applicationContext;

    /**
     * {@link DubboSpringInitContext#setModuleModel(org.apache.dubbo.rpc.model.ModuleModel)}中设置值
     * 通过该值，可以获取{@link ApplicationModel}和{@link org.apache.dubbo.rpc.model.FrameworkModel}
     */
    private ModuleModel moduleModel;

    private final Map<String, Object> moduleAttributes = new HashMap<>();


    /**
     * {@link DubboSpringInitializer#initContext(org.apache.dubbo.config.spring.context.DubboSpringInitContext, org.springframework.beans.factory.support.BeanDefinitionRegistry, org.springframework.beans.factory.config.ConfigurableListableBeanFactory)}
     * 中设置为true
     */
    private volatile boolean bound;

    /**
     * {@link DubboSpringInitializer#initContext(org.apache.dubbo.config.spring.context.DubboSpringInitContext, org.springframework.beans.factory.support.BeanDefinitionRegistry, org.springframework.beans.factory.config.ConfigurableListableBeanFactory)}
     * 中调用
     */
    public void markAsBound() {
        bound = true;
    }

    public BeanDefinitionRegistry getRegistry() {
        return registry;
    }

    void setRegistry(BeanDefinitionRegistry registry) {
        this.registry = registry;
    }

    public ConfigurableListableBeanFactory getBeanFactory() {
        return beanFactory;
    }

    void setBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public ApplicationModel getApplicationModel() {
        return (moduleModel == null) ? null : moduleModel.getApplicationModel();
    }

    public ModuleModel getModuleModel() {
        return moduleModel;
    }

    /**
     * Change the binding ModuleModel, the ModuleModel and DubboBootstrap must be matched.
     *
     * <p>
     * {@link DubboSpringInitializer#initContext(org.apache.dubbo.config.spring.context.DubboSpringInitContext, org.springframework.beans.factory.support.BeanDefinitionRegistry, org.springframework.beans.factory.config.ConfigurableListableBeanFactory)}
     * 中调用
     * </p>
     *
     * @param moduleModel
     */
    public void setModuleModel(ModuleModel moduleModel) {
        if (bound) {
            throw new IllegalStateException("Cannot change ModuleModel after bound context");
        }
        this.moduleModel = moduleModel;
    }

    public boolean isKeepRunningOnSpringClosed() {
        return (boolean) moduleAttributes.get(ModelConstants.KEEP_RUNNING_ON_SPRING_CLOSED);
    }

    /**
     * Keep Dubbo running when spring is stopped
     *
     * @param keepRunningOnSpringClosed
     */
    public void setKeepRunningOnSpringClosed(boolean keepRunningOnSpringClosed) {
        this.setModuleAttribute(ModelConstants.KEEP_RUNNING_ON_SPRING_CLOSED, keepRunningOnSpringClosed);
    }

    public Map<String, Object> getModuleAttributes() {
        return moduleAttributes;
    }

    public void setModuleAttribute(String key, Object value) {
        this.moduleAttributes.put(key, value);
    }
}
