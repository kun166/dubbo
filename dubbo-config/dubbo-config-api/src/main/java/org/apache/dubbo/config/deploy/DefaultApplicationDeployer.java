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
package org.apache.dubbo.config.deploy;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.config.ReferenceCache;
import org.apache.dubbo.common.config.configcenter.ConfigChangeType;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.config.configcenter.DynamicConfigurationFactory;
import org.apache.dubbo.common.config.configcenter.wrapper.CompositeDynamicConfiguration;
import org.apache.dubbo.common.constants.LoggerCodeConstants;
import org.apache.dubbo.common.deploy.AbstractDeployer;
import org.apache.dubbo.common.deploy.ApplicationDeployListener;
import org.apache.dubbo.common.deploy.ApplicationDeployer;
import org.apache.dubbo.common.deploy.DeployListener;
import org.apache.dubbo.common.deploy.DeployState;
import org.apache.dubbo.common.deploy.ModuleDeployer;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.lang.ShutdownHookCallbacks;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.threadpool.manager.FrameworkExecutorRepository;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.*;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.metadata.ExporterDeployListener;
import org.apache.dubbo.config.utils.CompositeReferenceCache;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.metadata.report.MetadataReportFactory;
import org.apache.dubbo.metadata.report.MetadataReportInstance;
import org.apache.dubbo.metrics.collector.DefaultMetricsCollector;
import org.apache.dubbo.metrics.config.event.ConfigCenterEvent;
import org.apache.dubbo.metrics.event.MetricsEventBus;
import org.apache.dubbo.metrics.report.DefaultMetricsReporterFactory;
import org.apache.dubbo.metrics.report.MetricsReporter;
import org.apache.dubbo.metrics.report.MetricsReporterFactory;
import org.apache.dubbo.metrics.service.MetricsServiceExporter;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils;
import org.apache.dubbo.registry.support.RegistryManager;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ModuleServiceRepository;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.apache.dubbo.common.config.ConfigurationUtils.parseProperties;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTRY_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.COMMON_METRICS_COLLECTOR_EXCEPTION;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_EXECUTE_DESTROY;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_INIT_CONFIG_CENTER;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_START_MODEL;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_REFRESH_INSTANCE_ERROR;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_REGISTER_INSTANCE_ERROR;
import static org.apache.dubbo.common.constants.MetricsConstants.PROTOCOL_DEFAULT;
import static org.apache.dubbo.common.constants.MetricsConstants.PROTOCOL_PROMETHEUS;
import static org.apache.dubbo.common.utils.StringUtils.isEmpty;
import static org.apache.dubbo.common.utils.StringUtils.isNotEmpty;
import static org.apache.dubbo.metadata.MetadataConstants.DEFAULT_METADATA_PUBLISH_DELAY;
import static org.apache.dubbo.metadata.MetadataConstants.METADATA_PUBLISH_DELAY_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;

/**
 * initialize and start application instance
 */
public class DefaultApplicationDeployer extends AbstractDeployer<ApplicationModel> implements ApplicationDeployer {

    private static final ErrorTypeAwareLogger logger =
        LoggerFactory.getErrorTypeAwareLogger(DefaultApplicationDeployer.class);

    private final ApplicationModel applicationModel;

    private final ConfigManager configManager;

    /**
     * {@link Environment}
     * 构造函数中添加
     */
    private final Environment environment;

    private final ReferenceCache referenceCache;

    private final FrameworkExecutorRepository frameworkExecutorRepository;
    private final ExecutorRepository executorRepository;

    private final AtomicBoolean hasPreparedApplicationInstance = new AtomicBoolean(false);
    private volatile boolean hasPreparedInternalModule = false;

    private ScheduledFuture<?> asyncMetadataFuture;
    private volatile CompletableFuture<Boolean> startFuture;
    private final DubboShutdownHook dubboShutdownHook;

    private volatile MetricsServiceExporter metricsServiceExporter;

    private final Object stateLock = new Object();
    private final Object startLock = new Object();
    private final Object destroyLock = new Object();
    private final Object internalModuleLock = new Object();

    public DefaultApplicationDeployer(ApplicationModel applicationModel) {
        super(applicationModel);
        this.applicationModel = applicationModel;
        configManager = applicationModel.getApplicationConfigManager();
        environment = applicationModel.modelEnvironment();

        referenceCache = new CompositeReferenceCache(applicationModel);
        frameworkExecutorRepository =
            applicationModel.getFrameworkModel().getBeanFactory().getBean(FrameworkExecutorRepository.class);
        executorRepository = ExecutorRepository.getInstance(applicationModel);
        dubboShutdownHook = new DubboShutdownHook(applicationModel);

        // load spi listener
        Set<ApplicationDeployListener> deployListeners = applicationModel
            .getExtensionLoader(ApplicationDeployListener.class)
            .getSupportedExtensionInstances();
        for (ApplicationDeployListener listener : deployListeners) {
            this.addDeployListener(listener);
        }
    }

    public static ApplicationDeployer get(ScopeModel moduleOrApplicationModel) {
        ApplicationModel applicationModel = ScopeModelUtil.getApplicationModel(moduleOrApplicationModel);
        ApplicationDeployer applicationDeployer = applicationModel.getDeployer();
        if (applicationDeployer == null) {
            applicationDeployer = applicationModel.getBeanFactory().getOrRegisterBean(DefaultApplicationDeployer.class);
        }
        return applicationDeployer;
    }

    @Override
    public ApplicationModel getApplicationModel() {
        return applicationModel;
    }

