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
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.context.ApplicationExt;
import org.apache.dubbo.common.deploy.ApplicationDeployer;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.context.ConfigManager;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

/**
 * {@link ExtensionLoader}, {@code DubboBootstrap} and this class are at present designed to be
 * singleton or static (by itself totally static or uses some static fields). So the instances
 * returned from them are of process scope. If you want to support multiple dubbo servers in one
 * single process, you may need to refactor those three classes.
 * <p>
 * Represent an application which is using Dubbo and store basic metadata info for using
 * during the processing of RPC invoking.
 * <p>
 * ApplicationModel includes many ProviderModel which is about published services
 * and many Consumer Model which is about subscribed services.
 * <p>
 */
public class ApplicationModel extends ScopeModel {
    protected static final Logger LOGGER = LoggerFactory.getLogger(ApplicationModel.class);
    public static final String NAME = "ApplicationModel";

    /**
     * {@link ApplicationModel#addModule(org.apache.dubbo.rpc.model.ModuleModel, boolean)}中添加值
     * <p>
     * 更进一步讲，只要是调用了{@link ModuleModel}构造函数就会添加到这里来。
     * 1,在{@link ApplicationModel#ApplicationModel(org.apache.dubbo.rpc.model.FrameworkModel, boolean)}
     * 中创建了一个{@link ScopeModel#internalScope}为true的{@link ModuleModel}
     * 2,在{@link ApplicationModel#getDefaultModule()}
     * 中创建了一个{@link ScopeModel#internalScope}为false的{@link ModuleModel}
     * </p>
     */
    private final List<ModuleModel> moduleModels = new CopyOnWriteArrayList<>();

    /**
     * {@link ApplicationModel#addModule(org.apache.dubbo.rpc.model.ModuleModel, boolean)}中添加值
     * 当{@link ScopeModel#internalScope}为false的时候添加
     */
    private final List<ModuleModel> pubModuleModels = new CopyOnWriteArrayList<>();

    /**
     * {@link Environment}
     * {@link ApplicationModel#modelEnvironment()}中赋值
     */
    private volatile Environment environment;
    private volatile ConfigManager configManager;
    private volatile ServiceRepository serviceRepository;
    private volatile ApplicationDeployer deployer;

    /**
     * {@link FrameworkModel#defaultInstance}
     */
    private final FrameworkModel frameworkModel;

    private final ModuleModel internalModule;

    private volatile ModuleModel defaultModule;

    // internal module index is 0, default module index is 1
    private final AtomicInteger moduleIndex = new AtomicInteger(0);

    // --------- static methods ----------//

    public static ApplicationModel ofNullable(ApplicationModel applicationModel) {
        if (applicationModel != null) {
            return applicationModel;
        } else {
            return defaultModel();
        }
    }

    /**
     * During destroying the default FrameworkModel, the FrameworkModel.defaultModel() or ApplicationModel.defaultModel()
     * will return a broken model, maybe cause unpredictable problem.
     * Recommendation: Avoid using the default model as much as possible.
     * <p>
     * {@link org.apache.dubbo.config.spring.context.DubboSpringInitializer#initContext}
     * 中调用
     * </p>
     *
     * @return the global default ApplicationModel
     */
    public static ApplicationModel defaultModel() {
        // should get from default FrameworkModel, avoid out of sync
        return FrameworkModel.defaultModel().defaultApplication();
    }

    // ------------- instance methods ---------------//

    /**
     * <p>
     * {@link FrameworkModel#newApplication()}中调用
     * </p>
     *
     * @param frameworkModel {@link FrameworkModel#defaultInstance}
     */
    protected ApplicationModel(FrameworkModel frameworkModel) {
        this(frameworkModel, false);
    }

