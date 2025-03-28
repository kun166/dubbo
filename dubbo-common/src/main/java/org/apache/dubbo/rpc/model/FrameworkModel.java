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
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.resource.GlobalResourcesRepository;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.metadata.definition.TypeDefinitionBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Model of dubbo framework, it can be shared with multiple applications.
 * 这个是三个{@link ScopeModel}实现类里面，范围最大的。
 */
public class FrameworkModel extends ScopeModel {

    // ========================= Static Fields Start ===================================

    protected static final Logger LOGGER = LoggerFactory.getLogger(FrameworkModel.class);

    /**
     * 常量
     */
    public static final String NAME = "FrameworkModel";

    /**
     * 线性安全的自增序号,初始值为1
     */
    private static final AtomicLong index = new AtomicLong(1);

    /**
     * 这个应该是线程锁?
     */
    private static final Object globalLock = new Object();

    /**
     * 单例模式,在{@link FrameworkModel#defaultModel()}中初始化
     */
    private static volatile FrameworkModel defaultInstance;

    /**
     * {@link FrameworkModel#FrameworkModel()}中调用
     * 一旦创建过{@link FrameworkModel},即加入到这里面
     * <p>
     * 在{@link FrameworkModel#FrameworkModel()}构造其中,会向其中添加数据
     * 在{@link FrameworkModel#onDestroy()}中会有删除对象操作
     */
    private static final List<FrameworkModel> allInstances = new CopyOnWriteArrayList<>();

    // ========================= Static Fields End ===================================

    // internal app index is 0, default app index is 1
    private final AtomicLong appIndex = new AtomicLong(0);

    private volatile ApplicationModel defaultAppModel;

    /**
     * {@link FrameworkModel#addApplication(org.apache.dubbo.rpc.model.ApplicationModel)}中添加
     */
    private final List<ApplicationModel> applicationModels = new CopyOnWriteArrayList<>();

    /**
     * {@link FrameworkModel#addApplication(org.apache.dubbo.rpc.model.ApplicationModel)}中添加
     * {@link ScopeModel#internalScope}为false的时候添加
     */
    private final List<ApplicationModel> pubApplicationModels = new CopyOnWriteArrayList<>();

    private final FrameworkServiceRepository serviceRepository;

    private final ApplicationModel internalApplicationModel;

    private final ReentrantLock destroyLock = new ReentrantLock();