    private <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        return applicationModel.getExtensionLoader(type);
    }

    private void unRegisterShutdownHook() {
        dubboShutdownHook.unregister();
    }

    /**
     * Close registration of instance for pure Consumer process by setting registerConsumer to 'false'
     * by default is true.
     *
     * <p>
     * {@link DefaultApplicationDeployer#prepareApplicationInstance()}中调用
     * </p>
     */
    private boolean isRegisterConsumerInstance() {
        Boolean registerConsumer = getApplication().getRegisterConsumer();
        if (registerConsumer == null) {
            return true;
        }
        return Boolean.TRUE.equals(registerConsumer);
    }

    @Override
    public ReferenceCache getReferenceCache() {
        return referenceCache;
    }

    /**
     * Initialize
     * <p>
     * {@link DefaultModuleDeployer#start()}中调用
     * </p>
     */
    @Override
    public void initialize() {
        if (initialized) {
            // 只执行一次
            return;
        }
        // Ensure that the initialization is completed when concurrent calls
        synchronized (startLock) {
            if (initialized) {
                return;
            }
            /**
             * 在自定标签中，感觉这个可以不用关注
             */
            onInitialize();

            // register shutdown hook
            /**
             * {@link Runtime#getRuntime()}的{@link Runtime#addShutdownHook(java.lang.Thread)}方法
             * 学学shut down hook，
             */
            registerShutdownHook();

            startConfigCenter();

            /**
             * 检测下面几项是否有定义，如果没有定义，尝试从Properties中加载
             * {@link ApplicationConfig}
             * {@link MonitorConfig}
             * {@link MetricsConfig}
             * {@link TracingConfig}
             * {@link ProtocolConfig}
             * {@link RegistryConfig}
             * {@link MetadataReportConfig}
             */
            loadApplicationConfigs();

            // 调用Default ModuleModel的ModuleDeployer的initialize方法
            initModuleDeployers();

            /**
             * 指标度量的上报，这个感觉应该很重要，有时间看下吧
             */
            initMetricsReporter();

            /**
             * 有时间再看吧
             */
            initMetricsService();

            // @since 2.7.8
            /**
             * <dubbo:metadata-report address="zookeeper://127.0.0.1:2181"/>
             * 这个也先不看了
             */
            startMetadataCenter();

            initialized = true;

            if (logger.isInfoEnabled()) {
                logger.info(getIdentifier() + " has been initialized!");
            }
        }
    }

    /**
     * {@link Runtime#getRuntime()}的{@link Runtime#addShutdownHook(java.lang.Thread)}方法
     * <p>
     * {@link DefaultApplicationDeployer#initialize()}中调用
     * </p>
     */
    private void registerShutdownHook() {
        dubboShutdownHook.register();
    }

    /**
     * 调用Default ModuleModel的ModuleDeployer的initialize方法
     * <p>
     * {@link DefaultApplicationDeployer#initialize()}调用
     * </p>
     */
    private void initModuleDeployers() {
        // make sure created default module
        applicationModel.getDefaultModule();
        // deployer initialize
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            moduleModel.getDeployer().initialize();
        }
    }

    /**
     * <p>
     * {@link DefaultApplicationDeployer#initialize()}中调用
     * </p>
     */
    private void loadApplicationConfigs() {
        configManager.loadConfigs();
    }

    /**
     * <p>
     * {@link DefaultApplicationDeployer#initialize()}中调用
     * </p>
     */
    private void startConfigCenter() {

        // load application config
        /**
         * 加载{@link ApplicationConfig}
         * 如果没有主动声明，且Properties配置文件里有符合的配置
         * 例如:<dubbo:application name="demo-provider" >
         */
        configManager.loadConfigsOfTypeFromProps(ApplicationConfig.class);

        // try set model name
        if (StringUtils.isBlank(applicationModel.getModelName())) {
            applicationModel.setModelName(applicationModel.tryGetApplicationName());
        }

        // load config centers
        /**
         * 加载{@link ConfigCenterConfig}
         * 如果没有主动声明，且Properties配置文件里有符合的配置
         * 例如：<dubbo:config-center address="zookeeper://127.0.0.1:2181"/>
         */
        configManager.loadConfigsOfTypeFromProps(ConfigCenterConfig.class);

        /**
         * 未配置<dubbo:config-center address="zookeeper://127.0.0.1:2181"/>
         * 尝试用Registry来初始化ConfigCenter
         */
        useRegistryAsConfigCenterIfNecessary();

        // check Config Center
        Collection<ConfigCenterConfig> configCenters = configManager.getConfigCenters();
        if (CollectionUtils.isEmpty(configCenters)) {
            /**
             * 未配置{@link ConfigCenterConfig},创建一个
             */
            ConfigCenterConfig configCenterConfig = new ConfigCenterConfig();
            configCenterConfig.setScopeModel(applicationModel);
            configCenterConfig.refresh();
            ConfigValidationUtils.validateConfigCenterConfig(configCenterConfig);
            if (configCenterConfig.isValid()) {
                /**
                 * 如果走到这个分支，说明在上面的refresh方法里面，从Properties中拿到了配置项
                 */
                configManager.addConfigCenter(configCenterConfig);
                configCenters = configManager.getConfigCenters();
            }
        } else {
            for (ConfigCenterConfig configCenterConfig : configCenters) {
                configCenterConfig.refresh();
                ConfigValidationUtils.validateConfigCenterConfig(configCenterConfig);
            }
        }

        if (CollectionUtils.isNotEmpty(configCenters)) {
            CompositeDynamicConfiguration compositeDynamicConfiguration = new CompositeDynamicConfiguration();
            for (ConfigCenterConfig configCenter : configCenters) {
                // Pass config from ConfigCenterBean to environment
                environment.updateExternalConfigMap(configCenter.getExternalConfiguration());
                environment.updateAppExternalConfigMap(configCenter.getAppExternalConfiguration());

                // Fetch config from remote config center
                compositeDynamicConfiguration.addConfiguration(prepareEnvironment(configCenter));
            }
            environment.setDynamicConfiguration(compositeDynamicConfiguration);
        }
    }

    /**
     * <dubbo:metadata-report address="zookeeper://127.0.0.1:2181"/>
     * <p>
     * {@link DefaultApplicationDeployer#initialize()}调用
     * </p>
     */
    private void startMetadataCenter() {

        useRegistryAsMetadataCenterIfNecessary();

        ApplicationConfig applicationConfig = getApplication();

        String metadataType = applicationConfig.getMetadataType();
        // FIXME, multiple metadata config support.
        Collection<MetadataReportConfig> metadataReportConfigs = configManager.getMetadataConfigs();
        if (CollectionUtils.isEmpty(metadataReportConfigs)) {
            if (REMOTE_METADATA_STORAGE_TYPE.equals(metadataType)) {
                throw new IllegalStateException(
                    "No MetadataConfig found, Metadata Center address is required when 'metadata=remote' is enabled.");
            }
            return;
        }

        MetadataReportInstance metadataReportInstance =
            applicationModel.getBeanFactory().getBean(MetadataReportInstance.class);
        List<MetadataReportConfig> validMetadataReportConfigs = new ArrayList<>(metadataReportConfigs.size());
        for (MetadataReportConfig metadataReportConfig : metadataReportConfigs) {
            if (ConfigValidationUtils.isValidMetadataConfig(metadataReportConfig)) {
                ConfigValidationUtils.validateMetadataConfig(metadataReportConfig);
                validMetadataReportConfigs.add(metadataReportConfig);
            }
        }
        metadataReportInstance.init(validMetadataReportConfigs);
        if (!metadataReportInstance.inited()) {
            throw new IllegalStateException(String.format(
                "%s MetadataConfigs found, but none of them is valid.", metadataReportConfigs.size()));
        }
    }

    /**
     * For compatibility purpose, use registry as the default config center when
     * there's no config center specified explicitly and
     * useAsConfigCenter of registryConfig is null or true
     * 出于兼容性目的，当没有明确指定配置中心，并且registryConfig的useAsConfigCenter为null或true
     * <p>
     * {@link DefaultApplicationDeployer#startConfigCenter()}中调用
     * </p>
     */
    private void useRegistryAsConfigCenterIfNecessary() {
        // we use the loading status of DynamicConfiguration to decide whether ConfigCenter has been initiated.
        // 我们使用DynamicConfiguration的加载状态来决定ConfigCenter是否已经启动。
        /**
         * 在{@link DefaultApplicationDeployer#startConfigCenter()}方法的最后有添加
         */
        if (environment.getDynamicConfiguration().isPresent()) {
            return;
        }

        if (CollectionUtils.isNotEmpty(configManager.getConfigCenters())) {
            return;
        }

        // load registry
        configManager.loadConfigsOfTypeFromProps(RegistryConfig.class);

        List<RegistryConfig> defaultRegistries = configManager.getDefaultRegistries();
        if (defaultRegistries.size() > 0) {
            defaultRegistries.stream()
                .filter(this::isUsedRegistryAsConfigCenter)
                .map(this::registryAsConfigCenter)
                .forEach(configCenter -> {
                    if (configManager.getConfigCenter(configCenter.getId()).isPresent()) {
                        return;
                    }
                    configManager.addConfigCenter(configCenter);
                    logger.info("use registry as config-center: " + configCenter);
                });
        }
    }

    /**
     * <p>
     * {@link DefaultApplicationDeployer#initialize()}调用
     * </p>
     */
    private void initMetricsService() {
        this.metricsServiceExporter =
            getExtensionLoader(MetricsServiceExporter.class).getDefaultExtension();
        metricsServiceExporter.init();
    }

    /**
     * <p>
     * {@link DefaultApplicationDeployer#initialize()}调用
     * </p>
     */
    private void initMetricsReporter() {
        if (!isSupportMetrics()) {
            return;
        }
        DefaultMetricsCollector collector = applicationModel.getBeanFactory().getBean(DefaultMetricsCollector.class);
        Optional<MetricsConfig> configOptional = configManager.getMetrics();
        // If no specific metrics type is configured and there is no Prometheus dependency in the dependencies.
        MetricsConfig metricsConfig = configOptional.orElse(new MetricsConfig(applicationModel));
        if (StringUtils.isBlank(metricsConfig.getProtocol())) {
            metricsConfig.setProtocol(isSupportPrometheus() ? PROTOCOL_PROMETHEUS : PROTOCOL_DEFAULT);
        }
        collector.setCollectEnabled(true);
        collector.collectApplication();
        collector.setThreadpoolCollectEnabled(
            Optional.ofNullable(metricsConfig.getEnableThreadpool()).orElse(true));
        collector.setMetricsInitEnabled(
            Optional.ofNullable(metricsConfig.getEnableMetricsInit()).orElse(true));
        MetricsReporterFactory metricsReporterFactory =
            getExtensionLoader(MetricsReporterFactory.class).getAdaptiveExtension();
        MetricsReporter metricsReporter = null;
        try {
            metricsReporter = metricsReporterFactory.createMetricsReporter(metricsConfig.toUrl());
        } catch (IllegalStateException e) {
            if (e.getMessage().startsWith("No such extension org.apache.dubbo.metrics.report.MetricsReporterFactory")) {
                logger.warn(COMMON_METRICS_COLLECTOR_EXCEPTION, "", "", e.getMessage());
                return;
            } else {
                throw e;
            }
        }
        metricsReporter.init();
        applicationModel.getBeanFactory().registerBean(metricsReporter);
        // If the protocol is not the default protocol, the default protocol is also initialized.
        if (!PROTOCOL_DEFAULT.equals(metricsConfig.getProtocol())) {
            DefaultMetricsReporterFactory defaultMetricsReporterFactory =
                new DefaultMetricsReporterFactory(applicationModel);
            MetricsReporter defaultMetricsReporter =
                defaultMetricsReporterFactory.createMetricsReporter(metricsConfig.toUrl());
            defaultMetricsReporter.init();
            applicationModel.getBeanFactory().registerBean(defaultMetricsReporter);
        }
    }

    public boolean isSupportMetrics() {
        return isClassPresent("io.micrometer.core.instrument.MeterRegistry");
    }

    public static boolean isSupportPrometheus() {
        return isClassPresent("io.micrometer.prometheus.PrometheusConfig")
            && isClassPresent("io.prometheus.client.exporter.BasicAuthHttpConnectionFactory")
            && isClassPresent("io.prometheus.client.exporter.HttpConnectionFactory")
            && isClassPresent("io.prometheus.client.exporter.PushGateway");
    }

    private static boolean isClassPresent(String className) {
        return ClassUtils.isPresent(className, DefaultApplicationDeployer.class.getClassLoader());
    }

    private boolean isUsedRegistryAsConfigCenter(RegistryConfig registryConfig) {
        return isUsedRegistryAsCenter(
            registryConfig, registryConfig::getUseAsConfigCenter, "config", DynamicConfigurationFactory.class);
    }

    private ConfigCenterConfig registryAsConfigCenter(RegistryConfig registryConfig) {
        String protocol = registryConfig.getProtocol();
        Integer port = registryConfig.getPort();
        URL url = URL.valueOf(registryConfig.getAddress(), registryConfig.getScopeModel());
        String id = "config-center-" + protocol + "-" + url.getHost() + "-" + port;
        ConfigCenterConfig cc = new ConfigCenterConfig();
        cc.setId(id);
        cc.setScopeModel(applicationModel);
        if (cc.getParameters() == null) {
            cc.setParameters(new HashMap<>());
        }
        if (CollectionUtils.isNotEmptyMap(registryConfig.getParameters())) {
            cc.getParameters().putAll(registryConfig.getParameters()); // copy the parameters
        }
        cc.getParameters().put(CLIENT_KEY, registryConfig.getClient());
        cc.setProtocol(protocol);
        cc.setPort(port);
        if (StringUtils.isNotEmpty(registryConfig.getGroup())) {
            cc.setGroup(registryConfig.getGroup());
        }
        cc.setAddress(getRegistryCompatibleAddress(registryConfig));
        cc.setNamespace(registryConfig.getGroup());
        cc.setUsername(registryConfig.getUsername());
        cc.setPassword(registryConfig.getPassword());
        if (registryConfig.getTimeout() != null) {
            cc.setTimeout(registryConfig.getTimeout().longValue());
        }
        cc.setHighestPriority(false);
        return cc;
    }

    private void useRegistryAsMetadataCenterIfNecessary() {

        Collection<MetadataReportConfig> originMetadataConfigs = configManager.getMetadataConfigs();
        if (originMetadataConfigs.stream().anyMatch(m -> Objects.nonNull(m.getAddress()))) {
            return;
        }

        Collection<MetadataReportConfig> metadataConfigsToOverride = originMetadataConfigs.stream()
            .filter(m -> Objects.isNull(m.getAddress()))
            .collect(Collectors.toList());

        if (metadataConfigsToOverride.size() > 1) {
            return;
        }

        MetadataReportConfig metadataConfigToOverride =
            metadataConfigsToOverride.stream().findFirst().orElse(null);

        List<RegistryConfig> defaultRegistries = configManager.getDefaultRegistries();
        if (!defaultRegistries.isEmpty()) {
            defaultRegistries.stream()
                .filter(this::isUsedRegistryAsMetadataCenter)
                .map(registryConfig -> registryAsMetadataCenter(registryConfig, metadataConfigToOverride))
                .forEach(metadataReportConfig -> {
                    overrideMetadataReportConfig(metadataConfigToOverride, metadataReportConfig);
                });
        }
    }

    private void overrideMetadataReportConfig(
        MetadataReportConfig metadataConfigToOverride, MetadataReportConfig metadataReportConfig) {
        if (metadataReportConfig.getId() == null) {
            Collection<MetadataReportConfig> metadataReportConfigs = configManager.getMetadataConfigs();
            if (CollectionUtils.isNotEmpty(metadataReportConfigs)) {
                for (MetadataReportConfig existedConfig : metadataReportConfigs) {
                    if (existedConfig.getId() == null
                        && existedConfig.getAddress().equals(metadataReportConfig.getAddress())) {
                        return;
                    }
                }
            }
            configManager.removeConfig(metadataConfigToOverride);
            configManager.addMetadataReport(metadataReportConfig);
        } else {
            Optional<MetadataReportConfig> configOptional =
                configManager.getConfig(MetadataReportConfig.class, metadataReportConfig.getId());
            if (configOptional.isPresent()) {
                return;
            }
            configManager.removeConfig(metadataConfigToOverride);
            configManager.addMetadataReport(metadataReportConfig);
        }
        logger.info("use registry as metadata-center: " + metadataReportConfig);
    }

    private boolean isUsedRegistryAsMetadataCenter(RegistryConfig registryConfig) {
        return isUsedRegistryAsCenter(
            registryConfig, registryConfig::getUseAsMetadataCenter, "metadata", MetadataReportFactory.class);
    }

    /**
     * Is used the specified registry as a center infrastructure
     *
     * @param registryConfig       the {@link RegistryConfig}
     * @param usedRegistryAsCenter the configured value on
     * @param centerType           the type name of center
     * @param extensionClass       an extension class of a center infrastructure
     * @return
     * @since 2.7.8
     */
    private boolean isUsedRegistryAsCenter(
        RegistryConfig registryConfig,
        Supplier<Boolean> usedRegistryAsCenter,
        String centerType,
        Class<?> extensionClass) {
        final boolean supported;

        Boolean configuredValue = usedRegistryAsCenter.get();
        if (configuredValue != null) { // If configured, take its value.
            supported = configuredValue.booleanValue();
        } else { // Or check the extension existence
            String protocol = registryConfig.getProtocol();
            supported = supportsExtension(extensionClass, protocol);
            if (logger.isInfoEnabled()) {
                logger.info(format(
                    "No value is configured in the registry, the %s extension[name : %s] %s as the %s center",
                    extensionClass.getSimpleName(),
                    protocol,
                    supported ? "supports" : "does not support",
                    centerType));
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info(format(
                "The registry[%s] will be %s as the %s center",
                registryConfig, supported ? "used" : "not used", centerType));
        }
        return supported;
    }

    /**
     * Supports the extension with the specified class and name
     *
     * @param extensionClass the {@link Class} of extension
     * @param name           the name of extension
     * @return if supports, return <code>true</code>, or <code>false</code>
     * @since 2.7.8
     */
    private boolean supportsExtension(Class<?> extensionClass, String name) {
        if (isNotEmpty(name)) {
            ExtensionLoader<?> extensionLoader = getExtensionLoader(extensionClass);
            return extensionLoader.hasExtension(name);
        }
        return false;
    }

    private MetadataReportConfig registryAsMetadataCenter(
        RegistryConfig registryConfig, MetadataReportConfig originMetadataReportConfig) {
        MetadataReportConfig metadataReportConfig = originMetadataReportConfig == null
            ? new MetadataReportConfig(registryConfig.getApplicationModel())
            : originMetadataReportConfig;
        if (metadataReportConfig.getId() == null) {
            metadataReportConfig.setId(registryConfig.getId());
        }
        metadataReportConfig.setScopeModel(applicationModel);
        if (metadataReportConfig.getParameters() == null) {
            metadataReportConfig.setParameters(new HashMap<>());
        }
        if (CollectionUtils.isNotEmptyMap(registryConfig.getParameters())) {
            for (Map.Entry<String, String> entry :
                registryConfig.getParameters().entrySet()) {
                metadataReportConfig
                    .getParameters()
                    .putIfAbsent(entry.getKey(), entry.getValue()); // copy the parameters
            }
        }
        metadataReportConfig.getParameters().put(CLIENT_KEY, registryConfig.getClient());
        if (metadataReportConfig.getGroup() == null) {
            metadataReportConfig.setGroup(registryConfig.getGroup());
        }
        if (metadataReportConfig.getAddress() == null) {
            metadataReportConfig.setAddress(getRegistryCompatibleAddress(registryConfig));
        }
        if (metadataReportConfig.getUsername() == null) {
            metadataReportConfig.setUsername(registryConfig.getUsername());
        }
        if (metadataReportConfig.getPassword() == null) {
            metadataReportConfig.setPassword(registryConfig.getPassword());
        }
        if (metadataReportConfig.getTimeout() == null) {
            metadataReportConfig.setTimeout(registryConfig.getTimeout());
        }
        return metadataReportConfig;
    }

    private String getRegistryCompatibleAddress(RegistryConfig registryConfig) {
        String registryAddress = registryConfig.getAddress();
        String[] addresses = REGISTRY_SPLIT_PATTERN.split(registryAddress);
        if (ArrayUtils.isEmpty(addresses)) {
            throw new IllegalStateException("Invalid registry address found.");
        }
        String address = addresses[0];
        // since 2.7.8
        // Issue : https://github.com/apache/dubbo/issues/6476
        StringBuilder metadataAddressBuilder = new StringBuilder();
        URL url = URL.valueOf(address, registryConfig.getScopeModel());
        String protocolFromAddress = url.getProtocol();
        if (isEmpty(protocolFromAddress)) {
            // If the protocol from address is missing, is like :
            // "dubbo.registry.address = 127.0.0.1:2181"
            String protocolFromConfig = registryConfig.getProtocol();
            metadataAddressBuilder.append(protocolFromConfig).append("://");
        }
        metadataAddressBuilder.append(address);
        return metadataAddressBuilder.toString();
    }

    /**
     * Start the bootstrap
     *
     * @return
     */
    @Override
    public Future start() {
        synchronized (startLock) {
            if (isStopping() || isStopped() || isFailed()) {
                throw new IllegalStateException(getIdentifier() + " is stopping or stopped, can not start again");
            }

            try {
                // maybe call start again after add new module, check if any new module
                boolean hasPendingModule = hasPendingModule();

                if (isStarting()) {
                    // currently, is starting, maybe both start by module and application
                    // if it has new modules, start them
                    if (hasPendingModule) {
                        startModules();
                    }
                    // if it is starting, reuse previous startFuture
                    return startFuture;
                }

                // if is started and no new module, just return
                if (isStarted() && !hasPendingModule) {
                    return CompletableFuture.completedFuture(false);
                }

                // pending -> starting : first start app
                // started -> starting : re-start app
                onStarting();

                initialize();

                doStart();
            } catch (Throwable e) {
                onFailed(getIdentifier() + " start failure", e);
                throw e;
            }

            return startFuture;
        }
    }

    private boolean hasPendingModule() {
        boolean found = false;
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            if (moduleModel.getDeployer().isPending()) {
                found = true;
                break;
            }
        }
        return found;
    }

    @Override
    public Future getStartFuture() {
        return startFuture;
    }

    private void doStart() {
        startModules();

        // prepare application instance
        //        prepareApplicationInstance();

        // Ignore checking new module after start
        //        executorRepository.getSharedExecutor().submit(() -> {
        //            try {
        //                while (isStarting()) {
        //                    // notify when any module state changed
        //                    synchronized (stateLock) {
        //                        try {
        //                            stateLock.wait(500);
        //                        } catch (InterruptedException e) {
        //                            // ignore
        //                        }
        //                    }
        //
        //                    // if has new module, do start again
        //                    if (hasPendingModule()) {
        //                        startModules();
        //                    }
        //                }
        //            } catch (Throwable e) {
        //                onFailed(getIdentifier() + " check start occurred an exception", e);
        //            }
        //        });
    }

    private void startModules() {
        // ensure init and start internal module first
        prepareInternalModule();

        // filter and start pending modules, ignore new module during starting, throw exception of module start
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            if (moduleModel.getDeployer().isPending()) {
                moduleModel.getDeployer().start();
            }
        }
    }

    /**
     * <p>
     * {@link DefaultApplicationDeployer#checkState(org.apache.dubbo.rpc.model.ModuleModel, org.apache.dubbo.common.deploy.DeployState)}
     * 中调用
     * </p>
     */
    @Override
    public void prepareApplicationInstance() {
        if (hasPreparedApplicationInstance.get()) {
            return;
        }

        // export MetricsService
        exportMetricsService();

        if (isRegisterConsumerInstance()) {
            exportMetadataService();
            if (hasPreparedApplicationInstance.compareAndSet(false, true)) {
                // register the local ServiceInstance if required
                registerServiceInstance();
            }
        }
    }

    public void prepareInternalModule() {
        if (hasPreparedInternalModule) {
            return;
        }
        synchronized (internalModuleLock) {
            if (hasPreparedInternalModule) {
                return;
            }

            // start internal module
            ModuleDeployer internalModuleDeployer =
                applicationModel.getInternalModule().getDeployer();
            if (!internalModuleDeployer.isStarted()) {
                Future future = internalModuleDeployer.start();
                // wait for internal module startup
                try {
                    future.get(5, TimeUnit.SECONDS);
                    hasPreparedInternalModule = true;
                } catch (Exception e) {
                    logger.warn(
                        CONFIG_FAILED_START_MODEL,
                        "",
                        "",
                        "wait for internal module startup failed: " + e.getMessage(),
                        e);
                }
            }
        }
    }

    /**
     * <p>
     * {@link DefaultApplicationDeployer#prepareApplicationInstance()}中调用
     * </p>
     */
    private void exportMetricsService() {
        boolean exportMetrics = applicationModel
            .getApplicationConfigManager()
            .getMetrics()
            .map(MetricsConfig::getExportMetricsService)
            .orElse(true);
        if (exportMetrics) {
            try {
                metricsServiceExporter.export();
            } catch (Exception e) {
                logger.error(
                    LoggerCodeConstants.COMMON_METRICS_COLLECTOR_EXCEPTION,
                    "",
                    "",
                    "exportMetricsService an exception occurred when handle starting event",
                    e);
            }
        }
    }

    private void unexportMetricsService() {
        if (metricsServiceExporter != null) {
            try {
                metricsServiceExporter.unexport();
            } catch (Exception ignored) {
                // ignored
            }
        }
    }

    private boolean hasExportedServices() {
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            if (CollectionUtils.isNotEmpty(moduleModel.getConfigManager().getServices())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isBackground() {
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            if (moduleModel.getDeployer().isBackground()) {
                return true;
            }
        }
        return false;
    }

    /**
     * <p>
     * {@link DefaultApplicationDeployer#startConfigCenter()}中有调用
     * </p>
     *
     * @param configCenter
     * @return
     */
    private DynamicConfiguration prepareEnvironment(ConfigCenterConfig configCenter) {
        if (configCenter.isValid()) {
            if (!configCenter.checkOrUpdateInitialized(true)) {
                return null;
            }

            DynamicConfiguration dynamicConfiguration;
            try {
                dynamicConfiguration = getDynamicConfiguration(configCenter.toUrl());
            } catch (Exception e) {
                if (!configCenter.isCheck()) {
                    logger.warn(
                        CONFIG_FAILED_INIT_CONFIG_CENTER,
                        "",
                        "",
                        "The configuration center failed to initialize",
                        e);
                    configCenter.setInitialized(false);
                    return null;
                } else {
                    throw new IllegalStateException(e);
                }
            }
            ApplicationModel applicationModel = getApplicationModel();

            if (StringUtils.isNotEmpty(configCenter.getConfigFile())) {
                String configContent =
                    dynamicConfiguration.getProperties(configCenter.getConfigFile(), configCenter.getGroup());
                if (StringUtils.isNotEmpty(configContent)) {
                    logger.info(String.format(
                        "Got global remote configuration from config center with key-%s and group-%s: \n %s",
                        configCenter.getConfigFile(), configCenter.getGroup(), configContent));
                }
                String appGroup = getApplication().getName();
                String appConfigContent = null;
                String appConfigFile = null;
                if (isNotEmpty(appGroup)) {
                    appConfigFile = isNotEmpty(configCenter.getAppConfigFile())
                        ? configCenter.getAppConfigFile()
                        : configCenter.getConfigFile();
                    appConfigContent = dynamicConfiguration.getProperties(appConfigFile, appGroup);
                    if (StringUtils.isNotEmpty(appConfigContent)) {
                        logger.info(String.format(
                            "Got application specific remote configuration from config center with key %s and group %s: \n %s",
                            appConfigFile, appGroup, appConfigContent));
                    }
                }
                try {
                    Map<String, String> configMap = parseProperties(configContent);
                    Map<String, String> appConfigMap = parseProperties(appConfigContent);

                    environment.updateExternalConfigMap(configMap);
                    environment.updateAppExternalConfigMap(appConfigMap);

                    // Add metrics
                    MetricsEventBus.publish(ConfigCenterEvent.toChangeEvent(
                        applicationModel,
                        configCenter.getConfigFile(),
                        configCenter.getGroup(),
                        configCenter.getProtocol(),
                        ConfigChangeType.ADDED.name(),
                        configMap.size()));
                    if (isNotEmpty(appGroup)) {
                        MetricsEventBus.publish(ConfigCenterEvent.toChangeEvent(
                            applicationModel,
                            appConfigFile,
                            appGroup,
                            configCenter.getProtocol(),
                            ConfigChangeType.ADDED.name(),
                            appConfigMap.size()));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to parse configurations from Config Center.", e);
                }
            }
            return dynamicConfiguration;
        }
        return null;
    }

    /**
     * Get the instance of {@link DynamicConfiguration} by the specified connection {@link URL} of config-center
     *
     * @param connectionURL of config-center
     * @return non-null
     * @since 2.7.5
     */
    private DynamicConfiguration getDynamicConfiguration(URL connectionURL) {
        String protocol = connectionURL.getProtocol();

        DynamicConfigurationFactory factory =
            ConfigurationUtils.getDynamicConfigurationFactory(applicationModel, protocol);
        return factory.getDynamicConfiguration(connectionURL);
    }

    private volatile boolean registered;

    private final AtomicInteger instanceRefreshScheduleTimes = new AtomicInteger(0);

    /**
     * Indicate that how many threads are updating service
     */
    private final AtomicInteger serviceRefreshState = new AtomicInteger(0);

    /**
     * <p>
     * {@link DefaultApplicationDeployer#prepareApplicationInstance()}中调用
     * </p>
     */
    private void registerServiceInstance() {
        try {
            registered = true;
            ServiceInstanceMetadataUtils.registerMetadataAndInstance(applicationModel);
        } catch (Exception e) {
            logger.error(
                CONFIG_REGISTER_INSTANCE_ERROR,
                "configuration server disconnected",
                "",
                "Register instance error.",
                e);
        }
        if (registered) {
            // scheduled task for updating Metadata and ServiceInstance
            asyncMetadataFuture = frameworkExecutorRepository
                .getSharedScheduledExecutor()
                .scheduleWithFixedDelay(
                    () -> {

                        // ignore refresh metadata on stopping
                        if (applicationModel.isDestroyed()) {
                            return;
                        }

                        // refresh for 30 times (default for 30s) when deployer is not started, prevent submit
                        // too many revision
                        if (instanceRefreshScheduleTimes.incrementAndGet() % 30 != 0 && !isStarted()) {
                            return;
                        }

                        // refresh for 5 times (default for 5s) when services are being updated by other
                        // threads, prevent submit too many revision
                        // note: should not always wait here
                        if (serviceRefreshState.get() != 0 && instanceRefreshScheduleTimes.get() % 5 != 0) {
                            return;
                        }

                        try {
                            if (!applicationModel.isDestroyed() && registered) {
                                ServiceInstanceMetadataUtils.refreshMetadataAndInstance(applicationModel);
                            }
                        } catch (Exception e) {
                            if (!applicationModel.isDestroyed()) {
                                logger.error(
                                    CONFIG_REFRESH_INSTANCE_ERROR,
                                    "",
                                    "",
                                    "Refresh instance and metadata error.",
                                    e);
                            }
                        }
                    },
                    0,
                    ConfigurationUtils.get(
                        applicationModel, METADATA_PUBLISH_DELAY_KEY, DEFAULT_METADATA_PUBLISH_DELAY),
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * <p>
     * {@link DefaultModuleDeployer#registerServices()}中调用
     * </p>
     */
    @Override
    public void refreshServiceInstance() {
        if (registered) {
            try {
                ServiceInstanceMetadataUtils.refreshMetadataAndInstance(applicationModel);
            } catch (Exception e) {
                logger.error(CONFIG_REFRESH_INSTANCE_ERROR, "", "", "Refresh instance and metadata error.", e);
            }
        }
    }

    @Override
    public void increaseServiceRefreshCount() {
        serviceRefreshState.incrementAndGet();
    }

    @Override
    public void decreaseServiceRefreshCount() {
        serviceRefreshState.decrementAndGet();
    }

    private void unregisterServiceInstance() {
        if (registered) {
            ServiceInstanceMetadataUtils.unregisterMetadataAndInstance(applicationModel);
        }
    }

    @Override
    public void stop() {
        applicationModel.destroy();
    }

    @Override
    public void preDestroy() {
        synchronized (destroyLock) {
            if (isStopping() || isStopped()) {
                return;
            }
            onStopping();

            offline();

            unregisterServiceInstance();

            unexportMetricsService();

            unRegisterShutdownHook();
            if (asyncMetadataFuture != null) {
                asyncMetadataFuture.cancel(true);
            }
        }
    }

    private void offline() {
        try {
            for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
                ModuleServiceRepository serviceRepository = moduleModel.getServiceRepository();
                List<ProviderModel> exportedServices = serviceRepository.getExportedServices();
                for (ProviderModel exportedService : exportedServices) {
                    List<ProviderModel.RegisterStatedURL> statedUrls = exportedService.getStatedUrl();
                    for (ProviderModel.RegisterStatedURL statedURL : statedUrls) {
                        if (statedURL.isRegistered()) {
                            doOffline(statedURL);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error(
                LoggerCodeConstants.INTERNAL_ERROR, "", "", "Exceptions occurred when unregister services.", t);
        }
    }

    private void doOffline(ProviderModel.RegisterStatedURL statedURL) {
        RegistryFactory registryFactory = statedURL
            .getRegistryUrl()
            .getOrDefaultApplicationModel()
            .getExtensionLoader(RegistryFactory.class)
            .getAdaptiveExtension();
        Registry registry = registryFactory.getRegistry(statedURL.getRegistryUrl());
        registry.unregister(statedURL.getProviderUrl());
        statedURL.setRegistered(false);
    }

    @Override
    public void postDestroy() {
        synchronized (destroyLock) {
            // expect application model is destroyed before here
            if (isStopped()) {
                return;
            }
            try {
                destroyRegistries();
                destroyMetadataReports();

                executeShutdownCallbacks();

                // TODO should we close unused protocol server which only used by this application?
                // protocol server will be closed on all applications of same framework are stopped currently, but no
                // associate to application
                // see org.apache.dubbo.config.deploy.FrameworkModelCleaner#destroyProtocols
                // see
                // org.apache.dubbo.config.bootstrap.DubboBootstrapMultiInstanceTest#testMultiProviderApplicationStopOneByOne

                // destroy all executor services
                destroyExecutorRepository();

                onStopped();
            } catch (Throwable ex) {
                String msg = getIdentifier() + " an error occurred while stopping application: " + ex.getMessage();
                onFailed(msg, ex);
            }
        }
    }

    private void executeShutdownCallbacks() {
        ShutdownHookCallbacks shutdownHookCallbacks =
            applicationModel.getBeanFactory().getBean(ShutdownHookCallbacks.class);
        shutdownHookCallbacks.callback();
    }

    /**
     * <p>
     * {@link ModuleModel#ModuleModel(org.apache.dubbo.rpc.model.ApplicationModel, boolean)}中调用
     * state:{@link DeployState#PENDING}
     * </p>
     * <p>
     * {@link DefaultModuleDeployer#onModuleStarting()}中调用
     * state:{@link DeployState#STARTING}
     * </p>
     * <p>
     * {@link DefaultModuleDeployer#onModuleStarted()}中调用
     * state:{@link DeployState#STARTED}
     * </p>
     *
     * @param moduleModel
     * @param state
     */
    @Override
    public void notifyModuleChanged(ModuleModel moduleModel, DeployState state) {
        checkState(moduleModel, state);

        // notify module state changed or module changed
        synchronized (stateLock) {
            stateLock.notifyAll();
        }
    }

    /**
     * <p>
     * {@link DefaultApplicationDeployer#notifyModuleChanged(org.apache.dubbo.rpc.model.ModuleModel, org.apache.dubbo.common.deploy.DeployState)}
     * 中调用
     * </p>
     *
     * @param moduleModel
     * @param moduleState
     */
    @Override
    public void checkState(ModuleModel moduleModel, DeployState moduleState) {
        synchronized (stateLock) {
            if (!moduleModel.isInternal() && moduleState == DeployState.STARTED) {
                prepareApplicationInstance();
            }
            DeployState newState = calculateState();
            switch (newState) {
                case STARTED:
                    onStarted();
                    break;
                case STARTING:
                    onStarting();
                    break;
                case STOPPING:
                    onStopping();
                    break;
                case STOPPED:
                    onStopped();
                    break;
                case FAILED:
                    Throwable error = null;
                    ModuleModel errorModule = null;
                    for (ModuleModel module : applicationModel.getModuleModels()) {
                        ModuleDeployer deployer = module.getDeployer();
                        if (deployer.isFailed() && deployer.getError() != null) {
                            error = deployer.getError();
                            errorModule = module;
                            break;
                        }
                    }
                    onFailed(getIdentifier() + " found failed module: " + errorModule.getDesc(), error);
                    break;
                case PENDING:
                    // cannot change to pending from other state
                    // setPending();
                    break;
            }
        }
    }

    /**
     * <p>
     * {@link DefaultApplicationDeployer#checkState(org.apache.dubbo.rpc.model.ModuleModel, org.apache.dubbo.common.deploy.DeployState)}
     * 中调用
     * </p>
     *
     * @return
     */
    private DeployState calculateState() {
        DeployState newState = DeployState.UNKNOWN;
        int pending = 0, starting = 0, started = 0, stopping = 0, stopped = 0, failed = 0;
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            ModuleDeployer deployer = moduleModel.getDeployer();
            if (deployer == null) {
                pending++;
            } else if (deployer.isPending()) {
                pending++;
            } else if (deployer.isStarting()) {
                starting++;
            } else if (deployer.isStarted()) {
                started++;
            } else if (deployer.isStopping()) {
                stopping++;
            } else if (deployer.isStopped()) {
                stopped++;
            } else if (deployer.isFailed()) {
                failed++;
            }
        }

        if (failed > 0) {
            newState = DeployState.FAILED;
        } else if (started > 0) {
            if (pending + starting + stopping + stopped == 0) {
                // all modules have been started
                newState = DeployState.STARTED;
            } else if (pending + starting > 0) {
                // some module is pending and some is started
                newState = DeployState.STARTING;
            } else if (stopping + stopped > 0) {
                newState = DeployState.STOPPING;
            }
        } else if (starting > 0) {
            // any module is starting
            newState = DeployState.STARTING;
        } else if (pending > 0) {
            if (starting + starting + stopping + stopped == 0) {
                // all modules have not starting or started
                newState = DeployState.PENDING;
            } else if (stopping + stopped > 0) {
                // some is pending and some is stopping or stopped
                newState = DeployState.STOPPING;
            }
        } else if (stopping > 0) {
            // some is stopping and some stopped
            newState = DeployState.STOPPING;
        } else if (stopped > 0) {
            // all modules are stopped
            newState = DeployState.STOPPED;
        }
        return newState;
    }

    /**
     * 调用{@link AbstractDeployer#listeners}的{@link DeployListener#onInitialize(org.apache.dubbo.rpc.model.ScopeModel)}方法
     * <p>
     * {@link DefaultApplicationDeployer#initialize()}中调用
     * </p>
     */
    private void onInitialize() {
        for (DeployListener<ApplicationModel> listener : listeners) {
            try {
                listener.onInitialize(applicationModel);
            } catch (Throwable e) {
                logger.error(
                    CONFIG_FAILED_START_MODEL,
                    "",
                    "",
                    getIdentifier() + " an exception occurred when handle initialize event",
                    e);
            }
        }
    }

    /**
     * <p>
     * {@link DefaultApplicationDeployer#prepareApplicationInstance()}中调用
     * </p>
     */
    private void exportMetadataService() {
        if (!isStarting()) {
            return;
        }
        for (DeployListener<ApplicationModel> listener : listeners) {
            try {
                if (listener instanceof ApplicationDeployListener) {
                    /**
                     * <p>
                     * {@link ExporterDeployListener#onModuleStarted(org.apache.dubbo.rpc.model.ApplicationModel)}
                     * </p>
                     */
                    ((ApplicationDeployListener) listener).onModuleStarted(applicationModel);
                }
            } catch (Throwable e) {
                logger.error(
                    CONFIG_FAILED_START_MODEL,
                    "",
                    "",
                    getIdentifier() + " an exception occurred when handle starting event",
                    e);
            }
        }
    }

    /**
     * <p>
     * {@link DefaultApplicationDeployer#checkState(org.apache.dubbo.rpc.model.ModuleModel, org.apache.dubbo.common.deploy.DeployState)}
     * 中调用
     * </p>
     */
    private void onStarting() {
        // pending -> starting
        // started -> starting
        if (!(isPending() || isStarted())) {
            return;
        }
        setStarting();
        startFuture = new CompletableFuture();
        if (logger.isInfoEnabled()) {
            logger.info(getIdentifier() + " is starting.");
        }
    }

    private void onStarted() {
        try {
            // starting -> started
            if (!isStarting()) {
                return;
            }
            setStarted();
            startMetricsCollector();
            if (logger.isInfoEnabled()) {
                logger.info(getIdentifier() + " is ready.");
            }
            // refresh metadata
            try {
                if (registered) {
                    ServiceInstanceMetadataUtils.refreshMetadataAndInstance(applicationModel);
                }
            } catch (Exception e) {
                logger.error(CONFIG_REFRESH_INSTANCE_ERROR, "", "", "Refresh instance and metadata error.", e);
            }
        } finally {
            // complete future
            completeStartFuture(true);
        }
    }

    private void startMetricsCollector() {
        DefaultMetricsCollector collector = applicationModel.getBeanFactory().getBean(DefaultMetricsCollector.class);
        if (Objects.nonNull(collector) && collector.isThreadpoolCollectEnabled()) {
            collector.registryDefaultSample();
        }
    }

    private void completeStartFuture(boolean success) {
        if (startFuture != null) {
            startFuture.complete(success);
        }
    }

    private void onStopping() {
        try {
            if (isStopping() || isStopped()) {
                return;
            }
            setStopping();
            if (logger.isInfoEnabled()) {
                logger.info(getIdentifier() + " is stopping.");
            }
        } finally {
            completeStartFuture(false);
        }
    }

    private void onStopped() {
        try {
            if (isStopped()) {
                return;
            }
            setStopped();
            if (logger.isInfoEnabled()) {
                logger.info(getIdentifier() + " has stopped.");
            }
        } finally {
            completeStartFuture(false);
        }
    }

    private void onFailed(String msg, Throwable ex) {
        try {
            setFailed(ex);
            logger.error(CONFIG_FAILED_START_MODEL, "", "", msg, ex);
        } finally {
            completeStartFuture(false);
        }
    }

    private void destroyExecutorRepository() {
        // shutdown export/refer executor
        executorRepository.shutdownServiceExportExecutor();
        executorRepository.shutdownServiceReferExecutor();
        ExecutorRepository.getInstance(applicationModel).destroyAll();
    }

    private void destroyRegistries() {
        RegistryManager.getInstance(applicationModel).destroyAll();
    }

    private void destroyServiceDiscoveries() {
        RegistryManager.getInstance(applicationModel).getServiceDiscoveries().forEach(serviceDiscovery -> {
            try {
                serviceDiscovery.destroy();
            } catch (Throwable ignored) {
                logger.warn(CONFIG_FAILED_EXECUTE_DESTROY, "", "", ignored.getMessage(), ignored);
            }
        });
        if (logger.isDebugEnabled()) {
            logger.debug(getIdentifier() + "'s all ServiceDiscoveries have been destroyed.");
        }
    }

    private void destroyMetadataReports() {
        // only destroy MetadataReport of this application
        List<MetadataReportFactory> metadataReportFactories =
            getExtensionLoader(MetadataReportFactory.class).getLoadedExtensionInstances();
        for (MetadataReportFactory metadataReportFactory : metadataReportFactories) {
            metadataReportFactory.destroy();
        }
    }

    private ApplicationConfig getApplication() {
        return configManager.getApplicationOrElseThrow();
    }
}
