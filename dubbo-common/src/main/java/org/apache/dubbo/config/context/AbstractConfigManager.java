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
package org.apache.dubbo.config.context;

import org.apache.dubbo.common.config.CompositeConfiguration;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.config.PropertiesConfiguration;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.context.LifecycleAdapter;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.*;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.lang.Boolean.TRUE;
import static java.util.Collections.emptyMap;
import static java.util.Optional.ofNullable;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.COMMON_PROPERTY_TYPE_MISMATCH;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.COMMON_UNEXPECTED_EXCEPTION;
import static org.apache.dubbo.config.AbstractConfig.getTagName;

public abstract class AbstractConfigManager extends LifecycleAdapter {

    private static final String CONFIG_NAME_READ_METHOD = "getName";

    private static final ErrorTypeAwareLogger logger =
        LoggerFactory.getErrorTypeAwareLogger(AbstractConfigManager.class);
    private static final Set<Class<? extends AbstractConfig>> uniqueConfigTypes = new ConcurrentHashSet<>();

    /**
     * {@link AbstractConfigManager#addConfig(org.apache.dubbo.config.AbstractConfig)}中添加值
     * 更确切的说,应该是在该方法中调用的{@link AbstractConfigManager#addIfAbsent(org.apache.dubbo.config.AbstractConfig, java.util.Map)}
     * 中添加值。
     * <p>
     * 从代码中来看，只有{@link ReferenceConfigBase}和{@link ServiceConfigBase},即服务提供者和服务调用者,
     * 两者有多个实例。
     * <p>
     * 该缓存的key为value(Map<String, AbstractConfig>)中的value，即{@link AbstractConfig}的实例的class的simpleName,
     * 通过{@link AbstractConfig#getTagName(java.lang.Class)}方法获取的字符串。
     * Map<String, AbstractConfig>中的value为{@link AbstractConfig}的实例,key为实例的id或者name
     *
     * <p>
     * {@link ProviderConfig}
     * {@link ConsumerConfig}
     * {@link ModuleConfig}
     * 这三者是在{@link org.apache.dubbo.config.deploy.DefaultModuleDeployer#loadConfigs()}
     * 中添加的
     * </p>
     */
    final Map<String, Map<String, AbstractConfig>> configsCache = new ConcurrentHashMap<>();

    private final Map<String, AtomicInteger> configIdIndexes = new ConcurrentHashMap<>();

    protected Set<AbstractConfig> duplicatedConfigs = new ConcurrentHashSet<>();

    /**
     * <p>
     * 如果是{@link ModuleConfigManager}
     * {@link org.apache.dubbo.rpc.model.ModuleModel}
     * </p>
     */
    protected final ScopeModel scopeModel;
    protected final ApplicationModel applicationModel;

    /**
     * <p>
     * 如果是{@link ModuleConfigManager}
     * {@link ModuleConfig}
     * {@link ServiceConfigBase}
     * {@link ReferenceConfigBase}
     * {@link org.apache.dubbo.config.ProviderConfig}
     * {@link org.apache.dubbo.config.ConsumerConfig}
     * </p>
     */
    private final Collection<Class<? extends AbstractConfig>> supportedConfigTypes;
    private final Environment environment;
    private ConfigValidator configValidator;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    protected ConfigMode configMode = ConfigMode.STRICT;
    protected boolean ignoreDuplicatedInterface = false;

    static {
        // init unique config types
        // unique config in application
        uniqueConfigTypes.add(ApplicationConfig.class);
        uniqueConfigTypes.add(MonitorConfig.class);
        uniqueConfigTypes.add(MetricsConfig.class);
        uniqueConfigTypes.add(TracingConfig.class);
        uniqueConfigTypes.add(SslConfig.class);

        // unique config in each module
        uniqueConfigTypes.add(ModuleConfig.class);
    }

    public AbstractConfigManager(ScopeModel scopeModel, Collection<Class<? extends AbstractConfig>> supportedConfigTypes) {
        this.scopeModel = scopeModel;
        this.applicationModel = ScopeModelUtil.getApplicationModel(scopeModel);
        this.supportedConfigTypes = supportedConfigTypes;
        environment = scopeModel.modelEnvironment();
    }

