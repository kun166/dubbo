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

import org.apache.dubbo.common.extension.ExtensionDirector;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.config.spring.schema.DubboNamespaceHandler;
import org.apache.dubbo.config.spring.util.DubboBeanUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.util.ObjectUtils;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dubbo spring initialization entry point
 */
public class DubboSpringInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DubboSpringInitializer.class);

    private static final Map<BeanDefinitionRegistry, DubboSpringInitContext> contextMap = new ConcurrentHashMap<>();

    private DubboSpringInitializer() {
    }

    /**
     * {@link DubboNamespaceHandler#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext)}
     * 中调用
     *
     * @param registry
     */
    public static void initialize(BeanDefinitionRegistry registry) {

        // prepare context and do customize
        // dubbo spring init 上下文?
        DubboSpringInitContext context = new DubboSpringInitContext();

        // Spring ApplicationContext may not ready at this moment (e.g. load from xml), so use registry as key
        /**
         *{@link Map#putIfAbsent(java.lang.Object, java.lang.Object)}
         *比如我们设置key为字符串"key",value为1,2,3
         * 则第一次putIfAbsent("key",1),返回null
         * 第一次putIfAbsent("key",2),返回1
         * 第一次putIfAbsent("key",3),返回1
         * get("key"),返回1
         * 即:
         * 仅有第一次设置成功,且返回null
         * 后面均设置不成功,且返回第一次设置的值
         */
        if (contextMap.putIfAbsent(registry, context) != null) {
            // 代码只执行一次，如果已经执行过，直接返回
            return;
        }

        // find beanFactory
        ConfigurableListableBeanFactory beanFactory = findBeanFactory(registry);

        // init dubbo context
        initContext(context, registry, beanFactory);
    }

    public static boolean remove(BeanDefinitionRegistry registry) {
        return contextMap.remove(registry) != null;
    }

    public static boolean remove(ApplicationContext springContext) {
        AutowireCapableBeanFactory autowireCapableBeanFactory = springContext.getAutowireCapableBeanFactory();
        for (Map.Entry<BeanDefinitionRegistry, DubboSpringInitContext> entry : contextMap.entrySet()) {
            DubboSpringInitContext initContext = entry.getValue();
            if (initContext.getApplicationContext() == springContext
                || initContext.getBeanFactory() == autowireCapableBeanFactory
                || initContext.getRegistry() == autowireCapableBeanFactory) {
                DubboSpringInitContext context = contextMap.remove(entry.getKey());
                logger.info("Unbind " + safeGetModelDesc(context.getModuleModel()) + " from spring container: "
                    + ObjectUtils.identityToString(entry.getKey()));
                return true;
            }
        }
        return false;
    }

    static Map<BeanDefinitionRegistry, DubboSpringInitContext> getContextMap() {
        return contextMap;
    }

    static DubboSpringInitContext findBySpringContext(ApplicationContext applicationContext) {
        for (DubboSpringInitContext initContext : contextMap.values()) {
            if (initContext.getApplicationContext() == applicationContext) {
                return initContext;
            }
        }
        return null;
    }

    /**
     * {@link DubboSpringInitializer#initialize(org.springframework.beans.factory.support.BeanDefinitionRegistry)}
     * 中调用
     *
     * @param context     dubbo spring 初始化上下文
     * @param registry    大概率是个{@link org.springframework.beans.factory.support.DefaultListableBeanFactory}
     * @param beanFactory 同registry
     */
    private static void initContext(DubboSpringInitContext context, BeanDefinitionRegistry registry, ConfigurableListableBeanFactory beanFactory) {
        // 先把两者设置进去
        context.setRegistry(registry);
        context.setBeanFactory(beanFactory);

        // customize context, you can change the bind module model via DubboSpringInitCustomizer SPI
        // 自定义上下文，您可以通过DubboSpringInitCustomizer SPI更改绑定模块模型
        // 目前这个定制为空，如果有需要，可以自己定制
        customize(context);

        // init ModuleModel
        /**
         * 初始化 ModuleModel。
         * 这个很重要：
         * 比如{@link org.apache.dubbo.config.spring.ServiceBean}的加载模式{@link AbstractBeanDefinition#autowireMode}
         * 是{@link AutowireCapableBeanFactory#AUTOWIRE_CONSTRUCTOR},构造器中就需要这个对象
         */
        ModuleModel moduleModel = context.getModuleModel();
        if (moduleModel == null) {
            ApplicationModel applicationModel;
            if (findContextForApplication(ApplicationModel.defaultModel()) == null) {
                // first spring context use default application instance
                /**
                 *{@link ApplicationModel#defaultModel()}返回的是{@link FrameworkModel.defaultModel().defaultApplication()},
                 * 即：{@link FrameworkModel#defaultModel()}的{@link FrameworkModel#defaultApplication()}
                 */
                applicationModel = ApplicationModel.defaultModel();
                logger.info("Use default application: " + applicationModel.getDesc());
            } else {
                // create a new application instance for later spring context
                /**
                 * 说明{@link ApplicationModel#defaultModel()}已经绑定到了其它{@link DubboSpringInitContext}上了
                 * 重新生成一个
                 */
                applicationModel = FrameworkModel.defaultModel().newApplication();
                logger.info("Create new application: " + applicationModel.getDesc());
            }

            // init ModuleModel
            /**
             * 从这里可以看到,{@link DubboSpringInitContext#moduleModel}
             * 是从{@link ApplicationModel#getDefaultModule()}获取的
             */
            moduleModel = applicationModel.getDefaultModule();
            context.setModuleModel(moduleModel);
            logger.info("Use default module model of target application: " + moduleModel.getDesc());
        } else {
            // 说明这个moduleModel已经在定制化的代码中，设置了
            logger.info("Use module model from customizer: " + moduleModel.getDesc());
        }
        logger.info(
            "Bind " + moduleModel.getDesc() + " to spring container: " + ObjectUtils.identityToString(registry));

        // set module attributes
        Map<String, Object> moduleAttributes = context.getModuleAttributes();
        if (moduleAttributes.size() > 0) {
            moduleModel.getAttributes().putAll(moduleAttributes);
        }

        // bind dubbo initialization context to spring context
        /**
         * 1,将参数中的context,以class 全限定名为beanName,以context为singletonObject,
         * 调用{@link DefaultListableBeanFactory#registerSingleton(java.lang.String, java.lang.Object)}注册bean
         * 2,将参数中的context的{@link DubboSpringInitContext#getApplicationModel()},以相同方式注册到spring中
         * 3,将参数中的context的{@link DubboSpringInitContext#getModuleModel()},以相同方式注册到spring中
         */
        registerContextBeans(beanFactory, context);

        // mark context as bound
        context.markAsBound();
        moduleModel.setLifeCycleManagedExternally(true);

        // register common beans
        /**
         * 下面逻辑很重要，有时间再看吧
         */
        DubboBeanUtils.registerCommonBeans(registry);
    }

    private static String safeGetModelDesc(ScopeModel scopeModel) {
        return scopeModel != null ? scopeModel.getDesc() : null;
    }

    private static ConfigurableListableBeanFactory findBeanFactory(BeanDefinitionRegistry registry) {
        ConfigurableListableBeanFactory beanFactory;
        if (registry instanceof ConfigurableListableBeanFactory) {
            beanFactory = (ConfigurableListableBeanFactory) registry;
        } else if (registry instanceof GenericApplicationContext) {
            GenericApplicationContext genericApplicationContext = (GenericApplicationContext) registry;
            beanFactory = genericApplicationContext.getBeanFactory();
        } else {
            throw new IllegalStateException("Can not find Spring BeanFactory from registry: "
                + registry.getClass().getName());
        }
        return beanFactory;
    }

    /**
     * 1,将参数中的context,以class 全限定名为beanName,以context为singletonObject,
     * 调用{@link DefaultListableBeanFactory#registerSingleton(java.lang.String, java.lang.Object)}注册bean
     * 2,将参数中的context的{@link DubboSpringInitContext#getApplicationModel()},以相同方式注册到spring中
     * 3,将参数中的context的{@link DubboSpringInitContext#getModuleModel()},以相同方式注册到spring中
     * <p>
     * {@link DubboSpringInitializer#initContext(org.apache.dubbo.config.spring.context.DubboSpringInitContext, org.springframework.beans.factory.support.BeanDefinitionRegistry, org.springframework.beans.factory.config.ConfigurableListableBeanFactory)}
     * 中调用
     * </p>
     *
     * @param beanFactory
     * @param context
     */
    private static void registerContextBeans(ConfigurableListableBeanFactory beanFactory,
                                             DubboSpringInitContext context) {
        // register singleton
        registerSingleton(beanFactory, context);
        registerSingleton(beanFactory, context.getApplicationModel());
        registerSingleton(beanFactory, context.getModuleModel());
    }

    /**
     * 以传入的bean的class的全类名为beanName,以bean为singletonObject,
     * 调用{@link DefaultListableBeanFactory#registerSingleton(java.lang.String, java.lang.Object)}注册bean
     * <p>
     * {@link DubboSpringInitializer#registerContextBeans(org.springframework.beans.factory.config.ConfigurableListableBeanFactory, org.apache.dubbo.config.spring.context.DubboSpringInitContext)}
     * 中调用
     * </p>
     *
     * @param beanFactory
     * @param bean
     */
    private static void registerSingleton(ConfigurableListableBeanFactory beanFactory, Object bean) {
        beanFactory.registerSingleton(bean.getClass().getName(), bean);
    }

    /**
     * 从{@link DubboSpringInitializer#contextMap}中,寻找{@link DubboSpringInitContext#moduleModel}的
     * {@link ModuleModel#applicationModel}为传入的applicationModel的{@link DubboSpringInitContext}
     * <p>
     * {@link DubboSpringInitializer#initContext(org.apache.dubbo.config.spring.context.DubboSpringInitContext, org.springframework.beans.factory.support.BeanDefinitionRegistry, org.springframework.beans.factory.config.ConfigurableListableBeanFactory)}
     * 中调用
     * </p>
     *
     * @param applicationModel
     * @return
     */
    private static DubboSpringInitContext findContextForApplication(ApplicationModel applicationModel) {
        for (DubboSpringInitContext initializationContext : contextMap.values()) {
            if (initializationContext.getApplicationModel() == applicationModel) {
                return initializationContext;
            }
        }
        return null;
    }

    /**
     * 定制化,寻找定制的{@link DubboSpringInitCustomizer}实现，
     * 调用其{@link DubboSpringInitCustomizer#customize(org.apache.dubbo.config.spring.context.DubboSpringInitContext)}
     * 接口
     * <p>
     * {@link DubboSpringInitializer#initContext(org.apache.dubbo.config.spring.context.DubboSpringInitContext, org.springframework.beans.factory.support.BeanDefinitionRegistry, org.springframework.beans.factory.config.ConfigurableListableBeanFactory)}
     * 中调用
     * </p>
     *
     * @param context
     */
    private static void customize(DubboSpringInitContext context) {

        // find initialization customizers
        /**
         * 这个扩展目前为空，如果需要的话，自己定制
         */
        Set<DubboSpringInitCustomizer> customizers = FrameworkModel.defaultModel()
            /**
             * 调用的是{@link ExtensionDirector#getExtensionLoader(java.lang.Class)}
             */
            .getExtensionLoader(DubboSpringInitCustomizer.class)
            .getSupportedExtensionInstances();
        for (DubboSpringInitCustomizer customizer : customizers) {
            customizer.customize(context);
        }

        // load customizers in thread local holder
        DubboSpringInitCustomizerHolder customizerHolder = DubboSpringInitCustomizerHolder.get();
        customizers = customizerHolder.getCustomizers();
        for (DubboSpringInitCustomizer customizer : customizers) {
            customizer.customize(context);
        }
        customizerHolder.clearCustomizers();
    }
}