    /**
     * <p>
     * {@link ApplicationModel#ApplicationModel(org.apache.dubbo.rpc.model.FrameworkModel)}中调用
     * </p>
     *
     * @param frameworkModel {@link FrameworkModel#defaultInstance}
     * @param isInternal     false
     */
    protected ApplicationModel(FrameworkModel frameworkModel, boolean isInternal) {
        super(frameworkModel, ExtensionScope.APPLICATION, isInternal);
        synchronized (instLock) {
            Assert.notNull(frameworkModel, "FrameworkModel can not be null");
            this.frameworkModel = frameworkModel;
            frameworkModel.addApplication(this);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(getDesc() + " is created");
            }
            initialize();

            this.internalModule = new ModuleModel(this, true);
            this.serviceRepository = new ServiceRepository(this);

            ExtensionLoader<ApplicationInitListener> extensionLoader =
                this.getExtensionLoader(ApplicationInitListener.class);
            Set<String> listenerNames = extensionLoader.getSupportedExtensions();
            for (String listenerName : listenerNames) {
                extensionLoader.getExtension(listenerName).init();
            }

            initApplicationExts();

            ExtensionLoader<ScopeModelInitializer> initializerExtensionLoader =
                this.getExtensionLoader(ScopeModelInitializer.class);
            /**
             * dubbo-common={@link org.apache.dubbo.common.CommonScopeModelInitializer}
             * {@link CommonScopeModelInitializer#initializeApplicationModel(org.apache.dubbo.rpc.model.ApplicationModel)}
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
                initializer.initializeApplicationModel(this);
            }

            Assert.notNull(getApplicationServiceRepository(), "ApplicationServiceRepository can not be null");
            Assert.notNull(getApplicationConfigManager(), "ApplicationConfigManager can not be null");
            Assert.assertTrue(
                getApplicationConfigManager().isInitialized(), "ApplicationConfigManager can not be initialized");
        }
    }

    // already synchronized in constructor
    private void initApplicationExts() {
        Set<ApplicationExt> exts = this.getExtensionLoader(ApplicationExt.class).getSupportedExtensionInstances();
        for (ApplicationExt ext : exts) {
            ext.initialize();
        }
    }

    @Override
    protected void onDestroy() {
        synchronized (instLock) {
            // 1. remove from frameworkModel
            frameworkModel.removeApplication(this);

            // 2. pre-destroy, set stopping
            if (deployer != null) {
                // destroy registries and unregister services from registries first to notify consumers to stop
                // consuming this instance.
                deployer.preDestroy();
            }

            // 3. Try to destroy protocols to stop this instance from receiving new requests from connections
            frameworkModel.tryDestroyProtocols();

            // 4. destroy application resources
            for (ModuleModel moduleModel : moduleModels) {
                if (moduleModel != internalModule) {
                    moduleModel.destroy();
                }
            }
            // 5. destroy internal module later
            internalModule.destroy();

            // 6. post-destroy, release registry resources
            if (deployer != null) {
                deployer.postDestroy();
            }

            // 7. destroy other resources (e.g. ZookeeperTransporter )
            notifyDestroy();

            if (environment != null) {
                environment.destroy();
                environment = null;
            }
            if (configManager != null) {
                configManager.destroy();
                configManager = null;
            }
            if (serviceRepository != null) {
                serviceRepository.destroy();
                serviceRepository = null;
            }

            // 8. destroy framework if none application
            frameworkModel.tryDestroy();
        }
    }

    public FrameworkModel getFrameworkModel() {
        return frameworkModel;
    }

    /**
     * {@link ApplicationModel#getDefaultModule()}中调用
     *
     * @return
     */
    public ModuleModel newModule() {
        synchronized (instLock) {
            return new ModuleModel(this);
        }
    }

    /**
     * <p>
     * {@link org.apache.dubbo.config.deploy.DefaultApplicationDeployer#DefaultApplicationDeployer(org.apache.dubbo.rpc.model.ApplicationModel)}
     * 中调用
     * </p>
     *
     * @return
     */
    @Override
    public Environment modelEnvironment() {
        if (environment == null) {
            /**
             * config=org.apache.dubbo.config.context.ConfigManager
             * environment=org.apache.dubbo.common.config.Environment
             */
            environment =
                (Environment) this.getExtensionLoader(ApplicationExt.class).getExtension(Environment.NAME);
        }
        return environment;
    }

    public ConfigManager getApplicationConfigManager() {
        if (configManager == null) {
            configManager = (ConfigManager)
                this.getExtensionLoader(ApplicationExt.class).getExtension(ConfigManager.NAME);
        }
        return configManager;
    }

    public ServiceRepository getApplicationServiceRepository() {
        return serviceRepository;
    }

    public ExecutorRepository getApplicationExecutorRepository() {
        return ExecutorRepository.getInstance(this);
    }

    public boolean NotExistApplicationConfig() {
        return !getApplicationConfigManager().getApplication().isPresent();
    }

    public ApplicationConfig getCurrentConfig() {
        return getApplicationConfigManager().getApplicationOrElseThrow();
    }

    public String getApplicationName() {
        return getCurrentConfig().getName();
    }

    /**
     * <p>
     * {@link org.apache.dubbo.config.deploy.DefaultApplicationDeployer#startConfigCenter()}中调用
     * </p>
     *
     * @return
     */
    public String tryGetApplicationName() {
        Optional<ApplicationConfig> appCfgOptional =
            getApplicationConfigManager().getApplication();
        return appCfgOptional.isPresent() ? appCfgOptional.get().getName() : null;
    }

    /**
     * {@link ModuleModel#ModuleModel(org.apache.dubbo.rpc.model.ApplicationModel, boolean)}
     * 中调用
     *
     * @param moduleModel
     * @param isInternal
     */
    void addModule(ModuleModel moduleModel, boolean isInternal) {
        synchronized (instLock) {
            if (!this.moduleModels.contains(moduleModel)) {
                checkDestroyed();
                this.moduleModels.add(moduleModel);
                moduleModel.setInternalId(buildInternalId(getInternalId(), moduleIndex.getAndIncrement()));
                if (!isInternal) {
                    pubModuleModels.add(moduleModel);
                }
            }
        }
    }

    public void removeModule(ModuleModel moduleModel) {
        synchronized (instLock) {
            this.moduleModels.remove(moduleModel);
            this.pubModuleModels.remove(moduleModel);
            if (moduleModel == defaultModule) {
                defaultModule = findDefaultModule();
            }
        }
    }

    void tryDestroy() {
        synchronized (instLock) {
            if (this.moduleModels.isEmpty()
                || (this.moduleModels.size() == 1 && this.moduleModels.get(0) == internalModule)) {
                destroy();
            }
        }
    }

    private void checkDestroyed() {
        if (isDestroyed()) {
            throw new IllegalStateException("ApplicationModel is destroyed");
        }
    }

    public List<ModuleModel> getModuleModels() {
        return Collections.unmodifiableList(moduleModels);
    }

    public List<ModuleModel> getPubModuleModels() {
        return Collections.unmodifiableList(pubModuleModels);
    }

    /**
     * <p>
     * {@link org.apache.dubbo.config.spring.context.DubboSpringInitializer#initContext}
     * 中调用
     * {@link org.apache.dubbo.config.deploy.DefaultApplicationDeployer#initModuleDeployers()}
     * 中调用
     * </p>
     *
     * @return
     */
    public ModuleModel getDefaultModule() {
        if (defaultModule == null) {
            synchronized (instLock) {
                if (defaultModule == null) {
                    defaultModule = findDefaultModule();
                    if (defaultModule == null) {
                        defaultModule = this.newModule();
                    }
                }
            }
        }
        return defaultModule;
    }

    private ModuleModel findDefaultModule() {
        synchronized (instLock) {
            for (ModuleModel moduleModel : moduleModels) {
                if (moduleModel != internalModule) {
                    return moduleModel;
                }
            }
            return null;
        }
    }

    public ModuleModel getInternalModule() {
        return internalModule;
    }

    @Override
    public void addClassLoader(ClassLoader classLoader) {
        super.addClassLoader(classLoader);
        if (environment != null) {
            environment.refreshClassLoaders();
        }
    }

    @Override
    public void removeClassLoader(ClassLoader classLoader) {
        super.removeClassLoader(classLoader);
        if (environment != null) {
            environment.refreshClassLoaders();
        }
    }

    @Override
    protected boolean checkIfClassLoaderCanRemoved(ClassLoader classLoader) {
        return super.checkIfClassLoaderCanRemoved(classLoader) && !containsClassLoader(classLoader);
    }

    protected boolean containsClassLoader(ClassLoader classLoader) {
        return moduleModels.stream()
            .anyMatch(moduleModel -> moduleModel.getClassLoaders().contains(classLoader));
    }

    public ApplicationDeployer getDeployer() {
        return deployer;
    }

    public void setDeployer(ApplicationDeployer deployer) {
        this.deployer = deployer;
    }

    @Override
    protected Lock acquireDestroyLock() {
        return frameworkModel.acquireDestroyLock();
    }

    // =============================== Deprecated Methods Start =======================================

    /**
     * @deprecated use {@link ServiceRepository#allConsumerModels()}
     */
    @Deprecated
    public static Collection<ConsumerModel> allConsumerModels() {
        return defaultModel().getApplicationServiceRepository().allConsumerModels();
    }

    /**
     * @deprecated use {@link ServiceRepository#allProviderModels()}
     */
    @Deprecated
    public static Collection<ProviderModel> allProviderModels() {
        return defaultModel().getApplicationServiceRepository().allProviderModels();
    }

    /**
     * @deprecated use {@link FrameworkServiceRepository#lookupExportedService(String)}
     */
    @Deprecated
    public static ProviderModel getProviderModel(String serviceKey) {
        return defaultModel().getDefaultModule().getServiceRepository().lookupExportedService(serviceKey);
    }

    /**
     * @deprecated ConsumerModel should fetch from context
     */
    @Deprecated
    public static ConsumerModel getConsumerModel(String serviceKey) {
        return defaultModel().getDefaultModule().getServiceRepository().lookupReferredService(serviceKey);
    }

    /**
     * @deprecated Replace to {@link ScopeModel#modelEnvironment()}
     */
    @Deprecated
    public static Environment getEnvironment() {
        return defaultModel().modelEnvironment();
    }

    /**
     * @deprecated Replace to {@link ApplicationModel#getApplicationConfigManager()}
     */
    @Deprecated
    public static ConfigManager getConfigManager() {
        return defaultModel().getApplicationConfigManager();
    }

    /**
     * @deprecated Replace to {@link ApplicationModel#getApplicationServiceRepository()}
     */
    @Deprecated
    public static ServiceRepository getServiceRepository() {
        return defaultModel().getApplicationServiceRepository();
    }

    /**
     * @deprecated Replace to {@link ApplicationModel#getApplicationExecutorRepository()}
     */
    @Deprecated
    public static ExecutorRepository getExecutorRepository() {
        return defaultModel().getApplicationExecutorRepository();
    }

    /**
     * @deprecated Replace to {@link ApplicationModel#getCurrentConfig()}
     */
    @Deprecated
    public static ApplicationConfig getApplicationConfig() {
        return defaultModel().getCurrentConfig();
    }

    /**
     * @deprecated Replace to {@link ApplicationModel#getApplicationName()}
     */
    @Deprecated
    public static String getName() {
        return defaultModel().getCurrentConfig().getName();
    }

    /**
     * @deprecated Replace to {@link ApplicationModel#getApplicationName()}
     */
    @Deprecated
    public static String getApplication() {
        return getName();
    }

    // only for unit test
    @Deprecated
    public static void reset() {
        if (FrameworkModel.defaultModel().getDefaultAppModel() != null) {
            FrameworkModel.defaultModel().getDefaultAppModel().destroy();
        }
    }

    /**
     * @deprecated only for ut
     */
    @Deprecated
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /**
     * @deprecated only for ut
     */
    @Deprecated
    public void setConfigManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * @deprecated only for ut
     */
    @Deprecated
    public void setServiceRepository(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    // =============================== Deprecated Methods End =======================================
}