    @Override
    public void initialize() throws IllegalStateException {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }
        CompositeConfiguration configuration = scopeModel.modelEnvironment().getConfiguration();

        // dubbo.config.mode
        String configModeStr = (String) configuration.getProperty(ConfigKeys.DUBBO_CONFIG_MODE);
        try {
            if (StringUtils.hasText(configModeStr)) {
                this.configMode = ConfigMode.valueOf(configModeStr.toUpperCase());
            }
        } catch (Exception e) {
            String msg = "Illegal '" + ConfigKeys.DUBBO_CONFIG_MODE + "' config value [" + configModeStr
                + "], available values " + Arrays.toString(ConfigMode.values());
            logger.error(COMMON_PROPERTY_TYPE_MISMATCH, "", "", msg, e);
            throw new IllegalArgumentException(msg, e);
        }

        // dubbo.config.ignore-duplicated-interface
        String ignoreDuplicatedInterfaceStr =
            (String) configuration.getProperty(ConfigKeys.DUBBO_CONFIG_IGNORE_DUPLICATED_INTERFACE);
        if (ignoreDuplicatedInterfaceStr != null) {
            this.ignoreDuplicatedInterface = Boolean.parseBoolean(ignoreDuplicatedInterfaceStr);
        }

        // print
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(ConfigKeys.DUBBO_CONFIG_MODE, configMode);
        map.put(ConfigKeys.DUBBO_CONFIG_IGNORE_DUPLICATED_INTERFACE, this.ignoreDuplicatedInterface);
        logger.info("Config settings: " + map);
    }

    /**
     * Add the dubbo {@link AbstractConfig config}
     * <p>
     * {@link ModuleConfigManager#addService(org.apache.dubbo.config.ServiceConfigBase)}中调用
     * {@link AbstractConfigManager#loadConfigsOfTypeFromProps(java.lang.Class)}中调用
     * {@link ConfigManager#addConfigCenter(org.apache.dubbo.config.ConfigCenterConfig)}中调用
     * </p>
     *
     * @param config the dubbo {@link AbstractConfig config}
     */
    public final <T extends AbstractConfig> T addConfig(AbstractConfig config) {
        if (config == null) {
            return null;
        }
        // ignore MethodConfig
        if (!isSupportConfigType(config.getClass())) {
            throw new IllegalArgumentException("Unsupported config type: " + config);
        }

        if (config.getScopeModel() != scopeModel) {
            config.setScopeModel(scopeModel);
        }

        Map<String, AbstractConfig> configsMap =
            configsCache.computeIfAbsent(getTagName(config.getClass()), type -> new ConcurrentHashMap<>());

        // fast check duplicated equivalent config before write lock
        if (!(config instanceof ReferenceConfigBase || config instanceof ServiceConfigBase)) {
            /**
             * 进入条件是既不是{@link ReferenceConfigBase},也不是{@link ServiceConfigBase}
             */
            for (AbstractConfig value : configsMap.values()) {
                if (value.equals(config)) {
                    return (T) value;
                }
            }
        }

        // lock by config type
        synchronized (configsMap) {
            return (T) addIfAbsent(config, configsMap);
        }
    }

    /**
     * <p>
     * {@link AbstractConfigManager#addConfig(org.apache.dubbo.config.AbstractConfig)}中调用
     * </p>
     *
     * @param type
     * @return
     */
    protected boolean isSupportConfigType(Class<? extends AbstractConfig> type) {
        for (Class<? extends AbstractConfig> supportedConfigType : supportedConfigTypes) {
            if (supportedConfigType.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Add config
     * <p>
     * {@link AbstractConfigManager#addConfig(org.apache.dubbo.config.AbstractConfig)}中调用
     * </p>
     *
     * @param config
     * @param configsMap
     * @return the existing equivalent config or the new adding config
     * @throws IllegalStateException
     */
    private <C extends AbstractConfig> C addIfAbsent(C config, Map<String, C> configsMap) throws IllegalStateException {

        if (config == null || configsMap == null) {
            return config;
        }

        // find by value
        Optional<C> prevConfig = findDuplicatedConfig(configsMap, config);
        if (prevConfig.isPresent()) {
            return prevConfig.get();
        }

        String key = config.getId();
        if (key == null) {
            do {
                // generate key if id is not set
                key = generateConfigId(config);
            } while (configsMap.containsKey(key));
        }

        C existedConfig = configsMap.get(key);
        if (existedConfig != null && !isEquals(existedConfig, config)) {
            String type = config.getClass().getSimpleName();
            logger.warn(
                COMMON_UNEXPECTED_EXCEPTION,
                "",
                "",
                String.format(
                    "Duplicate %s found, there already has one default %s or more than two %ss have the same id, "
                        + "you can try to give each %s a different id, override previous config with later config. id: %s, prev: %s, later: %s",
                    type, type, type, type, key, existedConfig, config));
        }

        // override existed config if any
        configsMap.put(key, config);
        return config;
    }

    protected <C extends AbstractConfig> boolean removeIfAbsent(C config, Map<String, C> configsMap) {
        if (config.getId() != null) {
            return configsMap.remove(config.getId(), config);
        }
        return configsMap.values().removeIf(c -> config == c);
    }

    protected boolean isUniqueConfig(AbstractConfig config) {
        if (uniqueConfigTypes.contains(config.getClass())) {
            return true;
        }
        for (Class<? extends AbstractConfig> uniqueConfigType : uniqueConfigTypes) {
            if (uniqueConfigType.isAssignableFrom(config.getClass())) {
                return true;
            }
        }
        return false;
    }

    /**
     * <p>
     * {@link ModuleConfigManager#getModule()}中调用
     * </p>
     *
     * @param configType
     * @param <C>
     * @return
     * @throws IllegalStateException
     */
    protected <C extends AbstractConfig> C getSingleConfig(String configType) throws IllegalStateException {
        Map<String, AbstractConfig> configsMap = getConfigsMap(configType);
        int size = configsMap.size();
        if (size < 1) {
            //                throw new IllegalStateException("No such " + configType.getName() + " is found");
            return null;
        } else if (size > 1) {
            throw new IllegalStateException("Expected single instance of " + configType + ", but found " + size
                + " instances, please remove redundant configs. instances: " + configsMap.values());
        }
        return (C) configsMap.values().iterator().next();
    }

    /**
     * <p>
     * {@link AbstractConfigManager#addIfAbsent(org.apache.dubbo.config.AbstractConfig, java.util.Map)}
     * 中调用
     * </p>
     *
     * @param configsMap
     * @param config
     * @param <C>
     * @return
     */
    protected <C extends AbstractConfig> Optional<C> findDuplicatedConfig(Map<String, C> configsMap, C config) {

        // find by value
        Optional<C> prevConfig = findConfigByValue(configsMap.values(), config);
        if (prevConfig.isPresent()) {
            if (prevConfig.get() == config) {
                // the new one is same as existing one
                return prevConfig;
            }

            // ignore duplicated equivalent config
            if (logger.isInfoEnabled() && duplicatedConfigs.add(config)) {
                logger.info("Ignore duplicated config: " + config);
            }
            return prevConfig;
        }

        // check unique config
        return checkUniqueConfig(configsMap, config);
    }

    public <C extends AbstractConfig> Map<String, C> getConfigsMap(Class<C> cls) {
        return getConfigsMap(getTagName(cls));
    }

    protected <C extends AbstractConfig> Map<String, C> getConfigsMap(String configType) {
        return (Map<String, C>) configsCache.getOrDefault(configType, emptyMap());
    }

    protected <C extends AbstractConfig> Collection<C> getConfigs(String configType) {
        return (Collection<C>) getConfigsMap(configType).values();
    }

    /**
     * <p>
     * {@link AbstractConfigManager#loadConfigsOfTypeFromProps(java.lang.Class)}中调用
     * </p>
     *
     * @param configType
     * @param <C>
     * @return
     */
    public <C extends AbstractConfig> Collection<C> getConfigs(Class<C> configType) {
        return (Collection<C>) getConfigsMap(getTagName(configType)).values();
    }

    /**
     * Get config by id
     * <p>
     * {@link AbstractConfigManager#getConfig(java.lang.Class, java.lang.String)}中调用
     * </p>
     *
     * @param configType
     * @param id
     * @return
     */
    protected <C extends AbstractConfig> C getConfigById(String configType, String id) {
        return (C) getConfigsMap(configType).get(id);
    }

    /**
     * Get config instance by id or by name
     * 从{@link AbstractConfigManager#configsCache}中,获取传入的参数cls的实例。
     * <p>
     * 获取步骤:
     * 1,先根据cls获取Map<String, AbstractConfig>
     * 2,再按idOrName当做id,即第一步中的map的key,获取实例
     * 3,如果第二步没有获取到,则把idOrName当做name获取,这一步该cls必须有getName方法,没有直接返回null
     * 4,遍历第一步获取的map的所有values,分别调用getName方法,获取的值与idOrName比较,equals的就返回
     * <p>
     * {@link AbstractConfigManager#loadConfigsOfTypeFromProps(java.lang.Class)}中调用
     * </p>
     *
     * @param cls      Config type
     * @param idOrName the id or name of the config
     * @return
     */
    public <T extends AbstractConfig> Optional<T> getConfig(Class<T> cls, String idOrName) {
        T config = getConfigById(getTagName(cls), idOrName);
        if (config == null) {
            config = getConfigByName(cls, idOrName);
        }
        return ofNullable(config);
    }

    /**
     * Get config by name if existed
     *
     * @param cls
     * @param name
     * @return
     */
    protected <C extends AbstractConfig> C getConfigByName(Class<? extends C> cls, String name) {
        Map<String, ? extends C> configsMap = getConfigsMap(cls);
        if (configsMap.isEmpty()) {
            return null;
        }
        // try to find config by name
        if (ReflectUtils.hasMethod(cls, CONFIG_NAME_READ_METHOD)) {
            List<C> list = configsMap.values().stream()
                .filter(cfg -> name.equals(getConfigName(cfg)))
                .collect(Collectors.toList());
            if (list.size() > 1) {
                throw new IllegalStateException("Found more than one config by name: " + name + ", instances: " + list
                    + ". Please remove redundant configs or get config by id.");
            } else if (list.size() == 1) {
                return list.get(0);
            }
        }
        return null;
    }

    private <C extends AbstractConfig> String getConfigName(C config) {
        try {
            return ReflectUtils.getProperty(config, CONFIG_NAME_READ_METHOD);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 寻找集合values中，==或者equals config的那个元素
     * <p>
     * {@link AbstractConfigManager#findDuplicatedConfig(java.util.Map, org.apache.dubbo.config.AbstractConfig)}
     * 中调用
     * </p>
     *
     * @param values
     * @param config
     * @param <C>
     * @return
     */
    protected <C extends AbstractConfig> Optional<C> findConfigByValue(Collection<C> values, C config) {
        // 1. find same config instance (speed up raw api usage)
        // 1. 找到相同的配置实例（加快原始api的使用）
        Optional<C> prevConfig = values.stream().filter(val -> val == config).findFirst();
        if (prevConfig.isPresent()) {
            return prevConfig;
        }

        // 2. find equal config
        prevConfig = values.stream().filter(val -> isEquals(val, config)).findFirst();
        return prevConfig;
    }

    /**
     * <p>
     * {@link AbstractConfigManager#findConfigByValue(java.util.Collection, org.apache.dubbo.config.AbstractConfig)}
     * 中调用
     * </p>
     *
     * @param oldOne
     * @param newOne
     * @return
     */
    protected boolean isEquals(AbstractConfig oldOne, AbstractConfig newOne) {
        if (oldOne == newOne) {
            return true;
        }
        if (oldOne == null || newOne == null) {
            return false;
        }
        if (oldOne.getClass() != newOne.getClass()) {
            return false;
        }
        // make both are refreshed or none is refreshed
        if (oldOne.isRefreshed() || newOne.isRefreshed()) {
            if (!oldOne.isRefreshed()) {
                oldOne.refresh();
            }
            if (!newOne.isRefreshed()) {
                newOne.refresh();
            }
        }
        return oldOne.equals(newOne);
    }

    protected <C extends AbstractConfig> String generateConfigId(C config) {
        String tagName = getTagName(config.getClass());
        int idx = configIdIndexes
            .computeIfAbsent(tagName, clazz -> new AtomicInteger(0))
            .incrementAndGet();
        return tagName + "#" + idx;
    }

    public <C extends AbstractConfig> List<C> getDefaultConfigs(Class<C> cls) {
        return getDefaultConfigs(getConfigsMap(getTagName(cls)));
    }

    static <C extends AbstractConfig> Boolean isDefaultConfig(C config) {
        return config.isDefault();
    }

    static <C extends AbstractConfig> List<C> getDefaultConfigs(Map<String, C> configsMap) {
        // find isDefault() == true
        List<C> list = configsMap.values().stream()
            .filter(c -> TRUE.equals(AbstractConfigManager.isDefaultConfig(c)))
            .collect(Collectors.toList());
        if (list.size() > 0) {
            return list;
        }

        // find isDefault() == null
        list = configsMap.values().stream()
            .filter(c -> AbstractConfigManager.isDefaultConfig(c) == null)
            .collect(Collectors.toList());
        return list;

        // exclude isDefault() == false
    }

    /**
     * <p>
     * {@link AbstractConfigManager#findDuplicatedConfig(java.util.Map, org.apache.dubbo.config.AbstractConfig)}
     * 中调用
     * </p>
     *
     * @param configsMap
     * @param config
     * @param <C>
     * @return
     */
    protected <C extends AbstractConfig> Optional<C> checkUniqueConfig(Map<String, C> configsMap, C config) {
        if (configsMap.size() > 0 && isUniqueConfig(config)) {
            C oldOne = configsMap.values().iterator().next();
            String configName = oldOne.getClass().getSimpleName();
            String msgPrefix = "Duplicate Configs found for " + configName + ", only one unique " + configName
                + " is allowed for one application. previous: " + oldOne + ", later: " + config
                + ". According to config mode [" + configMode + "], ";
            switch (configMode) {
                case STRICT: {
                    if (!isEquals(oldOne, config)) {
                        throw new IllegalStateException(
                            msgPrefix + "please remove redundant configs and keep only one.");
                    }
                    break;
                }
                case IGNORE: {
                    // ignore later config
                    if (logger.isWarnEnabled() && duplicatedConfigs.add(config)) {
                        logger.warn(
                            COMMON_UNEXPECTED_EXCEPTION,
                            "",
                            "",
                            msgPrefix + "keep previous config and ignore later config");
                    }
                    return Optional.of(oldOne);
                }
                case OVERRIDE: {
                    // clear previous config, add new config
                    configsMap.clear();
                    if (logger.isWarnEnabled() && duplicatedConfigs.add(config)) {
                        logger.warn(
                            COMMON_UNEXPECTED_EXCEPTION,
                            "",
                            "",
                            msgPrefix + "override previous config with later config");
                    }
                    break;
                }
                case OVERRIDE_ALL: {
                    // override old one's properties with the new one
                    oldOne.overrideWithConfig(config, true);
                    if (logger.isWarnEnabled() && duplicatedConfigs.add(config)) {
                        logger.warn(
                            COMMON_UNEXPECTED_EXCEPTION,
                            "",
                            "",
                            msgPrefix + "override previous config with later config");
                    }
                    return Optional.of(oldOne);
                }
                case OVERRIDE_IF_ABSENT: {
                    // override old one's properties with the new one
                    oldOne.overrideWithConfig(config, false);
                    if (logger.isWarnEnabled() && duplicatedConfigs.add(config)) {
                        logger.warn(
                            COMMON_UNEXPECTED_EXCEPTION,
                            "",
                            "",
                            msgPrefix + "override previous config with later config");
                    }
                    return Optional.of(oldOne);
                }
            }
        }
        return Optional.empty();
    }

    public abstract void loadConfigs();

    /**
     * 从Properties配置文件里面加载{@link AbstractConfig}
     * <p>
     * {@link org.apache.dubbo.config.deploy.DefaultApplicationDeployer#startConfigCenter()}中调用
     * cls传递的是{@link ApplicationConfig}
     * </p>
     *
     * @param cls
     * @param <T>
     * @return
     */
    public <T extends AbstractConfig> List<T> loadConfigsOfTypeFromProps(Class<T> cls) {
        List<T> tmpConfigs = new ArrayList<>();
        /**
         * properties配置文件的数据吧？
         */
        PropertiesConfiguration properties = environment.getPropertiesConfiguration();

        // load multiple configs with id
        /**
         * 比如cls是{@link ApplicationConfig},则获取
         * dubbo.application.
         * 为前缀的配置项
         * 关于这个方法，注释已经很明白了，一定要看下注释
         */
        Set<String> configIds = this.getConfigIdsFromProps(cls);
        configIds.forEach(id -> {
            if (!this.getConfig(cls, id).isPresent()) {
                /**
                 * 获取的实例如果不存在，则创建一个。
                 * 重点注意：这个前提是在Properties文件中已经配置了，才会走到这一步
                 */
                T config;
                try {
                    config = createConfig(cls, scopeModel);
                    // 设置ID
                    config.setId(id);
                } catch (Exception e) {
                    throw new IllegalStateException(
                        "create config instance failed, id: " + id + ", type:" + cls.getSimpleName());
                }

                String key = null;
                boolean addDefaultNameConfig = false;
                try {
                    // add default name config (same as id), e.g. dubbo.protocols.rest.port=1234
                    // 如上注释，把dubbo.protocols.rest.name=rest 添加到配置环境中?
                    // finally又给删除了……
                    key = DUBBO + "." + AbstractConfig.getPluralTagName(cls) + "." + id + ".name";
                    if (properties.getProperty(key) == null) {
                        properties.setProperty(key, id);
                        addDefaultNameConfig = true;
                    }

                    config.refresh();
                    this.addConfig(config);
                    tmpConfigs.add(config);
                } catch (Exception e) {
                    logger.error(
                        COMMON_PROPERTY_TYPE_MISMATCH,
                        "",
                        "",
                        "load config failed, id: " + id + ", type:" + cls.getSimpleName(),
                        e);
                    throw new IllegalStateException("load config failed, id: " + id + ", type:" + cls.getSimpleName());
                } finally {
                    if (addDefaultNameConfig && key != null) {
                        properties.remove(key);
                    }
                }
            }
        });

        // If none config of the type, try load single config
        if (this.getConfigs(cls).isEmpty()) {
            // load single config
            /**
             * xml没有配置该项，且Properties中也未配置导致没有生成默认的。
             * 这个也是从Properties中获取配置,不同的是获取的配置项不用,
             * 上面是{@link AbstractConfig#getPluralTagName(java.lang.Class)}
             * 这里是{@link AbstractConfig#getTypePrefix(java.lang.Class)}
             */
            List<Map<String, String>> configurationMaps = environment.getConfigurationMaps();
            if (ConfigurationUtils.hasSubProperties(configurationMaps, AbstractConfig.getTypePrefix(cls))) {
                T config;
                try {
                    config = createConfig(cls, scopeModel);
                    config.refresh();
                } catch (Exception e) {
                    throw new IllegalStateException(
                        "create default config instance failed, type:" + cls.getSimpleName());
                }

                this.addConfig(config);
                tmpConfigs.add(config);
            }
        }

        return tmpConfigs;
    }

    /**
     * 根据传入的cls,创建一个{@link AbstractConfig}实例,并且设置scopeModel
     * <p>
     * {@link AbstractConfigManager#loadConfigsOfTypeFromProps(java.lang.Class)}中调用
     * </p>
     *
     * @param cls
     * @param scopeModel
     * @param <T>
     * @return
     * @throws ReflectiveOperationException
     */
    private <T extends AbstractConfig> T createConfig(Class<T> cls, ScopeModel scopeModel)
        throws ReflectiveOperationException {
        T config = cls.getDeclaredConstructor().newInstance();
        config.setScopeModel(scopeModel);
        return config;
    }

    /**
     * Search props and extract config ids of specify type.
     * <pre>
     * # properties
     * dubbo.registries.registry1.address=xxx
     * dubbo.registries.registry2.port=xxx
     *
     * # extract
     * Set configIds = getConfigIds(RegistryConfig.class)
     *
     * # result
     * configIds: ["registry1", "registry2"]
     * </pre>
     *
     * @param clazz config type
     * @return ids of specify config type
     */
    private Set<String> getConfigIdsFromProps(Class<? extends AbstractConfig> clazz) {
        String prefix = CommonConstants.DUBBO + "." + AbstractConfig.getPluralTagName(clazz) + ".";
        return ConfigurationUtils.getSubIds(environment.getConfigurationMaps(), prefix);
    }

    protected <T extends AbstractConfig> void checkDefaultAndValidateConfigs(Class<T> configType) {
        try {
            if (shouldAddDefaultConfig(configType)) {
                T config = createConfig(configType, scopeModel);
                config.refresh();
                if (!isNeedValidation(config) || config.isValid()) {
                    this.addConfig(config);
                } else {
                    logger.info("Ignore invalid config: " + config);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Add default config failed: " + configType.getSimpleName(), e);
        }

        // validate configs
        Collection<T> configs = this.getConfigs(configType);
        if (getConfigValidator() != null) {
            for (T config : configs) {
                getConfigValidator().validate(config);
            }
        }

        // check required default
        if (isRequired(configType) && configs.isEmpty()) {
            throw new IllegalStateException("Default config not found for " + configType.getSimpleName());
        }
    }

    /**
     * The component configuration that does not affect the main process does not need to be verified.
     *
     * @param config
     * @param <T>
     * @return
     */
    protected <T extends AbstractConfig> boolean isNeedValidation(T config) {
        if (config instanceof MetadataReportConfig) {
            return false;
        }
        return true;
    }

    private ConfigValidator getConfigValidator() {
        if (configValidator == null) {
            configValidator = applicationModel.getBeanFactory().getBean(ConfigValidator.class);
        }
        return configValidator;
    }

    /**
     * The configuration that does not affect the main process is not necessary.
     *
     * @param clazz
     * @param <T>
     * @return
     */
    protected <T extends AbstractConfig> boolean isRequired(Class<T> clazz) {
        if (clazz == RegistryConfig.class
            || clazz == MetadataReportConfig.class
            || clazz == MonitorConfig.class
            || clazz == MetricsConfig.class
            || clazz == TracingConfig.class) {
            return false;
        }
        return true;
    }

    private <T extends AbstractConfig> boolean shouldAddDefaultConfig(Class<T> clazz) {
        // Configurations that are not required will not be automatically added to the default configuration
        if (!isRequired(clazz)) {
            return false;
        }
        return this.getDefaultConfigs(clazz).isEmpty();
    }

    public void refreshAll() {
    }

    /**
     * In some scenario,  we may need to add and remove ServiceConfig or ReferenceConfig dynamically.
     *
     * @param config the config instance to remove.
     * @return
     */
    public boolean removeConfig(AbstractConfig config) {
        if (config == null) {
            return false;
        }

        Map<String, AbstractConfig> configs = configsCache.get(getTagName(config.getClass()));
        if (CollectionUtils.isNotEmptyMap(configs)) {
            // lock by config type
            synchronized (configs) {
                return removeIfAbsent(config, configs);
            }
        }
        return false;
    }

    @Override
    public void destroy() throws IllegalStateException {
        clear();
    }

    public void clear() {
        this.configsCache.clear();
        this.configIdIndexes.clear();
        this.duplicatedConfigs.clear();
    }

    public boolean isInitialized() {
        return initialized.get();
    }
}