    /**
     * Use {@link FrameworkModel#newModel()} to create a new model
     * <p>
     * {@link FrameworkModel#defaultModel()}中创建
     * </p>
     * <p>
     * 说它是单例模式吧，其实它好像也不是……
     * 说它不是单例模式吧，好像也按着单例模式写的……
     */
    public FrameworkModel() {
        // 初始化父类默认参数
        super(null, ExtensionScope.FRAMEWORK, false);
        synchronized (globalLock) {
            synchronized (instLock) {

                this.setInternalId(String.valueOf(index.getAndIncrement()));
                // register FrameworkModel instance early
                allInstances.add(this);
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(getDesc() + " is created");
                }
                initialize();

                TypeDefinitionBuilder.initBuilders(this);

                serviceRepository = new FrameworkServiceRepository(this);

                ExtensionLoader<ScopeModelInitializer> initializerExtensionLoader =
                    this.getExtensionLoader(ScopeModelInitializer.class);
                /**
                 * dubbo-common={@link org.apache.dubbo.common.CommonScopeModelInitializer}
                 * {@link CommonScopeModelInitializer#initializeFrameworkModel(org.apache.dubbo.rpc.model.FrameworkModel)}
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
                    initializer.initializeFrameworkModel(this);
                }

                internalApplicationModel = new ApplicationModel(this, true);
                internalApplicationModel
                    .getApplicationConfigManager()
                    .setApplication(new ApplicationConfig(
                        internalApplicationModel, CommonConstants.DUBBO_INTERNAL_APPLICATION));
                internalApplicationModel.setModelName(CommonConstants.DUBBO_INTERNAL_APPLICATION);
            }
        }
    }

    @Override
    protected void onDestroy() {
        synchronized (instLock) {
            if (defaultInstance == this) {
                // NOTE: During destroying the default FrameworkModel, the FrameworkModel.defaultModel() or
                // ApplicationModel.defaultModel()
                // will return a broken model, maybe cause unpredictable problem.
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("Destroying default framework model: " + getDesc());
                }
            }

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(getDesc() + " is destroying ...");
            }

            // destroy all application model
            for (ApplicationModel applicationModel : new ArrayList<>(applicationModels)) {
                applicationModel.destroy();
            }
            // check whether all application models are destroyed
            checkApplicationDestroy();

            // notify destroy and clean framework resources
            // see org.apache.dubbo.config.deploy.FrameworkModelCleaner
            notifyDestroy();

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(getDesc() + " is destroyed");
            }

            // remove from allInstances and reset default FrameworkModel
            synchronized (globalLock) {
                allInstances.remove(this);
                resetDefaultFrameworkModel();
            }

            // if all FrameworkModels are destroyed, clean global static resources, shutdown dubbo completely
            destroyGlobalResources();
        }
    }

    private void checkApplicationDestroy() {
        synchronized (instLock) {
            if (applicationModels.size() > 0) {
                List<String> remainApplications =
                    applicationModels.stream().map(ScopeModel::getDesc).collect(Collectors.toList());
                throw new IllegalStateException(
                    "Not all application models are completely destroyed, remaining " + remainApplications.size()
                        + " application models may be created during destruction: " + remainApplications);
            }
        }
    }

    private void destroyGlobalResources() {
        synchronized (globalLock) {
            if (allInstances.isEmpty()) {
                GlobalResourcesRepository.getInstance().destroy();
            }
        }
    }

    /**
     * During destroying the default FrameworkModel, the FrameworkModel.defaultModel() or ApplicationModel.defaultModel()
     * will return a broken model, maybe cause unpredictable problem.
     * Recommendation: Avoid using the default model as much as possible.
     * <p>
     * {@link org.apache.dubbo.config.spring.context.DubboSpringInitializer#customize}
     * 中调用
     * </p>
     *
     * @return the global default FrameworkModel
     */
    public static FrameworkModel defaultModel() {
        // 单例模式
        FrameworkModel instance = defaultInstance;
        if (instance == null) {
            synchronized (globalLock) {
                resetDefaultFrameworkModel();
                if (defaultInstance == null) {
                    /**
                     * 如果单例模式中对象未创建,则创建一个
                     */
                    defaultInstance = new FrameworkModel();
                }
                instance = defaultInstance;
            }
        }
        Assert.notNull(instance, "Default FrameworkModel is null");
        return instance;
    }

    /**
     * Get all framework model instances
     *
     * @return
     */
    public static List<FrameworkModel> getAllInstances() {
        synchronized (globalLock) {
            return Collections.unmodifiableList(new ArrayList<>(allInstances));
        }
    }

    /**
     * Destroy all framework model instances, shutdown dubbo engine completely.
     */
    public static void destroyAll() {
        synchronized (globalLock) {
            for (FrameworkModel frameworkModel : new ArrayList<>(allInstances)) {
                frameworkModel.destroy();
            }
        }
    }

    /**
     * <p>
     * {@link FrameworkModel#defaultApplication()}中调用
     * {@link org.apache.dubbo.config.spring.context.DubboSpringInitializer#initContext}中调用
     * </p>
     *
     * @return
     */
    public ApplicationModel newApplication() {
        synchronized (instLock) {
            return new ApplicationModel(this);
        }
    }

    /**
     * Get or create default application model
     *
     * @return
     */
    public ApplicationModel defaultApplication() {
        ApplicationModel appModel = this.defaultAppModel;
        if (appModel == null) {
            // check destroyed before acquire inst lock, avoid blocking during destroying
            checkDestroyed();
            resetDefaultAppModel();
            if ((appModel = this.defaultAppModel) == null) {
                synchronized (instLock) {
                    if (this.defaultAppModel == null) {
                        this.defaultAppModel = newApplication();
                    }
                    appModel = this.defaultAppModel;
                }
            }
        }
        Assert.notNull(appModel, "Default ApplicationModel is null");
        return appModel;
    }

    ApplicationModel getDefaultAppModel() {
        return defaultAppModel;
    }

    /**
     * {@link ApplicationModel#ApplicationModel(org.apache.dubbo.rpc.model.FrameworkModel, boolean)}中调用
     *
     * @param applicationModel
     */
    void addApplication(ApplicationModel applicationModel) {
        // can not add new application if it's destroying
        checkDestroyed();
        synchronized (instLock) {
            if (!this.applicationModels.contains(applicationModel)) {
                applicationModel.setInternalId(buildInternalId(getInternalId(), appIndex.getAndIncrement()));
                this.applicationModels.add(applicationModel);
                if (!applicationModel.isInternal()) {
                    this.pubApplicationModels.add(applicationModel);
                }
            }
        }
    }

    void removeApplication(ApplicationModel model) {
        synchronized (instLock) {
            this.applicationModels.remove(model);
            if (!model.isInternal()) {
                this.pubApplicationModels.remove(model);
            }
            resetDefaultAppModel();
        }
    }

    /**
     * Protocols are special resources that need to be destroyed as soon as possible.
     * <p>
     * Since connections inside protocol are not classified by applications, trying to destroy protocols in advance might only work for singleton application scenario.
     */
    void tryDestroyProtocols() {
        synchronized (instLock) {
            if (pubApplicationModels.size() == 0) {
                notifyProtocolDestroy();
            }
        }
    }

    void tryDestroy() {
        synchronized (instLock) {
            if (pubApplicationModels.size() == 0) {
                destroy();
            }
        }
    }

    private void checkDestroyed() {
        if (isDestroyed()) {
            throw new IllegalStateException("FrameworkModel is destroyed");
        }
    }

    private void resetDefaultAppModel() {
        synchronized (instLock) {
            if (this.defaultAppModel != null && !this.defaultAppModel.isDestroyed()) {
                return;
            }
            ApplicationModel oldDefaultAppModel = this.defaultAppModel;
            if (pubApplicationModels.size() > 0) {
                this.defaultAppModel = pubApplicationModels.get(0);
            } else {
                this.defaultAppModel = null;
            }
            if (defaultInstance == this && oldDefaultAppModel != this.defaultAppModel) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("Reset global default application from " + safeGetModelDesc(oldDefaultAppModel) + " to "
                        + safeGetModelDesc(this.defaultAppModel));
                }
            }
        }
    }

