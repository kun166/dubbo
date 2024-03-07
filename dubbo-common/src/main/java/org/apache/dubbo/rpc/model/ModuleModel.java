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
package org.apache.dubbo.rpc.model;

import org.apache.dubbo.common.CommonScopeModelInitializer;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ModuleEnvironment;
import org.apache.dubbo.common.context.ModuleExt;
import org.apache.dubbo.common.deploy.ApplicationDeployer;
import org.apache.dubbo.common.deploy.DeployState;
import org.apache.dubbo.common.deploy.ModuleDeployer;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.config.context.ModuleConfigManager;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.locks.Lock;

/**
 * Model of a service module
 */
public class ModuleModel extends ScopeModel {
    private static final Logger logger = LoggerFactory.getLogger(ModuleModel.class);

    public static final String NAME = "ModuleModel";

    /**
     * {@link ModuleModel#ModuleModel(org.apache.dubbo.rpc.model.ApplicationModel, boolean)}中赋值
     * 传递的是创建本实例的{@link ApplicationModel}实例
     */
    private final ApplicationModel applicationModel;
    private volatile ModuleServiceRepository serviceRepository;
    private volatile ModuleEnvironment moduleEnvironment;
    private volatile ModuleConfigManager moduleConfigManager;

    /**
     * {@link org.apache.dubbo.config.ConfigScopeModelInitializer#initializeModuleModel(org.apache.dubbo.rpc.model.ModuleModel)}
     * 中赋值
     */
    private volatile ModuleDeployer deployer;

    /**
     * {@link org.apache.dubbo.config.spring.context.DubboSpringInitializer#initContext(org.apache.dubbo.config.spring.context.DubboSpringInitContext, org.springframework.beans.factory.support.BeanDefinitionRegistry, org.springframework.beans.factory.config.ConfigurableListableBeanFactory)}
     * 中设置成了true
     */
    private boolean lifeCycleManagedExternally = false;

    /**
     * {@link ApplicationModel#newModule()}中调用
     *
     * @param applicationModel
     */
    protected ModuleModel(ApplicationModel applicationModel) {
        this(applicationModel, false);
    }

