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

import org.apache.dubbo.common.Extension;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.beans.support.InstantiationStrategy;
import org.apache.dubbo.common.compact.Dubbo2ActivateUtils;
import org.apache.dubbo.common.compact.Dubbo2CompactUtils;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.inject.AdaptiveExtensionInjector;
import org.apache.dubbo.common.extension.inject.SpiExtensionInjector;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.extension.support.WrapperComparator;
import org.apache.dubbo.common.lang.Prioritized;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.resource.Disposable;
import org.apache.dubbo.common.threadpool.manager.DefaultExecutorRepository;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassLoaderResourceLoader;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.NativeUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.model.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.ref.SoftReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.ServiceLoader.load;
import static java.util.stream.StreamSupport.stream;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.COMMON_ERROR_LOAD_EXTENSION;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_LOAD_ENV_VARIABLE;

/**
 * {@link org.apache.dubbo.rpc.model.ApplicationModel}, {@code DubboBootstrap} and this class are
 * at present designed to be singleton or static (by itself totally static or uses some static fields).
 * So the instances returned from them are of process or classloader scope. If you want to support
 * multiple dubbo servers in a single process, you may need to refactor these three classes.
 * <p>
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(ExtensionLoader.class);

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");
    private static final String SPECIAL_SPI_PROPERTIES = "special_spi.properties";

    /**
     * {@link ExtensionLoader#createExtension(String, boolean)}方法中添加值
     * 根据方法传入的name,获取对应的class
     * 1,如果有有参构造器,且所有的构造器参数都是{@link ScopeModel}的子类,则选择它生成对象
     * 2,否则用无参构造器生成对象
     * <p>
     * key为name对应的class
     * value为上述生成的对象
     */
    private final ConcurrentMap<Class<?>, Object> extensionInstances = new ConcurrentHashMap<>(64);

    /**
     * 标有注解{@link SPI}的接口的class
     * 可以说，它相当于表的主键，其它方法都围绕它来服务
     * {@link ExtensionLoader#ExtensionLoader(Class, ExtensionDirector, ScopeModel)}构造器中赋值
     */
    private final Class<?> type;

    /**
     * <p>
     * {@link ExtensionLoader#ExtensionLoader(java.lang.Class, org.apache.dubbo.common.extension.ExtensionDirector, org.apache.dubbo.rpc.model.ScopeModel)}
     * 构造函数中赋值{@link AdaptiveExtensionInjector}
     * </p>
     * <p>
     * 如果{@link ExtensionLoader#type}是{@link ExtensionInjector},此值为null。
     * 其它是{@link AdaptiveExtensionInjector}
     */
    private final ExtensionInjector injector;

    /**
     * {@link ExtensionLoader#cacheName(Class, String)}中添加值
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();

    /**
     * {@link ExtensionLoader#getExtensionClasses()}方法中赋值
     * 简单点来说,就是根据{@link ExtensionLoader#type}和下面的三个策略,
     * {@link DubboInternalLoadingStrategy}
     * {@link DubboLoadingStrategy}
     * {@link ServicesLoadingStrategy}
     * 获取的name和对应的具体实现类的对应关系
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    /**
     * {@link ExtensionLoader#cacheActivateClass(java.lang.Class, java.lang.String)}
     * 中添加元素
     */
    private final Map<String, Object> cachedActivates = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Set<String>> cachedActivateGroups = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String[][]> cachedActivateValues = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();

    /**
     *
     */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();
    /**
     * {@link ExtensionLoader#cacheAdaptiveClass(java.lang.Class, boolean)}中设置值
     * {@link ExtensionLoader#type}的实现类中(仅限符合条件加载进来的),如果有的话，那只有一个有注解{@link Adaptive}。
     * 该值即缓存的该实现类
     */
    private volatile Class<?> cachedAdaptiveClass = null;

    /**
     * 缓存了{@link ExtensionLoader#type}上的{@link SPI}的{@link SPI#value()}
     * {@link ExtensionLoader#cacheDefaultExtensionName()}中赋值
     */
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;

    /**
     * {@link ExtensionLoader#cacheWrapperClass(java.lang.Class)}中有添加
     * 包装类的判定:
     * 构造函数中,存在只有一个参数的构造器,且该参数为{@link ExtensionLoader#type}类型
     * <p>
     * 这里为啥是一个set呢?
     * 因为{@link ExtensionLoader#type}有很多实现类啊
     */
    private Set<Class<?>> cachedWrapperClasses;

    private final Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    /**
     * 通过{@link SPI}加载出的{@link LoadingStrategy}数组
     * {@link org.apache.dubbo.common.extension.DubboInternalLoadingStrategy}
     * META-INF/dubbo/internal/
     * {@link org.apache.dubbo.common.extension.DubboLoadingStrategy}
     * META-INF/dubbo/
     * {@link org.apache.dubbo.common.extension.ServicesLoadingStrategy}
     * META-INF/services/
     */
    private static volatile LoadingStrategy[] strategies = loadLoadingStrategies();

    /**
     * special_spi.properties配置的数据
     * 从项目来看,这个配置文件不存在,也即这个map是空的
     */
    private static final Map<String, String> specialSPILoadingStrategyMap = getSpecialSPILoadingStrategyMap();

    private static SoftReference<Map<java.net.URL, List<String>>> urlListMapCache =
        new SoftReference<>(new ConcurrentHashMap<>());

    /**
     * {@link ScopeModelAware}
     * {@link ExtensionAccessorAware}
     * 两个接口的所有方法吧
     */
    private static final List<String> ignoredInjectMethodsDesc = getIgnoredInjectMethodsDesc();

    /**
     * Record all unacceptable exceptions when using SPI
     */
    private final Set<String> unacceptableExceptions = new ConcurrentHashSet<>();

    /**
     * 创建本实例的{@link ExtensionDirector}
     * {@link ExtensionLoader#ExtensionLoader(Class, ExtensionDirector, ScopeModel)}构造器中赋值
     */
    private final ExtensionDirector extensionDirector;

    /**
     * {@link ExtensionLoader#ExtensionLoader(Class, ExtensionDirector, ScopeModel)}构造器中赋值
     * {@link ScopeModelAwareExtensionProcessor}
     * 通过{@link ExtensionLoader#extensionDirector}的{@link ExtensionDirector#getExtensionPostProcessors()}获取的
     */
    private final List<ExtensionPostProcessor> extensionPostProcessors;

    /**
     * {@link ExtensionLoader#initInstantiationStrategy()}中赋值
     * 它是取了{@link ExtensionLoader#extensionPostProcessors}中实现了接口{@link ScopeModelAccessor}
     * 然后再通过{@link InstantiationStrategy#InstantiationStrategy(ScopeModelAccessor)}封装的，
     * 取的是第一个
     */
    private InstantiationStrategy instantiationStrategy;
    private final ActivateComparator activateComparator;
    /**
     * 传递的是{@link FrameworkModel}
     */
    private final ScopeModel scopeModel;
    private final AtomicBoolean destroyed = new AtomicBoolean();

    public static void setLoadingStrategies(LoadingStrategy... strategies) {
        if (ArrayUtils.isNotEmpty(strategies)) {
            ExtensionLoader.strategies = strategies;
        }
    }

    /**
     * Load all {@link Prioritized prioritized} {@link LoadingStrategy Loading Strategies} via {@link ServiceLoader}
     * <p>
     * 关于{@link SPI}
     * https://zhuanlan.zhihu.com/p/212850943
     * </p>
     * <p>
     * 目录src/main/resources/META-INF/services/org.apache.dubbo.common.extension.LoadingStrategy
     * {@link org.apache.dubbo.common.extension.DubboInternalLoadingStrategy}
     * {@link org.apache.dubbo.common.extension.DubboLoadingStrategy}
     * {@link org.apache.dubbo.common.extension.ServicesLoadingStrategy}
     *
     * @return non-null
     * @since 2.7.7
     */
    private static LoadingStrategy[] loadLoadingStrategies() {
        /**
         * 呃，调用了{@link ServiceLoader#load(Class)}方法,
         * 这是一个静态方法
         */
        return stream(load(LoadingStrategy.class).spliterator(), false).sorted().toArray(LoadingStrategy[]::new);
    }

    /**
     * some spi are implements by dubbo framework only and scan multi classloaders resources may cause
     * application startup very slow
     *
     * @return
     */
    private static Map<String, String> getSpecialSPILoadingStrategyMap() {
        Map map = new ConcurrentHashMap<>();
        Properties properties = loadProperties(ExtensionLoader.class.getClassLoader(), SPECIAL_SPI_PROPERTIES);
        map.putAll(properties);
        return map;
    }

    /**
     * Get all {@link LoadingStrategy Loading Strategies}
     *
     * @return non-null
     * @see LoadingStrategy
     * @see Prioritized
     * @since 2.7.7
     */
    public static List<LoadingStrategy> getLoadingStrategies() {
        return asList(strategies);
    }

    private static List<String> getIgnoredInjectMethodsDesc() {
        List<String> ignoreInjectMethodsDesc = new ArrayList<>();
        Arrays.stream(ScopeModelAware.class.getMethods())
            .map(ReflectUtils::getDesc)
            .forEach(ignoreInjectMethodsDesc::add);
        Arrays.stream(ExtensionAccessorAware.class.getMethods())
            .map(ReflectUtils::getDesc)
            .forEach(ignoreInjectMethodsDesc::add);
        return ignoreInjectMethodsDesc;
    }

    /**
     * <p>
     * 在{@link ExtensionDirector#createExtensionLoader0(java.lang.Class)}中传递的参数:
     * type为有注解{@link SPI}的接口的class
     * scopeModel传递的是{@link FrameworkModel}
     * </p>
     *
     * @param type              标有注解{@link SPI}的接口的class
     * @param extensionDirector 创建本实例的{@link ExtensionDirector}
     * @param scopeModel        本实例的作用域
     */
    ExtensionLoader(Class<?> type, ExtensionDirector extensionDirector, ScopeModel scopeModel) {
        this.type = type;
        this.extensionDirector = extensionDirector;
        this.extensionPostProcessors = extensionDirector.getExtensionPostProcessors();
        initInstantiationStrategy();
        this.injector = (type == ExtensionInjector.class
            ? null
            : extensionDirector.getExtensionLoader(ExtensionInjector.class).getAdaptiveExtension());
        this.activateComparator = new ActivateComparator(extensionDirector);
        this.scopeModel = scopeModel;
    }

    /**
     * <p>
     * {@link ExtensionLoader#ExtensionLoader(java.lang.Class, org.apache.dubbo.common.extension.ExtensionDirector, org.apache.dubbo.rpc.model.ScopeModel)}
     * 构造函数中调用
     * </p>
     */
    private void initInstantiationStrategy() {
        /**
         * {@link ScopeModelAwareExtensionProcessor}
         */
        instantiationStrategy = extensionPostProcessors.stream()
            .filter(extensionPostProcessor -> extensionPostProcessor instanceof ScopeModelAccessor)
            .map(extensionPostProcessor -> new InstantiationStrategy((ScopeModelAccessor) extensionPostProcessor))
            .findFirst()
            .orElse(new InstantiationStrategy());
    }

    /**
     * @see ApplicationModel#getExtensionDirector()
     * @see FrameworkModel#getExtensionDirector()
     * @see ModuleModel#getExtensionDirector()
     * @see ExtensionDirector#getExtensionLoader(java.lang.Class)
     * @deprecated get extension loader from extension director of some module.
     */
    @Deprecated
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        return ApplicationModel.defaultModel().getDefaultModule().getExtensionLoader(type);
    }

    @Deprecated
    public static void resetExtensionLoader(Class type) {
    }

    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        // destroy raw extension instance
        extensionInstances.forEach((type, instance) -> {
            if (instance instanceof Disposable) {
                Disposable disposable = (Disposable) instance;
                try {
                    disposable.destroy();
                } catch (Exception e) {
                    logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "", "Error destroying extension " + disposable, e);
                }
            }
        });
        extensionInstances.clear();

        // destroy wrapped extension instance
        for (Holder<Object> holder : cachedInstances.values()) {
            Object wrappedInstance = holder.get();
            if (wrappedInstance instanceof Disposable) {
                Disposable disposable = (Disposable) wrappedInstance;
                try {
                    disposable.destroy();
                } catch (Exception e) {
                    logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "", "Error destroying extension " + disposable, e);
                }
            }
        }
        cachedInstances.clear();
    }

    private void checkDestroyed() {
        if (destroyed.get()) {
            throw new IllegalStateException("ExtensionLoader is destroyed: " + type);
        }
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses(); // load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    @SuppressWarnings("deprecation")
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        checkDestroyed();
        // solve the bug of using @SPI's wrapper method to report a null pointer exception.
        Map<Class<?>, T> activateExtensionsMap = new TreeMap<>(activateComparator);
        List<String> names = values == null
            ? new ArrayList<>(0)
            : Arrays.stream(values).map(StringUtils::trim).collect(Collectors.toList());
        Set<String> namesSet = new HashSet<>(names);
        if (!namesSet.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {
            if (cachedActivateGroups.size() == 0) {
                synchronized (cachedActivateGroups) {
                    // cache all extensions
                    if (cachedActivateGroups.size() == 0) {
                        getExtensionClasses();
                        for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                            String name = entry.getKey();
                            Object activate = entry.getValue();

                            String[] activateGroup, activateValue;

                            if (activate instanceof Activate) {
                                activateGroup = ((Activate) activate).group();
                                activateValue = ((Activate) activate).value();
                            } else if (Dubbo2CompactUtils.isEnabled()
                                && Dubbo2ActivateUtils.isActivateLoaded()
                                && Dubbo2ActivateUtils.getActivateClass().isAssignableFrom(activate.getClass())) {
                                activateGroup = Dubbo2ActivateUtils.getGroup((Annotation) activate);
                                activateValue = Dubbo2ActivateUtils.getValue((Annotation) activate);
                            } else {
                                continue;
                            }
                            cachedActivateGroups.put(name, new HashSet<>(Arrays.asList(activateGroup)));
                            String[][] keyPairs = new String[activateValue.length][];
                            for (int i = 0; i < activateValue.length; i++) {
                                if (activateValue[i].contains(":")) {
                                    keyPairs[i] = new String[2];
                                    String[] arr = activateValue[i].split(":");
                                    keyPairs[i][0] = arr[0];
                                    keyPairs[i][1] = arr[1];
                                } else {
                                    keyPairs[i] = new String[1];
                                    keyPairs[i][0] = activateValue[i];
                                }
                            }
                            cachedActivateValues.put(name, keyPairs);
                        }
                    }
                }
            }

            // traverse all cached extensions
            cachedActivateGroups.forEach((name, activateGroup) -> {
                if (isMatchGroup(group, activateGroup)
                    && !namesSet.contains(name)
                    && !namesSet.contains(REMOVE_VALUE_PREFIX + name)
                    && isActive(cachedActivateValues.get(name), url)) {

                    activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
                }
            });
        }

        if (namesSet.contains(DEFAULT_KEY)) {
            // will affect order
            // `ext1,default,ext2` means ext1 will happens before all of the default extensions while ext2 will after
            // them
            ArrayList<T> extensionsResult = new ArrayList<>(activateExtensionsMap.size() + names.size());
            for (String name : names) {
                if (name.startsWith(REMOVE_VALUE_PREFIX) || namesSet.contains(REMOVE_VALUE_PREFIX + name)) {
                    continue;
                }
                if (DEFAULT_KEY.equals(name)) {
                    extensionsResult.addAll(activateExtensionsMap.values());
                    continue;
                }
                if (containsExtension(name)) {
                    extensionsResult.add(getExtension(name));
                }
            }
            return extensionsResult;
        } else {
            // add extensions, will be sorted by its order
            for (String name : names) {
                if (name.startsWith(REMOVE_VALUE_PREFIX) || namesSet.contains(REMOVE_VALUE_PREFIX + name)) {
                    continue;
                }
                if (DEFAULT_KEY.equals(name)) {
                    continue;
                }
                if (containsExtension(name)) {
                    activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
                }
            }
            return new ArrayList<>(activateExtensionsMap.values());
        }
    }

    public List<T> getActivateExtensions() {
        checkDestroyed();
        List<T> activateExtensions = new ArrayList<>();
        TreeMap<Class<?>, T> activateExtensionsMap = new TreeMap<>(activateComparator);
        getExtensionClasses();
        for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
            String name = entry.getKey();
            Object activate = entry.getValue();
            if (!(activate instanceof Activate)) {
                continue;
            }
            activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
        }
        if (!activateExtensionsMap.isEmpty()) {
            activateExtensions.addAll(activateExtensionsMap.values());
        }

        return activateExtensions;
    }

    private boolean isMatchGroup(String group, Set<String> groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (CollectionUtils.isNotEmpty(groups)) {
            return groups.contains(group);
        }
        return false;
    }

    private boolean isActive(String[][] keyPairs, URL url) {
        if (keyPairs.length == 0) {
            return true;
        }
        for (String[] keyPair : keyPairs) {
            // @Active(value="key1:value1, key2:value2")
            String key;
            String keyValue = null;
            if (keyPair.length > 1) {
                key = keyPair[0];
                keyValue = keyPair[1];
            } else {
                key = keyPair[0];
            }

            String realValue = url.getParameter(key);
            if (StringUtils.isEmpty(realValue)) {
                realValue = url.getAnyMethodParameter(key);
            }
            if ((keyValue != null && keyValue.equals(realValue))
                || (keyValue == null && ConfigUtils.isNotEmpty(realValue))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        checkDestroyed();
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    /**
     * {@link ExtensionLoader#getExtension(String, boolean)}中调用
     *
     * @param name
     * @return
     */
    private Holder<Object> getOrCreateHolder(String name) {
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    @SuppressWarnings("unchecked")
    public List<T> getLoadedExtensionInstances() {
        checkDestroyed();
        List<T> instances = new ArrayList<>();
        cachedInstances.values().forEach(holder -> instances.add((T) holder.get()));
        return instances;
    }

    /**
     * Find the extension with the given name.
     * <p>
     * 这个方法经常调用，需要关注
     * </p>
     *
     * @throws IllegalStateException If the specified extension is not found.
     */
    public T getExtension(String name) {
        T extension = getExtension(name, true);
        if (extension == null) {
            throw new IllegalArgumentException("Not find extension: " + name);
        }
        return extension;
    }

    /**
     * <p>
     * {@link ExtensionLoader#getExtension(java.lang.String)}中调用
     * </p>
     *
     * @param name
     * @param wrap
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name, boolean wrap) {
        checkDestroyed();
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        if ("true".equals(name)) {
            /**
             * 呃，这个地方一定要注意啊，细节
             */
            return getDefaultExtension();
        }
        String cacheKey = name;
        if (!wrap) {
            /**
             * 不包装的话，name就变了
             */
            cacheKey += "_origin";
        }
        final Holder<Object> holder = getOrCreateHolder(cacheKey);
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    instance = createExtension(name, wrap);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Get the extension by specified name if found, or {@link #getDefaultExtension() returns the default one}
     *
     * @param name the name of extension
     * @return non-null
     */
    public T getOrDefaultExtension(String name) {
        return containsExtension(name) ? getExtension(name) : getDefaultExtension();
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     * <p>
     * 获取{@link ExtensionLoader#cachedDefaultName}记录的那个扩展
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        checkDestroyed();
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    /**
     * {@link ExtensionLoader#getSupportedExtensionInstances()}中调用
     * {@link AdaptiveExtensionInjector#initialize()}中调用
     * {@link SpiExtensionInjector#getInstance(java.lang.Class, java.lang.String)}中调用
     *
     * @return
     */
    public Set<String> getSupportedExtensions() {
        checkDestroyed();
        Map<String, Class<?>> classes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(classes.keySet()));
    }

    /**
     * {@link org.apache.dubbo.config.spring.context.DubboSpringInitializer#customize}
     * 中调用
     *
     * @return
     */
    public Set<T> getSupportedExtensionInstances() {
        checkDestroyed();
        List<T> instances = new LinkedList<>();
        Set<String> supportedExtensions = getSupportedExtensions();
        if (CollectionUtils.isNotEmpty(supportedExtensions)) {
            for (String name : supportedExtensions) {
                instances.add(getExtension(name));
            }
        }
        // sort the Prioritized instances
        instances.sort(Prioritized.COMPARATOR);
        return new LinkedHashSet<>(instances);
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        checkDestroyed();
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " + clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " + clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " + name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        checkDestroyed();
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " + clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " + clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " + name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * <p>
     * {@link ExtensionLoader#ExtensionLoader(java.lang.Class, org.apache.dubbo.common.extension.ExtensionDirector, org.apache.dubbo.rpc.model.ScopeModel)}
     * 中调用
     * {@link org.apache.dubbo.config.ServiceConfig#postProcessAfterScopeModelChanged(org.apache.dubbo.rpc.model.ScopeModel, org.apache.dubbo.rpc.model.ScopeModel)}
     * 中调用
     * </p>
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        checkDestroyed();
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError != null) {
                throw new IllegalStateException(
                    "Failed to create adaptive instance: " + createAdaptiveInstanceError.toString(),
                    createAdaptiveInstanceError);
            }

            synchronized (cachedAdaptiveInstance) {
                instance = cachedAdaptiveInstance.get();
                if (instance == null) {
                    try {
                        instance = createAdaptiveExtension();
                        cachedAdaptiveInstance.set(instance);
                    } catch (Throwable t) {
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);

        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().startsWith(name.toLowerCase())) {
                if (i == 1) {
                    buf.append(", possible causes: ");
                }
                buf.append("\r\n(");
                buf.append(i++);
                buf.append(") ");
                buf.append(entry.getKey());
                buf.append(":\r\n");
                buf.append(StringUtils.toString(entry.getValue()));
            }
        }

        if (i == 1) {
            buf.append(", no related exception was found, please check whether related SPI module is missing.");
        }
        return new IllegalStateException(buf.toString());
    }

    /**
     * <p>
     * {@link ExtensionLoader#getExtension(java.lang.String, boolean)}中调用
     * </p>
     * <p>
     * 这个方法和{@link ExtensionLoader#createAdaptiveExtension()}有些相似
     *
     * @param name
     * @param wrap
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name, boolean wrap) {
        /**
         * 获取name对应的class
         */
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null || unacceptableExceptions.contains(name)) {
            throw findException(name);
        }
        try {
            T instance = (T) extensionInstances.get(clazz);
            if (instance == null) {
                extensionInstances.putIfAbsent(clazz, createExtensionInstance(clazz));
                instance = (T) extensionInstances.get(clazz);
                /**
                 * 目前来看，没有做什么操作
                 */
                instance = postProcessBeforeInitialization(instance, name);
                /**
                 * 这个方法很重要
                 */
                injectExtension(instance);
                instance = postProcessAfterInitialization(instance, name);
            }

            if (wrap) {
                /**
                 * 下面逻辑很重要
                 * 以{@link org.apache.dubbo.rpc.Protocol}为例:
                 * 有如下几个包装类
                 * {@link org.apache.dubbo.rpc.protocol.InvokerCountWrapper}
                 * {@link org.apache.dubbo.rpc.protocol.ProtocolSerializationWrapper}
                 * {@link org.apache.dubbo.rpc.protocol.ProtocolListenerWrapper}
                 * {@link org.apache.dubbo.rpc.protocol.ProtocolSecurityWrapper}
                 * {@link org.apache.dubbo.qos.protocol.QosProtocolWrapper}
                 */
                List<Class<?>> wrapperClassesList = new ArrayList<>();
                if (cachedWrapperClasses != null) {
                    wrapperClassesList.addAll(cachedWrapperClasses);
                    wrapperClassesList.sort(WrapperComparator.COMPARATOR);
                    /**
                     * 从这里可以看到,{@link Activate#order()}越大的排的越靠前
                     */
                    Collections.reverse(wrapperClassesList);
                }

                if (CollectionUtils.isNotEmpty(wrapperClassesList)) {
                    for (Class<?> wrapperClass : wrapperClassesList) {
                        Wrapper wrapper = wrapperClass.getAnnotation(Wrapper.class);
                        boolean match = (wrapper == null)
                            /**
                             * 没有{@link Wrapper}注解
                             */
                            || ((ArrayUtils.isEmpty(wrapper.matches()) || ArrayUtils.contains(wrapper.matches(), name))
                            && !ArrayUtils.contains(wrapper.mismatches(), name));
                        if (match) {
                            instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                            instance = postProcessAfterInitialization(instance, name);
                        }
                    }
                }
            }

            // Warning: After an instance of Lifecycle is wrapped by cachedWrapperClasses, it may not still be Lifecycle
            // instance, this application may not invoke the lifecycle.initialize hook.
            initExtension(instance);
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException(
                "Extension instance (name: " + name + ", class: " + type + ") couldn't be instantiated: "
                    + t.getMessage(),
                t);
        }
    }

    /**
     * <p>
     * {@link ExtensionLoader#createExtension(java.lang.String, boolean)}中调用
     * </p>
     *
     * @param type
     * @return
     * @throws ReflectiveOperationException
     */
    private Object createExtensionInstance(Class<?> type) throws ReflectiveOperationException {
        return instantiationStrategy.instantiate(type);
    }

    /**
     * <p>
     * {@link ExtensionLoader#createExtension(java.lang.String, boolean)}中调用
     * </p>
     *
     * @param instance
     * @param name
     * @return
     * @throws Exception
     */
    @SuppressWarnings("unchecked")
    private T postProcessBeforeInitialization(T instance, String name) throws Exception {
        if (extensionPostProcessors != null) {
            for (ExtensionPostProcessor processor : extensionPostProcessors) {
                /**
                 * 目前来看，好像只有{@link ScopeModelAwareExtensionProcessor}一个实现类
                 */
                instance = (T) processor.postProcessBeforeInitialization(instance, name);
            }
        }
        return instance;
    }

    /**
     * <p>
     * {@link ExtensionLoader#createExtension(java.lang.String, boolean)}中调用
     * </p>
     *
     * @param instance
     * @param name
     * @return
     * @throws Exception
     */
    @SuppressWarnings("unchecked")
    private T postProcessAfterInitialization(T instance, String name) throws Exception {
        if (instance instanceof ExtensionAccessorAware) {
            /**
             * 实现了{@link ExtensionAccessorAware}接口的
             * 目前通过项目看实现类:
             * {@link AdaptiveExtensionInjector#setExtensionAccessor(ExtensionAccessor)}
             * {@link DefaultExecutorRepository#setExtensionAccessor(ExtensionAccessor)}
             * {@link org.apache.dubbo.monitor.dubbo.MetricsFilter#setExtensionAccessor(ExtensionAccessor)}
             * {@link SpiExtensionInjector#setExtensionAccessor(ExtensionAccessor)}
             */
            ((ExtensionAccessorAware) instance).setExtensionAccessor(extensionDirector);
        }
        if (extensionPostProcessors != null) {
            for (ExtensionPostProcessor processor : extensionPostProcessors) {
                /**
                 * 判断instance是否实现了{@link ScopeModelAware}接口
                 * 分别赋值
                 */
                instance = (T) processor.postProcessAfterInitialization(instance, name);
            }
        }
        return instance;
    }

    private boolean containsExtension(String name) {
        return getExtensionClasses().containsKey(name);
    }

    /**
     * <p>
     * {@link ExtensionLoader#createExtension(java.lang.String, boolean)}中调用
     * {@link ExtensionLoader#createAdaptiveExtension()}中调用
     * </p>
     *
     * @param instance
     * @return
     */
    private T injectExtension(T instance) {
        if (injector == null) {
            return instance;
        }

        try {
            for (Method method : instance.getClass().getMethods()) {
                /**
                 * 遍历所有方法
                 */
                if (!isSetter(method)) {
                    /**
                     * 如果不是set方法,下面逻辑不走
                     * 1,以"set"开头
                     * 2,方法参数只有一个
                     * 3,方法是public的
                     */
                    continue;
                }
                /**
                 * Check {@link DisableInject} to see if we need auto-injection for this property
                 */
                if (method.isAnnotationPresent(DisableInject.class)) {
                    /**
                     * 方法上标注{@link DisableInject}注解的，不处理
                     * 参考{@link Environment}
                     */
                    continue;
                }

                // When spiXXX implements ScopeModelAware, ExtensionAccessorAware,
                // the setXXX of ScopeModelAware and ExtensionAccessorAware does not need to be injected
                if (method.getDeclaringClass() == ScopeModelAware.class) {
                    /**
                     * 不是从{@link ScopeModelAware}继承来的方法
                     * 未实现{@link ScopeModelAware}接口
                     */
                    continue;
                }
                if (instance instanceof ScopeModelAware || instance instanceof ExtensionAccessorAware) {
                    if (ignoredInjectMethodsDesc.contains(ReflectUtils.getDesc(method))) {
                        /**
                         * 实现了{@link ScopeModelAware}和{@link ExtensionAccessorAware}
                         * 的接口不处理
                         */
                        continue;
                    }
                }

                Class<?> pt = method.getParameterTypes()[0];
                if (ReflectUtils.isPrimitives(pt)) {
                    /**
                     * 如果这仅有的第一个参数是8种基本类型，不处理
                     */
                    continue;
                }

                try {
                    /**
                     * 获取setXxx的xxx，首字母小写
                     */
                    String property = getSetterProperty(method);
                    /**
                     * {@link AdaptiveExtensionInjector#getInstance(java.lang.Class, java.lang.String)}
                     * 这个地方非常重要!!!
                     */
                    Object object = injector.getInstance(pt, property);
                    if (object != null) {
                        method.invoke(instance, object);
                    }
                } catch (Exception e) {
                    logger.error(
                        COMMON_ERROR_LOAD_EXTENSION,
                        "",
                        "",
                        "Failed to inject via method " + method.getName() + " of interface " + type.getName() + ": "
                            + e.getMessage(),
                        e);
                }
            }
        } catch (Exception e) {
            logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "", e.getMessage(), e);
        }
        return instance;
    }

    /**
     * <p>
     * {@link ExtensionLoader#createAdaptiveExtension()}中调用
     * </p>
     *
     * @param instance
     */
    private void initExtension(T instance) {
        if (instance instanceof Lifecycle) {
            Lifecycle lifecycle = (Lifecycle) instance;
            lifecycle.initialize();
        }
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * 获取setXXX方法的XXX,首字母小写
     * <p>
     * <p>
     * {@link ExtensionLoader#injectExtension(java.lang.Object)}中调用
     * </p>
     * return "", if setter name with length less than 3
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3
            ? method.getName().substring(3, 4).toLowerCase()
            + method.getName().substring(4)
            : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     * <p>
     * {@link ExtensionLoader#injectExtension(Object)}中调用
     * 1,以"set"开头
     * 2,方法参数只有一个
     * 3,方法是public的
     */
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
            && method.getParameterTypes().length == 1
            && Modifier.isPublic(method.getModifiers());
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    /**
     * {@link ExtensionLoader#getSupportedExtensions()}中调用
     * {@link ExtensionLoader#createExtension(java.lang.String, boolean)}中调用
     *
     * @return
     */
    private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                // 双重校验
                classes = cachedClasses.get();
                if (classes == null) {
                    try {
                        classes = loadExtensionClasses();
                    } catch (InterruptedException e) {
                        logger.error(
                            COMMON_ERROR_LOAD_EXTENSION,
                            "",
                            "",
                            "Exception occurred when loading extension class (interface: " + type + ")",
                            e);
                        throw new IllegalStateException(
                            "Exception occurred when loading extension class (interface: " + type + ")", e);
                    }
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * synchronized in getExtensionClasses
     * <p>
     * 这个地方是通过{@link SPI}实现的,
     * META-INF/services/org.apache.dubbo.common.extension.LoadingStrategy
     * 找到LoadingStrategy的三个实现类。
     * {@link DubboInternalLoadingStrategy}
     * {@link DubboLoadingStrategy}
     * {@link ServicesLoadingStrategy}
     * 分别在这三种加载模式中，寻找{@link ExtensionLoader#type}的实现扩展类，返回
     * </p>
     * <p>
     * {@link ExtensionLoader#getExtensionClasses()}
     * 中调用
     * </p>
     */
    @SuppressWarnings("deprecation")
    private Map<String, Class<?>> loadExtensionClasses() throws InterruptedException {
        checkDestroyed();
        cacheDefaultExtensionName();

        Map<String, Class<?>> extensionClasses = new HashMap<>();

        /**
         * 这个地方是通过{@link SPI}实现的,
         * META-INF/services/org.apache.dubbo.common.extension.LoadingStrategy
         * {@link DubboInternalLoadingStrategy}
         * {@link DubboLoadingStrategy}
         * {@link ServicesLoadingStrategy}
         */
        for (LoadingStrategy strategy : strategies) {
            loadDirectory(extensionClasses, strategy, type.getName());

            // compatible with old ExtensionFactory
            if (this.type == ExtensionInjector.class) {
                /**
                 * 这个地方关注一下吧，好像也没什么用
                 */
                loadDirectory(extensionClasses, strategy, ExtensionFactory.class.getName());
            }
        }

        return extensionClasses;
    }

    /**
     * {@link ExtensionLoader#loadExtensionClasses()}调用
     *
     * @param extensionClasses 扩展的class
     * @param strategy         策略
     * @param type             接口的name
     * @throws InterruptedException
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses,
                               LoadingStrategy strategy,
                               String type)
        throws InterruptedException {
        loadDirectoryInternal(extensionClasses, strategy, type);
        if (Dubbo2CompactUtils.isEnabled()) {
            try {
                String oldType = type.replace("org.apache", "com.alibaba");
                if (oldType.equals(type)) {
                    return;
                }
                // if class not found,skip try to load resources
                ClassUtils.forName(oldType);
                loadDirectoryInternal(extensionClasses, strategy, oldType);
            } catch (ClassNotFoundException classNotFoundException) {

            }
        }
    }

    /**
     * extract and cache default extension name if exists
     * <p>
     * {@link ExtensionLoader#loadExtensionClasses()}中调用
     * </p>
     */
    private void cacheDefaultExtensionName() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation == null) {
            return;
        }

        String value = defaultAnnotation.value();
        if ((value = value.trim()).length() > 0) {
            /**
             * 从这里可以看到{@link SPI}的value可以配置多个name，中间用","隔开
             */
            String[] names = NAME_SEPARATOR.split(value);
            if (names.length > 1) {
                /**
                 * 呃，这……
                 */
                throw new IllegalStateException("More than 1 default extension name on extension " + type.getName()
                    + ": " + Arrays.toString(names));
            }
            if (names.length == 1) {
                cachedDefaultName = names[0];
            }
        }
    }

    /**
     * {@link ExtensionLoader#loadDirectory(java.util.Map, org.apache.dubbo.common.extension.LoadingStrategy, java.lang.String)}
     * 中调用
     *
     * @param extensionClasses
     * @param loadingStrategy
     * @param type
     * @throws InterruptedException
     */
    private void loadDirectoryInternal(Map<String, Class<?>> extensionClasses,
                                       LoadingStrategy loadingStrategy,
                                       String type) throws InterruptedException {
        /**
         * 查找策略的fileName
         * {@link org.apache.dubbo.common.extension.DubboInternalLoadingStrategy}
         * META-INF/dubbo/internal/
         * {@link org.apache.dubbo.common.extension.DubboLoadingStrategy}
         * META-INF/dubbo/
         * {@link org.apache.dubbo.common.extension.ServicesLoadingStrategy}
         * META-INF/services/
         */
        String fileName = loadingStrategy.directory() + type;
        try {
            List<ClassLoader> classLoadersToLoad = new LinkedList<>();

            // try to load from ExtensionLoader's ClassLoader first
            if (loadingStrategy.preferExtensionClassLoader()) {
                /**
                 * 这个分支不走
                 * 三种策略,好像都是false
                 * 为扩展用的?
                 */
                ClassLoader extensionLoaderClassLoader = ExtensionLoader.class.getClassLoader();
                if (ClassLoader.getSystemClassLoader() != extensionLoaderClassLoader) {
                    classLoadersToLoad.add(extensionLoaderClassLoader);
                }
            }

            if (specialSPILoadingStrategyMap.containsKey(type)) {
                /**
                 * 这个分支也不会走
                 * map为空
                 */
                String internalDirectoryType = specialSPILoadingStrategyMap.get(type);
                // skip to load spi when name don't match
                if (!LoadingStrategy.ALL.equals(internalDirectoryType)
                    && !internalDirectoryType.equals(loadingStrategy.getName())) {
                    return;
                }
                classLoadersToLoad.clear();
                classLoadersToLoad.add(ExtensionLoader.class.getClassLoader());
            } else {
                // load from scope model
                Set<ClassLoader> classLoaders = scopeModel.getClassLoaders();

                if (CollectionUtils.isEmpty(classLoaders)) {
                    /**
                     * 通过{@link ClassLoader#getSystemResources(java.lang.String)}获取路径下的资源
                     */
                    Enumeration<java.net.URL> resources = ClassLoader.getSystemResources(fileName);
                    if (resources != null) {
                        while (resources.hasMoreElements()) {
                            loadResource(
                                extensionClasses,
                                null,
                                resources.nextElement(),
                                loadingStrategy.overridden(),
                                loadingStrategy.includedPackages(),
                                loadingStrategy.excludedPackages(),
                                loadingStrategy.onlyExtensionClassLoaderPackages());
                        }
                    }
                } else {
                    classLoadersToLoad.addAll(classLoaders);
                }
            }

            Map<ClassLoader, Set<java.net.URL>> resources =
                ClassLoaderResourceLoader.loadResources(fileName, classLoadersToLoad);
            resources.forEach(((classLoader, urls) -> {
                loadFromClass(
                    extensionClasses,
                    loadingStrategy.overridden(),
                    urls,
                    classLoader,
                    loadingStrategy.includedPackages(),
                    loadingStrategy.excludedPackages(),
                    loadingStrategy.onlyExtensionClassLoaderPackages());
            }));
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable t) {
            logger.error(
                COMMON_ERROR_LOAD_EXTENSION,
                "",
                "",
                "Exception occurred when loading extension class (interface: " + type + ", description file: "
                    + fileName + ").",
                t);
        }
    }

    /**
     * <p>
     * {@link ExtensionLoader#loadDirectoryInternal(java.util.Map, org.apache.dubbo.common.extension.LoadingStrategy, java.lang.String)}
     * 中调用
     * </p>
     *
     * @param extensionClasses
     * @param overridden
     * @param urls
     * @param classLoader
     * @param includedPackages
     * @param excludedPackages
     * @param onlyExtensionClassLoaderPackages
     */
    private void loadFromClass(Map<String, Class<?>> extensionClasses,
                               boolean overridden,
                               Set<java.net.URL> urls,
                               ClassLoader classLoader,
                               String[] includedPackages,
                               String[] excludedPackages,
                               String[] onlyExtensionClassLoaderPackages) {
        if (CollectionUtils.isNotEmpty(urls)) {
            for (java.net.URL url : urls) {
                loadResource(
                    extensionClasses,
                    classLoader,
                    url,
                    overridden,
                    includedPackages,
                    excludedPackages,
                    onlyExtensionClassLoaderPackages);
            }
        }
    }

    /**
     * {@link ExtensionLoader#loadDirectoryInternal(java.util.Map, org.apache.dubbo.common.extension.LoadingStrategy, java.lang.String)}
     * 中调用
     *
     * @param extensionClasses
     * @param classLoader                      传递的null
     * @param resourceURL
     * @param overridden                       {@link LoadingStrategy#overridden()}
     * @param includedPackages                 {@link LoadingStrategy#includedPackages()}
     * @param excludedPackages                 {@link LoadingStrategy#excludedPackages()}
     * @param onlyExtensionClassLoaderPackages {@link LoadingStrategy#onlyExtensionClassLoaderPackages()}
     */
    private void loadResource(Map<String, Class<?>> extensionClasses,
                              ClassLoader classLoader,
                              java.net.URL resourceURL,
                              boolean overridden,
                              String[] includedPackages,
                              String[] excludedPackages,
                              String[] onlyExtensionClassLoaderPackages) {
        try {
            List<String> newContentList = getResourceContent(resourceURL);
            String clazz;
            for (String line : newContentList) {
                try {
                    String name = null;
                    int i = line.indexOf('=');
                    if (i > 0) {
                        // 字符串中有=的，左边是name，右边是class全名称
                        name = line.substring(0, i).trim();
                        clazz = line.substring(i + 1).trim();
                    } else {
                        // 没有=的，直接就是一个class全名称
                        clazz = line;
                    }
                    if (StringUtils.isNotEmpty(clazz)
                        && !isExcluded(clazz, excludedPackages)
                        && isIncluded(clazz, includedPackages)
                        && !isExcludedByClassLoader(clazz, classLoader, onlyExtensionClassLoaderPackages)) {

                        loadClass(
                            classLoader,
                            extensionClasses,
                            resourceURL,
                            Class.forName(clazz, true, classLoader),
                            name,
                            overridden);
                    }
                } catch (Throwable t) {
                    IllegalStateException e = new IllegalStateException(
                        "Failed to load extension class (interface: " + type + ", class line: " + line + ") in "
                            + resourceURL + ", cause: " + t.getMessage(),
                        t);
                    exceptions.put(line, e);
                }
            }
        } catch (Throwable t) {
            logger.error(
                COMMON_ERROR_LOAD_EXTENSION,
                "",
                "",
                "Exception occurred when loading extension class (interface: " + type + ", class file: "
                    + resourceURL + ") in " + resourceURL,
                t);
        }
    }

    /**
     * 用{@link BufferedReader}按行读取resourceURL的内容。然后把每一行的String字符串(忽略空白行)添加到队列{@link List}中。
     * 将结果存放在{@link ExtensionLoader#urlListMapCache}中
     * <p>
     * {@link ExtensionLoader#loadResource(java.util.Map, java.lang.ClassLoader, java.net.URL, boolean, java.lang.String[], java.lang.String[], java.lang.String[])}
     * 中调用
     * </p>
     *
     * @param resourceURL
     * @return
     * @throws IOException
     */
    private List<String> getResourceContent(java.net.URL resourceURL) throws IOException {
        // 缓存
        Map<java.net.URL, List<String>> urlListMap = urlListMapCache.get();
        if (urlListMap == null) {
            synchronized (ExtensionLoader.class) {
                if ((urlListMap = urlListMapCache.get()) == null) {
                    urlListMap = new ConcurrentHashMap<>();
                    // 这个地方用的是软引用
                    urlListMapCache = new SoftReference<>(urlListMap);
                }
            }
        }

        /**
         * 注意，这个computeIfAbsent和putIfAbsent的区别
         * 1,相同点:只保存第一次的value,如果已经存在该key，则再次执行，value不会变，会返回该value
         * 2,不同点:如果不存在该key，computeIfAbsent会返回计算的值。putIfAbsent会返回null
         * 3,也就是说computeIfAbsent总是会返回第一次的value。而putIfAbsent第一次会返回null,之后返回第一次的value
         */
        List<String> contentList = urlListMap.computeIfAbsent(resourceURL,
            key -> {
                // 由key去初始化List<String>
                List<String> newContentList = new ArrayList<>();

                try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                    // 按行读取
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final int ci = line.indexOf('#');
                        if (ci >= 0) {
                            // 没看懂这个#是什么意思。判断startWith多好
                            line = line.substring(0, ci);
                        }
                        line = line.trim();
                        if (line.length() > 0) {
                            newContentList.add(line);
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
                return newContentList;
            });
        return contentList;
    }

    /**
     * 判断className是否在includedPackages的包含项中
     * 1,如果includedPackages为null,则返回true
     * 2,遍历includedPackages中的每一项,只要有一项符合className.startsWith,返回true
     * 3,includedPackages不为空，且所有的项，都不符合className.startsWith,返回false
     * <p>
     * {@link ExtensionLoader#loadResource(java.util.Map, java.lang.ClassLoader, java.net.URL, boolean, java.lang.String[], java.lang.String[], java.lang.String[])}
     * 中调用
     * </p>
     *
     * @param className
     * @param includedPackages
     * @return
     */
    private boolean isIncluded(String className, String... includedPackages) {
        if (includedPackages != null && includedPackages.length > 0) {
            for (String includedPackage : includedPackages) {
                if (className.startsWith(includedPackage + ".")) {
                    // one match, return true
                    return true;
                }
            }
            // none matcher match, return false
            return false;
        }
        // matcher is empty, return true
        return true;
    }

    /**
     * 判断className是否在excludedPackages排除项中
     * 1,如果excludedPackages为null,返回false
     * 2,遍历excludedPackages的每一项,只要有一项为className的startsWith,即返回true
     * <p>
     * {@link ExtensionLoader#loadResource(java.util.Map, java.lang.ClassLoader, java.net.URL, boolean, java.lang.String[], java.lang.String[], java.lang.String[])}
     * 中调用
     * </p>
     *
     * @param className
     * @param excludedPackages
     * @return
     */
    private boolean isExcluded(String className, String... excludedPackages) {
        if (excludedPackages != null) {
            for (String excludePackage : excludedPackages) {
                if (className.startsWith(excludePackage + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断className是否在onlyExtensionClassLoaderPackages排除项中
     * 1,如果onlyExtensionClassLoaderPackages为空,返回false
     * 2,遍历onlyExtensionClassLoaderPackages的每一项，只要有一项符合className.startsWith，
     * 返回参数中传入的classLoader不是{@link ExtensionLoader}的{@link ClassLoader}。
     * 也即参数中传入的classLoader是{@link ExtensionLoader}的{@link ClassLoader}就不需要排除
     * 3,返回false
     * <p>
     * {@link ExtensionLoader#loadResource(java.util.Map, java.lang.ClassLoader, java.net.URL, boolean, java.lang.String[], java.lang.String[], java.lang.String[])}
     * 中调用
     * </p>
     *
     * @param className
     * @param classLoader
     * @param onlyExtensionClassLoaderPackages
     * @return
     */
    private boolean isExcludedByClassLoader(String className,
                                            ClassLoader classLoader,
                                            String... onlyExtensionClassLoaderPackages) {
        if (onlyExtensionClassLoaderPackages != null) {
            for (String excludePackage : onlyExtensionClassLoaderPackages) {
                if (className.startsWith(excludePackage + ".")) {
                    // if target classLoader is not ExtensionLoader's classLoader should be excluded
                    return !Objects.equals(ExtensionLoader.class.getClassLoader(), classLoader);
                }
            }
        }
        return false;
    }

    /**
     * <p>
     * {@link ExtensionLoader#loadResource(java.util.Map, java.lang.ClassLoader, java.net.URL, boolean, java.lang.String[], java.lang.String[], java.lang.String[])}
     * 中调用
     * </p>
     *
     * @param classLoader      类加载器
     * @param extensionClasses 加载扩展class的缓存map
     * @param resourceURL      配置的资源，好像用来打日志了
     * @param clazz            扩展class
     * @param name             配置文件中每一行的配置的key
     * @param overridden       false
     */
    private void loadClass(ClassLoader classLoader,
                           Map<String, Class<?>> extensionClasses,
                           java.net.URL resourceURL,
                           Class<?> clazz,
                           String name,
                           boolean overridden) {
        if (!type.isAssignableFrom(clazz)) {
            // 如果配置的clazz不是type的实现类，抛异常
            throw new IllegalStateException(
                "Error occurred when loading extension class (interface: " + type + ", class line: "
                    + clazz.getName() + "), class " + clazz.getName() + " is not subtype of interface.");
        }

        /**
         * 呃，这个地方很重要。
         * 像{@link org.apache.dubbo.common.extension.ExtensionInjector}
         * 实现类中的{@link AdaptiveExtensionInjector} 有注解{@link Adaptive}
         * 那么在方法{@link ExtensionLoader#cacheAdaptiveClass(java.lang.Class, boolean)}
         * 中会将{@link ExtensionLoader#cachedAdaptiveClass}设置为该 clazz
         */
        boolean isActive = loadClassIfActive(classLoader, clazz);

        if (!isActive) {
            return;
        }

        if (clazz.isAnnotationPresent(Adaptive.class)) {
            /**
             * 很重要。
             * 这几个条件是if else的关系
             */
            cacheAdaptiveClass(clazz, overridden);
        } else if (isWrapperClass(clazz)) {
            /**
             * 很重要
             * 进入这个条件的逻辑就是，所有的构造函数中,有一个只有一个参数的构造器，且该构造器参数类型是{@link ExtensionLoader#type}
             */
            cacheWrapperClass(clazz);
        } else {
            if (StringUtils.isEmpty(name)) {
                /**
                 * 如果name为空,
                 * 1,如果参数clazz上有注解{@link Extension},返回{@link Extension#value()}
                 * 2,如果clazz的简单类名(不包括包名),以{@link ExtensionLoader#type}的简单类名结尾，返回之前的部分(全部小写)
                 * 3,返回clazz的简单类名(全小写)
                 */
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName()
                        + " in the config " + resourceURL);
                }
            }
            /**
             * 以","分割
             */
            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {
                    cacheName(clazz, n);
                    saveInExtensionClass(extensionClasses, clazz, n, overridden);
                }
            }
        }
    }

    /**
     * 1,读取clazz上的{@link Activate}注解
     * 2,如果不存在，则返回true
     * 3,获取注解上的{@link Activate#onClass()} className数组
     * 4,用classLoader加载该数组的每个className,如果异常返回false
     * 5,如果无异常,返回true
     * <p>
     * {@link ExtensionLoader#loadClass(java.lang.ClassLoader, java.util.Map, java.net.URL, java.lang.Class, java.lang.String, boolean)}
     * 中调用
     * </p>
     *
     * @param classLoader
     * @param clazz
     * @return
     */
    private boolean loadClassIfActive(ClassLoader classLoader, Class<?> clazz) {
        /**
         * 取clazz的注解{@link Activate}
         */
        Activate activate = clazz.getAnnotation(Activate.class);

        if (activate == null) {
            // 如果没有该注解，返回true
            return true;
        }
        String[] onClass = null;

        if (activate instanceof Activate) {
            onClass = ((Activate) activate).onClass();
        } else if (Dubbo2CompactUtils.isEnabled()
            && Dubbo2ActivateUtils.isActivateLoaded()
            && Dubbo2ActivateUtils.getActivateClass().isAssignableFrom(activate.getClass())) {
            onClass = Dubbo2ActivateUtils.getOnClass(activate);
        }

        boolean isActive = true;

        if (null != onClass && onClass.length > 0) {
            isActive = Arrays.stream(onClass)
                .filter(StringUtils::isNotBlank)
                .allMatch(className -> ClassUtils.isPresent(className, classLoader));
        }
        return isActive;
    }

    /**
     * cache name
     * 将clazz和name关系保存在{@link ExtensionLoader#cachedNames}中。
     * 从代码中来看，只会在第一次调用中建立关系。
     * <p>
     * {@link ExtensionLoader#loadClass(java.lang.ClassLoader, java.util.Map, java.net.URL, java.lang.Class, java.lang.String, boolean)}
     * 中调用
     * </p>
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     * 将name和clazz添加到extensionClasses中,key为name,value为clazz
     * <p>
     * {@link ExtensionLoader#loadClass(java.lang.ClassLoader, java.util.Map, java.net.URL, java.lang.Class, java.lang.String, boolean)}
     * 中调用
     * </p>
     *
     * @param extensionClasses
     * @param clazz
     * @param name
     * @param overridden
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses,
                                      Class<?> clazz,
                                      String name,
                                      boolean overridden) {
        Class<?> c = extensionClasses.get(name);
        if (c == null || overridden) {
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            // duplicate implementation is unacceptable
            unacceptableExceptions.add(name);
            String duplicateMsg = "Duplicate extension " + type.getName() + " name " + name + " on " + c.getName()
                + " and " + clazz.getName();
            logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "", duplicateMsg);
            throw new IllegalStateException(duplicateMsg);
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     * 如果clazz上有注解{@link Activate},则将其添加到{@link ExtensionLoader#cachedActivates}里，key为name,value为{@link Activate}
     * <p>
     * {@link ExtensionLoader#loadClass(java.lang.ClassLoader, java.util.Map, java.net.URL, java.lang.Class, java.lang.String, boolean)}
     * 中调用
     * </p>
     */
    @SuppressWarnings("deprecation")
    private void cacheActivateClass(Class<?> clazz, String name) {
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            cachedActivates.put(name, activate);
        } else if (Dubbo2CompactUtils.isEnabled() && Dubbo2ActivateUtils.isActivateLoaded()) {
            // support com.alibaba.dubbo.common.extension.Activate
            /**
             * 下面是判断是否存在{@link com.alibaba.dubbo.common.extension.Activate}注解
             */
            Annotation oldActivate = clazz.getAnnotation(Dubbo2ActivateUtils.getActivateClass());
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }


    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     * 如果clazz上有{@link Adaptive}注解。
     * 将{@link ExtensionLoader#cachedAdaptiveClass}设置为参数clazz
     * <p>
     * {@link ExtensionLoader#loadClass(java.lang.ClassLoader, java.util.Map, java.net.URL, java.lang.Class, java.lang.String, boolean)}
     * 中调用
     * </p>
     *
     * @param clazz
     * @param overridden
     */
    private void cacheAdaptiveClass(Class<?> clazz, boolean overridden) {
        if (cachedAdaptiveClass == null || overridden) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException(
                "More than 1 adaptive class found: " + cachedAdaptiveClass.getName() + ", " + clazz.getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     * 如果clazz是一个包装类，则将其添加到{@link ExtensionLoader#cachedWrapperClasses}中
     * <p>
     * {@link ExtensionLoader#loadClass(java.lang.ClassLoader, java.util.Map, java.net.URL, java.lang.Class, java.lang.String, boolean)}
     * 中调用
     * </p>
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     * 判断传入的clazz是否是个包装类。
     * 判断方式:
     * 1,获取所有的构造器
     * 2,遍历每一个构造器,如果该构造器只有一个参数，且参数类型是{@link ExtensionLoader#type},则为包装类
     * 3,不是包装类
     * <p>
     * {@link ExtensionLoader#loadClass(java.lang.ClassLoader, java.util.Map, java.net.URL, java.lang.Class, java.lang.String, boolean)}
     * 中调用
     * </p>
     *
     * @param clazz
     * @return
     */
    protected boolean isWrapperClass(Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getConstructors();
        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterTypes().length == 1 && constructor.getParameterTypes()[0] == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取clazz的name
     * 1,如果参数clazz上有注解{@link Extension},返回{@link Extension#value()}
     * 2,如果clazz的简单类名(不包括包名),以{@link ExtensionLoader#type}的简单类名结尾，返回之前的部分(全部小写)
     * 3,返回clazz的简单类名(全小写)
     * <p>
     * {@link ExtensionLoader#loadClass(java.lang.ClassLoader, java.util.Map, java.net.URL, java.lang.Class, java.lang.String, boolean)}
     * 中调用
     * </p>
     *
     * @param clazz
     * @return
     */
    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        Extension extension = clazz.getAnnotation(Extension.class);
        if (extension != null) {
            return extension.value();
        }

        String name = clazz.getSimpleName();
        if (name.endsWith(type.getSimpleName())) {
            name = name.substring(0, name.length() - type.getSimpleName().length());
        }
        return name.toLowerCase();
    }

    /**
     * <p>
     * {@link ExtensionLoader#getAdaptiveExtension()}中调用
     * </p>
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            T instance = (T) getAdaptiveExtensionClass().newInstance();
            /**
             * 这个代码直接返回的,无影响
             */
            instance = postProcessBeforeInitialization(instance, null);
            /**
             * 这个方法很重要
             */
            injectExtension(instance);
            instance = postProcessAfterInitialization(instance, null);
            initExtension(instance);
            return instance;
        } catch (Exception e) {
            throw new IllegalStateException(
                "Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * <p>
     * {@link ExtensionLoader#createAdaptiveExtension()}中调用
     * </p>
     *
     * @return
     */
    private Class<?> getAdaptiveExtensionClass() {
        getExtensionClasses();
        /**
         * 这个地方要特别关注一下{@link ExtensionLoader#loadClass(java.lang.ClassLoader, java.util.Map, java.net.URL, java.lang.Class, java.lang.String, boolean)}
         * 方法，会设置这个地方的cachedAdaptiveClass。
         * 所以，如果是{@link ExtensionInjector},这个地方返回的是{@link AdaptiveExtensionInjector}
         */
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        /**
         * 生成一个public class %s$Adaptive implements %s的类
         */
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * <p>
     * {@link ExtensionLoader#getAdaptiveExtensionClass()}中调用
     * </p>
     *
     * @return
     */
    private Class<?> createAdaptiveExtensionClass() {
        // Adaptive Classes' ClassLoader should be the same with Real SPI interface classes' ClassLoader
        ClassLoader classLoader = type.getClassLoader();
        try {
            if (NativeUtils.isNative()) {
                return classLoader.loadClass(type.getName() + "$Adaptive");
            }
        } catch (Throwable ignore) {

        }
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();
        org.apache.dubbo.common.compiler.Compiler compiler = extensionDirector
            .getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class)
            .getAdaptiveExtension();
        return compiler.compile(type, code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

    private static Properties loadProperties(ClassLoader classLoader, String resourceName) {
        Properties properties = new Properties();
        if (classLoader != null) {
            try {
                Enumeration<java.net.URL> resources = classLoader.getResources(resourceName);
                while (resources.hasMoreElements()) {
                    java.net.URL url = resources.nextElement();
                    Properties props = loadFromUrl(url);
                    for (Map.Entry<Object, Object> entry : props.entrySet()) {
                        String key = entry.getKey().toString();
                        if (properties.containsKey(key)) {
                            continue;
                        }
                        properties.put(key, entry.getValue().toString());
                    }
                }
            } catch (IOException ex) {
                logger.error(CONFIG_FAILED_LOAD_ENV_VARIABLE, "", "", "load properties failed.", ex);
            }
        }

        return properties;
    }

    private static Properties loadFromUrl(java.net.URL url) {
        Properties properties = new Properties();
        InputStream is = null;
        try {
            is = url.openStream();
            properties.load(is);
        } catch (IOException e) {
            // ignore
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        return properties;
    }
}