    /**
     * 重置{@link FrameworkModel#defaultInstance}
     * 如果{@link FrameworkModel#allInstances}有数据,则把{@link FrameworkModel#defaultInstance}设置为{@link FrameworkModel#allInstances}第一个
     * <p>
     * {@link FrameworkModel#defaultModel()}中调用
     * </p>
     * <p>
     * 几乎没做什么操作
     */
    private static void resetDefaultFrameworkModel() {
        synchronized (globalLock) {
            // 如果不为空且未销毁，则直接返回了
            if (defaultInstance != null && !defaultInstance.isDestroyed()) {
                /**
                 * 从判断条件来看,如果未有销毁,则下面逻辑不需要处理。
                 * 也即:只有销毁的,才会触发下面的逻辑
                 */
                return;
            }
            // 先记录原来的defaultInstance
            FrameworkModel oldDefaultFrameworkModel = defaultInstance;
            // 给defaultInstance赋值
            if (allInstances.size() > 0) {
                /**
                 * 取最开始的那个
                 */
                defaultInstance = allInstances.get(0);
            } else {
                defaultInstance = null;
            }
            if (oldDefaultFrameworkModel != defaultInstance) {
                /**
                 * 如果两者不相同，则打一个日志
                 * 好像也没有处理逻辑
                 */
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("Reset global default framework from " + safeGetModelDesc(oldDefaultFrameworkModel)
                        + " to " + safeGetModelDesc(defaultInstance));
                }
            }
        }
    }

    private static String safeGetModelDesc(ScopeModel scopeModel) {
        return scopeModel != null ? scopeModel.getDesc() : null;
    }

    /**
     * Get all application models except for the internal application model.
     */
    public List<ApplicationModel> getApplicationModels() {
        synchronized (globalLock) {
            return Collections.unmodifiableList(pubApplicationModels);
        }
    }

    /**
     * Get all application models including the internal application model.
     */
    public List<ApplicationModel> getAllApplicationModels() {
        synchronized (globalLock) {
            return Collections.unmodifiableList(applicationModels);
        }
    }

    public ApplicationModel getInternalApplicationModel() {
        return internalApplicationModel;
    }

    public FrameworkServiceRepository getServiceRepository() {
        return serviceRepository;
    }

    @Override
    protected Lock acquireDestroyLock() {
        return destroyLock;
    }

    @Override
    public Environment modelEnvironment() {
        throw new UnsupportedOperationException("Environment is inaccessible for FrameworkModel");
    }

    @Override
    protected boolean checkIfClassLoaderCanRemoved(ClassLoader classLoader) {
        return super.checkIfClassLoaderCanRemoved(classLoader)
            && applicationModels.stream()
            .noneMatch(applicationModel -> applicationModel.containsClassLoader(classLoader));
    }
}