    /**
     * {@link ModuleModel#ModuleModel(org.apache.dubbo.rpc.model.ApplicationModel)}中调用
     *
     * @param applicationModel
     * @param isInternal
     */
    protected ModuleModel(ApplicationModel applicationModel, boolean isInternal) {
        super(applicationModel, ExtensionScope.MODULE, isInternal);
        synchronized (instLock) {
            Assert.notNull(applicationModel, "ApplicationModel can not be null");
            this.applicationModel = applicationModel;
            applicationModel.addModule(this, isInternal);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(getDesc() + " is created");
            }

            initialize();

            this.serviceRepository = new ModuleServiceRepository(this);

            initModuleExt();

            ExtensionLoader<ScopeModelInitializer> initializerExtensionLoader =
                this.getExtensionLoader(ScopeModelInitializer.class);
            /**
             * dubbo-common={@link org.apache.dubbo.common.CommonScopeModelInitializer}
             * {@link CommonScopeModelInitializer#initializeModuleModel(org.apache.dubbo.rpc.model.ModuleModel)}
             * 如果是spring标签形式的，通过打断点，这个地方有如下14个：
             * {@link org.apache.dubbo.security.cert.CertScopeModelInitializer}
             * {@link org.apache.dubbo.rpc.cluster.ClusterScopeModelInitializer}
             * {@link org.apache.dubbo.common.CommonScopeModelInitializer}
             * {@link org.apache.dubbo.config.ConfigScopeModelInitializer}
             * {@link org.apache.dubbo.config.spring.SpringScopeModelInitializer}
             * {@link org.apache.dubbo.metadata.report.MetadataScopeModelInitializer}
             * {@link org.apache.dubbo.metrics.MetricsScopeModelInitializer}
             * {@link org.apache.dubbo.registry.RegistryScopeModelInitializer}
             * {@link org.apache.dubbo.remoting.RemotingScopeModelInitializer}
             * {@link org.apache.dubbo.common.serialize.fastjson2.Fastjson2ScopeModelInitializer}
             * {@link org.apache.dubbo.common.serialize.hessian2.Hessian2ScopeModelInitializer}
             * {@link org.apache.dubbo.qos.QosScopeModelInitializer}
             * {@link org.apache.dubbo.rpc.RpcScopeModelInitializer}
             * {@link org.apache.dubbo.rpc.cluster.router.xds.XdsScopeModelInitializer}
             */
            Set<ScopeModelInitializer> initializers = initializerExtensionLoader.getSupportedExtensionInstances();
            for (ScopeModelInitializer initializer : initializers) {
                initializer.initializeModuleModel(this);
            }
            Assert.notNull(getServiceRepository(), "ModuleServiceRepository can not be null");
            Assert.notNull(getConfigManager(), "ModuleConfigManager can not be null");
            Assert.assertTrue(getConfigManager().isInitialized(), "ModuleConfigManager can not be initialized");

            // notify application check state
            /**
             * {@link org.apache.dubbo.config.ConfigScopeModelInitializer#initializeApplicationModel(org.apache.dubbo.rpc.model.ApplicationModel)}
             * 中设置
             */
            ApplicationDeployer applicationDeployer = applicationModel.getDeployer();
            if (applicationDeployer != null) {
                applicationDeployer.notifyModuleChanged(this, DeployState.PENDING);
            }
        }
    }

    // already synchronized in constructor
    private void initModuleExt() {
        Set<ModuleExt> exts = this.getExtensionLoader(ModuleExt.class).getSupportedExtensionInstances();
        for (ModuleExt ext : exts) {
            ext.initialize();
        }
    }

    @Override
    protected void onDestroy() {
        synchronized (instLock) {
            // 1. remove from applicationModel
            applicationModel.removeModule(this);

            // 2. set stopping
            if (deployer != null) {
                deployer.preDestroy();
            }

            // 3. release services
            if (deployer != null) {
                deployer.postDestroy();
            }

            // destroy other resources
            notifyDestroy();

            if (serviceRepository != null) {
                serviceRepository.destroy();
                serviceRepository = null;
            }

            if (moduleEnvironment != null) {
                moduleEnvironment.destroy();
                moduleEnvironment = null;
            }

            if (moduleConfigManager != null) {
                moduleConfigManager.destroy();
                moduleConfigManager = null;
            }

            // destroy application if none pub module
            applicationModel.tryDestroy();
        }
    }

    public ApplicationModel getApplicationModel() {
        return applicationModel;
    }

    public ModuleServiceRepository getServiceRepository() {
        return serviceRepository;
    }

    @Override
    public void addClassLoader(ClassLoader classLoader) {
        super.addClassLoader(classLoader);
        if (moduleEnvironment != null) {
            moduleEnvironment.refreshClassLoaders();
        }
    }

    @Override
    public ModuleEnvironment modelEnvironment() {
        if (moduleEnvironment == null) {
            moduleEnvironment =
                (ModuleEnvironment) this.getExtensionLoader(ModuleExt.class).getExtension(ModuleEnvironment.NAME);
        }
        return moduleEnvironment;
    }

    /**
     * 通过拓展，获取{@link ModuleConfigManager}
     * <p>
     * {@link org.apache.dubbo.config.spring.ServiceBean#afterPropertiesSet()}中调用
     * </p>
     * moduleEnvironment={@link ModuleEnvironment}
     * moduleConfig={@link ModuleConfigManager}
     *
     * @return
     */
    public ModuleConfigManager getConfigManager() {
        if (moduleConfigManager == null) {
            moduleConfigManager = (ModuleConfigManager)
                this.getExtensionLoader(ModuleExt.class).getExtension(ModuleConfigManager.NAME);
        }
        return moduleConfigManager;
    }

    public ModuleDeployer getDeployer() {
        return deployer;
    }

    /**
     * {@link org.apache.dubbo.config.ConfigScopeModelInitializer#initializeModuleModel(org.apache.dubbo.rpc.model.ModuleModel)}
     * 中调用
     *
     * @param deployer
     */
    public void setDeployer(ModuleDeployer deployer) {
        this.deployer = deployer;
    }

    @Override
    protected Lock acquireDestroyLock() {
        return getApplicationModel().getFrameworkModel().acquireDestroyLock();
    }

    /**
     * for ut only
     */
    @Deprecated
    public void setModuleEnvironment(ModuleEnvironment moduleEnvironment) {
        this.moduleEnvironment = moduleEnvironment;
    }

    public ConsumerModel registerInternalConsumer(Class<?> internalService, URL url) {
        ServiceMetadata serviceMetadata = new ServiceMetadata();
        serviceMetadata.setVersion(url.getVersion());
        serviceMetadata.setGroup(url.getGroup());
        serviceMetadata.setDefaultGroup(url.getGroup());
        serviceMetadata.setServiceInterfaceName(internalService.getName());
        serviceMetadata.setServiceType(internalService);
        String serviceKey = URL.buildKey(internalService.getName(), url.getGroup(), url.getVersion());
        serviceMetadata.setServiceKey(serviceKey);

        ConsumerModel consumerModel = new ConsumerModel(
            serviceMetadata.getServiceKey(),
            "jdk",
            serviceRepository.lookupService(serviceMetadata.getServiceInterfaceName()),
            this,
            serviceMetadata,
            new HashMap<>(0),
            ClassUtils.getClassLoader(internalService));

        logger.info("Dynamically registering consumer model " + serviceKey + " into model " + this.getDesc());
        serviceRepository.registerConsumer(consumerModel);
        return consumerModel;
    }

    public boolean isLifeCycleManagedExternally() {
        return lifeCycleManagedExternally;
    }


    /**
     * {@link org.apache.dubbo.config.spring.context.DubboSpringInitializer#initContext(org.apache.dubbo.config.spring.context.DubboSpringInitContext, org.springframework.beans.factory.support.BeanDefinitionRegistry, org.springframework.beans.factory.config.ConfigurableListableBeanFactory)}
     * 中设置为true
     *
     * @param lifeCycleManagedExternally
     */
    public void setLifeCycleManagedExternally(boolean lifeCycleManagedExternally) {
        this.lifeCycleManagedExternally = lifeCycleManagedExternally;
    }
}
