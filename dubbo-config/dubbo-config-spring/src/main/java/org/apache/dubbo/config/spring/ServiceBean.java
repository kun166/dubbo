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
package org.apache.dubbo.config.spring;

import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.spring.context.event.ServiceBeanExportedEvent;
import org.apache.dubbo.config.spring.schema.DubboBeanDefinitionParser;
import org.apache.dubbo.config.spring.util.DubboBeanUtils;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.rpc.model.ModuleModel;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;

/**
 * ServiceFactoryBean
 *
 * @export
 */
public class ServiceBean<T> extends ServiceConfig<T> implements InitializingBean, DisposableBean, ApplicationContextAware, BeanNameAware, ApplicationEventPublisherAware {

    private static final long serialVersionUID = 213195494150089726L;

    private final transient Service service;

    private transient ApplicationContext applicationContext;

    private transient String beanName;

    private ApplicationEventPublisher applicationEventPublisher;

    public ServiceBean(ApplicationContext applicationContext) {
        super();
        this.service = null;
        this.applicationContext = applicationContext;
        this.setScopeModel(DubboBeanUtils.getModuleModel(applicationContext));
    }

    /**
     * {@link DubboBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, java.lang.Class, boolean)}
     * 中设置了{@link ServiceBean}的{@link AbstractBeanDefinition#setAutowireMode(int)}为{@link AbstractBeanDefinition#AUTOWIRE_CONSTRUCTOR}
     * 关于{@link AbstractBeanDefinition#autowireMode}可以参考：https://blog.csdn.net/ystyaoshengting/article/details/120488094
     *
     * @param applicationContext
     * @param moduleModel
     */
    public ServiceBean(ApplicationContext applicationContext, ModuleModel moduleModel) {
        super(moduleModel);
        this.service = null;
        this.applicationContext = applicationContext;
    }

    public ServiceBean(ApplicationContext applicationContext, Service service) {
        super(service);
        this.service = service;
        this.applicationContext = applicationContext;
        this.setScopeModel(DubboBeanUtils.getModuleModel(applicationContext));
    }

    public ServiceBean(ApplicationContext applicationContext, ModuleModel moduleModel, Service service) {
        super(moduleModel, service);
        this.service = service;
        this.applicationContext = applicationContext;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
    }

    /**
     * Gets associated {@link Service}
     *
     * @return associated {@link Service}
     */
    public Service getService() {
        return service;
    }

    /**
     * bean初始化的时候会调用
     *
     * @throws Exception
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        if (StringUtils.isEmpty(getPath())) {
            if (StringUtils.isNotEmpty(getInterface())) {
                /**
                 * 如果没有设置path,且已经设置了interfaceName,
                 * 则设置path为interfaceName
                 */
                setPath(getInterface());
            }
        }
        // register service bean
        /**
         * 下面代码感觉很重要，需要看一下
         */
        ModuleModel moduleModel = DubboBeanUtils.getModuleModel(applicationContext);
        moduleModel.getConfigManager().addService(this);
        moduleModel.getDeployer().setPending();
    }

    /**
     * Get the name of {@link ServiceBean}
     *
     * @return {@link ServiceBean}'s name
     * @since 2.6.5
     */
    @Parameter(excluded = true, attribute = false)
    public String getBeanName() {
        return this.beanName;
    }

    /**
     * @since 2.6.5
     */
    @Override
    protected void exported() {
        super.exported();
        // Publish ServiceBeanExportedEvent
        publishExportEvent();
    }

    /**
     * @since 2.6.5
     */
    private void publishExportEvent() {
        ServiceBeanExportedEvent exportEvent = new ServiceBeanExportedEvent(this);
        applicationEventPublisher.publishEvent(exportEvent);
    }

    @Override
    public void destroy() throws Exception {
        // no need to call unexport() here, see
        // org.apache.dubbo.config.spring.extension.SpringExtensionInjector.ShutdownHookListener
    }

    // merged from dubbox
    @Override
    protected Class getServiceClass(T ref) {
        if (AopUtils.isAopProxy(ref)) {
            return AopUtils.getTargetClass(ref);
        }
        return super.getServiceClass(ref);
    }

    /**
     * @param applicationEventPublisher
     * @since 2.6.5
     */
    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }
}
