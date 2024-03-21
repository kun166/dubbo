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
package org.apache.dubbo.config.spring.schema;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.MethodUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.*;
import org.apache.dubbo.config.nested.AggregationConfig;
import org.apache.dubbo.config.nested.HistogramConfig;
import org.apache.dubbo.config.nested.PrometheusConfig;
import org.apache.dubbo.config.spring.Constants;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.ServiceBean;
import org.apache.dubbo.config.spring.reference.ReferenceAttributes;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.apache.dubbo.common.constants.CommonConstants.HIDE_KEY_PREFIX;
import static org.apache.dubbo.config.spring.util.SpringCompatUtils.getPropertyValue;

/**
 * AbstractBeanDefinitionParser
 *
 * @export
 */
public class DubboBeanDefinitionParser implements BeanDefinitionParser {

    private static final Logger logger = LoggerFactory.getLogger(DubboBeanDefinitionParser.class);
    private static final Pattern GROUP_AND_VERSION = Pattern.compile("^[\\-.0-9_a-zA-Z]+(\\:[\\-.0-9_a-zA-Z]+)?$");
    private static final String ONRETURN = "onreturn";
    private static final String ONTHROW = "onthrow";
    private static final String ONINVOKE = "oninvoke";
    private static final String EXECUTOR = "executor";
    private static final String METHOD = "Method";
    private static final String BEAN_NAME = "BEAN_NAME";
    private static boolean resolvePlaceholdersEnabled = true;
    private final Class<?> beanClass;
    private static Map<String, Map<String, Class>> beanPropsCache = new HashMap<>();

    /**
     * {@link DubboNamespaceHandler#init()}
     * 中调用
     *
     * @param beanClass 可以参考{@link DubboNamespaceHandler}
     */
    public DubboBeanDefinitionParser(Class<?> beanClass) {
        this.beanClass = beanClass;
    }

    /**
     * {@link DubboBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext)}
     * 中调用
     *
     * @param element       xml自定义标签的node
     * @param parserContext 自定义标签，外部传入的对象{@link ParserContext}
     * @param beanClass     自定义标签，需要初始化的那个class
     * @param registered    传的是true,需要注册
     * @return
     */
    @SuppressWarnings("unchecked")
    private static RootBeanDefinition parse(Element element, ParserContext parserContext, Class<?> beanClass, boolean registered) {
        /**
         * 定义BeanDefinition,并设置beanClass
         */
        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setBeanClass(beanClass);
        beanDefinition.setLazyInit(false);
        if (ServiceBean.class.equals(beanClass)) {
            /**
             * 如果beanClass为ServiceBean,
             * 则设置autowireMode为构造器模式
             */
            beanDefinition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
        }
        // config id
        // 获取bean的id
        String configId = resolveAttribute(element, "id", parserContext);
        if (StringUtils.isNotEmpty(configId)) {
            /**
             * 有时间，在看看如果ref的时候怎么添加的吧
             */
            beanDefinition.getPropertyValues().addPropertyValue("id", configId);
        }

        String configName = "";
        // 如果没有配置id，则取name
        // get configName from name
        if (StringUtils.isEmpty(configId)) {
            configName = resolveAttribute(element, "name", parserContext);
        }

        String beanName = configId;
        if (StringUtils.isEmpty(beanName)) {
            // generate bean name
            String prefix = beanClass.getName();
            int counter = 0;
            beanName = prefix + (StringUtils.isEmpty(configName) ? "#" : ("#" + configName + "#")) + counter;
            while (parserContext.getRegistry().containsBeanDefinition(beanName)) {
                beanName = prefix + (StringUtils.isEmpty(configName) ? "#" : ("#" + configName + "#")) + (counter++);
            }
        }
        beanDefinition.setAttribute(BEAN_NAME, beanName);

        if (ProtocolConfig.class.equals(beanClass)) {
            //            for (String name : parserContext.getRegistry().getBeanDefinitionNames()) {
            //                BeanDefinition definition = parserContext.getRegistry().getBeanDefinition(name);
            //                PropertyValue property = definition.getPropertyValues().getPropertyValue("protocol");
            //                if (property != null) {
            //                    Object value = property.getValue();
            //                    if (value instanceof ProtocolConfig && beanName.equals(((ProtocolConfig)
            // value).getName())) {
            //                        definition.getPropertyValues().addPropertyValue("protocol", new
            // RuntimeBeanReference(beanName));
            //                    }
            //                }
            //            }
        } else if (ServiceBean.class.equals(beanClass)) {
            /**
             * class
             * 这个配置参数,没查到资源，先不看吧
             */
            String className = resolveAttribute(element, "class", parserContext);
            if (StringUtils.isNotEmpty(className)) {
                RootBeanDefinition classDefinition = new RootBeanDefinition();
                classDefinition.setBeanClass(ReflectUtils.forName(className));
                classDefinition.setLazyInit(false);
                parseProperties(element.getChildNodes(), classDefinition, parserContext);
                beanDefinition
                    .getPropertyValues()
                    .addPropertyValue("ref", new BeanDefinitionHolder(classDefinition, beanName + "Impl"));
            }
        }

        /**
         * 这个beanClass是{@link DubboNamespaceHandler}传入的，固定就这么些种
         * 保存的是属性name和属性归属的类型
         */
        Map<String, Class> beanPropTypeMap = beanPropsCache.get(beanClass.getName());
        if (beanPropTypeMap == null) {
            beanPropTypeMap = new HashMap<>();
            beanPropsCache.put(beanClass.getName(), beanPropTypeMap);
            if (ReferenceBean.class.equals(beanClass)) {
                // extract bean props from ReferenceConfig
                getPropertyMap(ReferenceConfig.class, beanPropTypeMap);
            } else {
                getPropertyMap(beanClass, beanPropTypeMap);
            }
        }

        ManagedMap parameters = null;
        Set<String> processedProps = new HashSet<>();
        for (Map.Entry<String, Class> entry : beanPropTypeMap.entrySet()) {
            // 获取属性name
            String beanProperty = entry.getKey();
            // 获取属性归属的类型
            Class type = entry.getValue();
            // 驼峰转连字符？
            String property = StringUtils.camelToSplitName(beanProperty, "-");
            processedProps.add(property);
            if ("parameters".equals(property)) {
                // 方法名称是setParameters
                parameters = parseParameters(element.getChildNodes(), beanDefinition, parserContext);
            } else if ("methods".equals(property)) {
                // 方法名称是setMethods
                parseMethods(beanName, element.getChildNodes(), beanDefinition, parserContext);
            } else if ("arguments".equals(property)) {
                // 方法名称是setArguments
                parseArguments(beanName, element.getChildNodes(), beanDefinition, parserContext);
            } else {
                String value = resolveAttribute(element, property, parserContext);
                if (StringUtils.isNotBlank(value)) {
                    value = value.trim();
                    if ("registry".equals(property) && RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(value)) {
                        RegistryConfig registryConfig = new RegistryConfig();
                        registryConfig.setAddress(RegistryConfig.NO_AVAILABLE);
                        // see AbstractInterfaceConfig#registries, It will be invoker setRegistries method when
                        // BeanDefinition is registered,
                        beanDefinition.getPropertyValues().addPropertyValue("registries", registryConfig);
                        // If registry is N/A, don't init it until the reference is invoked
                        beanDefinition.setLazyInit(true);
                    } else if ("provider".equals(property)
                        || "registry".equals(property)
                        || ("protocol".equals(property) && AbstractServiceConfig.class.isAssignableFrom(beanClass))) {
                        /**
                         * For 'provider' 'protocol' 'registry', keep literal value (should be id/name) and set the value to 'registryIds' 'providerIds' protocolIds'
                         * The following process should make sure each id refers to the corresponding instance, here's how to find the instance for different use cases:
                         * 1. Spring, check existing bean by id, see{@link ServiceBean#afterPropertiesSet()}; then try to use id to find configs defined in remote Config Center
                         * 2. API, directly use id to find configs defined in remote Config Center; if all config instances are defined locally, please use {@link org.apache.dubbo.config.ServiceConfig#setRegistries(List)}
                         */
                        /**
                         * 这个地方要注意,如果满足下面任意一个条件:
                         * 1,beanProperty为provider
                         * 2,beanProperty为registry
                         * 3,beanProperty为protocol,且解析的类为{@link AbstractServiceConfig}的子类
                         * 则调用的方法为 beanProperty+"Ids"
                         * 如{@link AbstractServiceConfig#setProtocolIds(java.lang.String)}
                         */
                        beanDefinition.getPropertyValues().addPropertyValue(beanProperty + "Ids", value);
                    } else {
                        Object reference;
                        if (isPrimitive(type)) {
                            value = getCompatibleDefaultValue(property, value);
                            reference = value;
                        } else if (ONRETURN.equals(property) || ONTHROW.equals(property) || ONINVOKE.equals(property)) {
                            int index = value.lastIndexOf(".");
                            String ref = value.substring(0, index);
                            String method = value.substring(index + 1);
                            reference = new RuntimeBeanReference(ref);
                            beanDefinition.getPropertyValues().addPropertyValue(property + METHOD, method);
                        } else if (EXECUTOR.equals(property)) {
                            reference = new RuntimeBeanReference(value);
                        } else {
                            /**
                             * setRef方法
                             * {@link ServiceConfigBase#setRef(java.lang.Object)}
                             */
                            if ("ref".equals(property)
                                /**
                                 * 这个地方只是对ref的校验
                                 */
                                && parserContext.getRegistry().containsBeanDefinition(value)) {
                                BeanDefinition refBean =
                                    parserContext.getRegistry().getBeanDefinition(value);
                                if (!refBean.isSingleton()) {
                                    throw new IllegalStateException(
                                        "The exported service ref " + value + " must be singleton! Please set the "
                                            + value + " bean scope to singleton, eg: <bean id=\"" + value
                                            + "\" scope=\"singleton\" ...>");
                                }
                            }
                            /**
                             * 和spring 标签一样
                             * 像{@link ServiceBean#setRegistry(RegistryConfig)},这个地方也是这么处理的
                             */
                            reference = new RuntimeBeanReference(value);
                        }
                        if (reference != null) {
                            beanDefinition.getPropertyValues().addPropertyValue(beanProperty, reference);
                        }
                    }
                }
            }
        }

        NamedNodeMap attributes = element.getAttributes();
        int len = attributes.getLength();
        for (int i = 0; i < len; i++) {
            Node node = attributes.item(i);
            String name = node.getLocalName();
            if (!processedProps.contains(name)) {
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String value = node.getNodeValue();
                parameters.put(name, new TypedStringValue(value, String.class));
            }
        }
        if (parameters != null) {
            beanDefinition.getPropertyValues().addPropertyValue("parameters", parameters);
        }

        // post-process after parse attributes
        if (ProviderConfig.class.equals(beanClass)) {
            parseNested(
                element, parserContext, ServiceBean.class, true, "service", "provider", beanName, beanDefinition);
        } else if (ConsumerConfig.class.equals(beanClass)) {
            parseNested(
                element,
                parserContext,
                ReferenceBean.class,
                true,
                "reference",
                "consumer",
                beanName,
                beanDefinition);
        } else if (ReferenceBean.class.equals(beanClass)) {
            configReferenceBean(element, parserContext, beanDefinition, null);
        } else if (MetricsConfig.class.equals(beanClass)) {
            parseMetrics(element, parserContext, beanDefinition);
        }

        // register bean definition
        if (parserContext.getRegistry().containsBeanDefinition(beanName)) {
            throw new IllegalStateException("Duplicate spring bean name: " + beanName);
        }

        if (registered) {
            parserContext.getRegistry().registerBeanDefinition(beanName, beanDefinition);
        }
        return beanDefinition;
    }

    private static void parseMetrics(Element element, ParserContext parserContext, RootBeanDefinition beanDefinition) {
        NodeList childNodes = element.getChildNodes();
        PrometheusConfig prometheus = null;
        for (int i = 0; i < childNodes.getLength(); i++) {
            if (!(childNodes.item(i) instanceof Element)) {
                continue;
            }

            Element child = (Element) childNodes.item(i);
            if ("aggregation".equals(child.getNodeName()) || "aggregation".equals(child.getLocalName())) {
                AggregationConfig aggregation = new AggregationConfig();
                assignProperties(aggregation, child, parserContext);
                beanDefinition.getPropertyValues().addPropertyValue("aggregation", aggregation);
            } else if ("histogram".equals(child.getNodeName()) || "histogram".equals(child.getLocalName())) {
                HistogramConfig histogram = new HistogramConfig();
                assignProperties(histogram, child, parserContext);
                beanDefinition.getPropertyValues().addPropertyValue("histogram", histogram);
            } else if ("prometheus-exporter".equals(child.getNodeName())
                || "prometheus-exporter".equals(child.getLocalName())) {
                if (prometheus == null) {
                    prometheus = new PrometheusConfig();
                }

                PrometheusConfig.Exporter exporter = new PrometheusConfig.Exporter();
                assignProperties(exporter, child, parserContext);
                prometheus.setExporter(exporter);
            } else if ("prometheus-pushgateway".equals(child.getNodeName())
                || "prometheus-pushgateway".equals(child.getLocalName())) {
                if (prometheus == null) {
                    prometheus = new PrometheusConfig();
                }

                PrometheusConfig.Pushgateway pushgateway = new PrometheusConfig.Pushgateway();
                assignProperties(pushgateway, child, parserContext);
                prometheus.setPushgateway(pushgateway);
            }
        }

        if (prometheus != null) {
            beanDefinition.getPropertyValues().addPropertyValue("prometheus", prometheus);
        }
    }

    private static void assignProperties(Object obj, Element ele, ParserContext parserContext) {
        Method[] methods = obj.getClass().getMethods();
        for (Method method : methods) {
            if (MethodUtils.isSetter(method)) {
                String beanProperty = method.getName().substring(3, 4).toLowerCase()
                    + method.getName().substring(4);
                String property = StringUtils.camelToSplitName(beanProperty, "-");
                String value = resolveAttribute(ele, property, parserContext);
                if (StringUtils.isNotEmpty(value)) {
                    try {
                        Object v = ClassUtils.convertPrimitive(method.getParameterTypes()[0], value);
                        method.invoke(obj, v);
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
    }

    private static void configReferenceBean(
        Element element,
        ParserContext parserContext,
        RootBeanDefinition beanDefinition,
        BeanDefinition consumerDefinition) {
        // process interface class
        String interfaceName = resolveAttribute(element, ReferenceAttributes.INTERFACE, parserContext);
        String generic = resolveAttribute(element, ReferenceAttributes.GENERIC, parserContext);
        if (StringUtils.isBlank(generic) && consumerDefinition != null) {
            // get generic from consumerConfig
            generic = getPropertyValue(consumerDefinition.getPropertyValues(), ReferenceAttributes.GENERIC);
        }
        if (generic != null) {
            generic = resolvePlaceholders(generic, parserContext);
            beanDefinition.getPropertyValues().add(ReferenceAttributes.GENERIC, generic);
        }
        beanDefinition.setAttribute(ReferenceAttributes.INTERFACE_NAME, interfaceName);

        Class interfaceClass = ReferenceConfig.determineInterfaceClass(generic, interfaceName);
        beanDefinition.setAttribute(ReferenceAttributes.INTERFACE_CLASS, interfaceClass);

        // TODO Only register one reference bean for same (group, interface, version)

        // create decorated definition for reference bean, Avoid being instantiated when getting the beanType of
        // ReferenceBean
        // see org.springframework.beans.factory.support.AbstractBeanFactory#getTypeForFactoryBean()
        GenericBeanDefinition targetDefinition = new GenericBeanDefinition();
        targetDefinition.setBeanClass(interfaceClass);
        String beanName = (String) beanDefinition.getAttribute(BEAN_NAME);
        beanDefinition.setDecoratedDefinition(new BeanDefinitionHolder(targetDefinition, beanName + "_decorated"));

        // signal object type since Spring 5.2
        beanDefinition.setAttribute(Constants.OBJECT_TYPE_ATTRIBUTE, interfaceClass);

        // mark property value as optional
        List<PropertyValue> propertyValues = beanDefinition.getPropertyValues().getPropertyValueList();
        for (PropertyValue propertyValue : propertyValues) {
            propertyValue.setOptional(true);
        }
    }

    /**
     * 获取beanClass的所有方法，找出public的set**方法,然后把**和它的class类型存到beanPropsMap中。
     * 判断是不是set**方法：
     * 1,方法名称长度大于3,方法类型是public的,方法参数只有一个,方法以set开头
     * 2,有对应的get方法,且get方法是public的,且get方法返回类型和set方法参数类型一致
     * <p>
     * {@link DubboBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, java.lang.Class, boolean)}
     * 中调用
     * </p>
     *
     * @param beanClass
     * @param beanPropsMap
     */
    private static void getPropertyMap(Class<?> beanClass, Map<String, Class> beanPropsMap) {
        for (Method setter : beanClass.getMethods()) {
            // 遍历beanClass的所有方法
            // 方法的名字
            String name = setter.getName();
            if (name.length() > 3
                && name.startsWith("set")
                && Modifier.isPublic(setter.getModifiers())
                && setter.getParameterTypes().length == 1) {
                // 方法名称大于3,且以set开头,且方法是public的,且参数长度为1

                // 获取方法参数类型
                Class<?> type = setter.getParameterTypes()[0];
                // 方法设置的属性名字
                String beanProperty = name.substring(3, 4).toLowerCase() + name.substring(4);
                // check the setter/getter whether match
                Method getter = null;
                try {
                    // 获取get方法
                    getter = beanClass.getMethod("get" + name.substring(3), new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    try {
                        // 如果get方法没有，则获取is方法
                        getter = beanClass.getMethod("is" + name.substring(3), new Class<?>[0]);
                    } catch (NoSuchMethodException e2) {
                        // ignore, there is no need any log here since some class implement the interface:
                        // EnvironmentAware,
                        // ApplicationAware, etc. They only have setter method, otherwise will cause the error log
                        // during application start up.
                    }
                }
                if (getter == null
                    || !Modifier.isPublic(getter.getModifiers())
                    || !type.equals(getter.getReturnType())) {
                    // 如果不存在get方法或者get方法非public,或者get方法的返回类型和set方法的参数类型不一致
                    // 则不认为是一个set方法
                    continue;
                }
                // 将属性名字和属性类型保存起来
                beanPropsMap.put(beanProperty, type);
            }
        }
    }

    private static String getCompatibleDefaultValue(String property, String value) {
        if ("async".equals(property) && "false".equals(value)
            || "timeout".equals(property) && "0".equals(value)
            || "delay".equals(property) && "0".equals(value)
            || "version".equals(property) && "0.0.0".equals(value)
            || "stat".equals(property) && "-1".equals(value)
            || "reliable".equals(property) && "false".equals(value)) {
            // backward compatibility for the default value in old version's xsd
            value = null;
        }
        return value;
    }

    private static boolean isPrimitive(Class<?> cls) {
        return cls.isPrimitive()
            || cls == Boolean.class
            || cls == Byte.class
            || cls == Character.class
            || cls == Short.class
            || cls == Integer.class
            || cls == Long.class
            || cls == Float.class
            || cls == Double.class
            || cls == String.class
            || cls == Date.class
            || cls == Class.class;
    }

    private static void parseNested(
        Element element,
        ParserContext parserContext,
        Class<?> beanClass,
        boolean registered,
        String tag,
        String property,
        String ref,
        BeanDefinition beanDefinition) {
        NodeList nodeList = element.getChildNodes();
        if (nodeList == null) {
            return;
        }
        boolean first = true;
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            if (!(node instanceof Element)) {
                continue;
            }
            if (tag.equals(node.getNodeName()) || tag.equals(node.getLocalName())) {
                if (first) {
                    first = false;
                    String isDefault = resolveAttribute(element, "default", parserContext);
                    if (StringUtils.isEmpty(isDefault)) {
                        beanDefinition.getPropertyValues().addPropertyValue("default", "false");
                    }
                }
                RootBeanDefinition subDefinition = parse((Element) node, parserContext, beanClass, registered);
                if (subDefinition != null) {
                    if (StringUtils.isNotEmpty(ref)) {
                        subDefinition.getPropertyValues().addPropertyValue(property, new RuntimeBeanReference(ref));
                    }
                    if (ReferenceBean.class.equals(beanClass)) {
                        configReferenceBean((Element) node, parserContext, subDefinition, beanDefinition);
                    }
                }
            }
        }
    }


    /**
     * 处理自定义标签下的
     * <property name="" ref="" />
     * 该property标签和spring相似
     * {@link DubboBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, java.lang.Class, boolean)}
     * 中调用
     *
     * @param nodeList
     * @param beanDefinition
     * @param parserContext
     */
    private static void parseProperties(
        NodeList nodeList, RootBeanDefinition beanDefinition, ParserContext parserContext) {
        if (nodeList == null) {
            return;
        }
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (!(nodeList.item(i) instanceof Element)) {
                continue;
            }
            Element element = (Element) nodeList.item(i);
            if ("property".equals(element.getNodeName()) || "property".equals(element.getLocalName())) {
                /**
                 * property属性
                 */
                // name
                String name = resolveAttribute(element, "name", parserContext);
                if (StringUtils.isNotEmpty(name)) {
                    // value
                    String value = resolveAttribute(element, "value", parserContext);
                    // ref
                    String ref = resolveAttribute(element, "ref", parserContext);
                    if (StringUtils.isNotEmpty(value)) {
                        beanDefinition.getPropertyValues().addPropertyValue(name, value);
                    } else if (StringUtils.isNotEmpty(ref)) {
                        /**
                         * ref是{@link RuntimeBeanReference}
                         */
                        beanDefinition.getPropertyValues().addPropertyValue(name, new RuntimeBeanReference(ref));
                    } else {
                        throw new UnsupportedOperationException("Unsupported <property name=\"" + name
                            + "\"> sub tag, Only supported <property name=\"" + name
                            + "\" ref=\"...\" /> or <property name=\"" + name + "\" value=\"...\" />");
                    }
                }
            }
        }
    }

    /**
     * {@link DubboBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, java.lang.Class, boolean)}
     * 中调用
     *
     * @param nodeList
     * @param beanDefinition
     * @param parserContext
     * @return
     */
    @SuppressWarnings("unchecked")
    private static ManagedMap parseParameters(
        NodeList nodeList, RootBeanDefinition beanDefinition, ParserContext parserContext) {
        if (nodeList == null) {
            return null;
        }
        ManagedMap parameters = null;
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (!(nodeList.item(i) instanceof Element)) {
                // 如果节点类型不是Element
                continue;
            }
            Element element = (Element) nodeList.item(i);
            if ("parameter".equals(element.getNodeName()) || "parameter".equals(element.getLocalName())) {
                /**
                 * <dubbo:parameter key="" value="" />标签
                 */
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String key = resolveAttribute(element, "key", parserContext);
                String value = resolveAttribute(element, "value", parserContext);
                boolean hide = "true".equals(resolveAttribute(element, "hide", parserContext));
                if (hide) {
                    key = HIDE_KEY_PREFIX + key;
                }
                parameters.put(key, new TypedStringValue(value, String.class));
            }
        }
        return parameters;
    }

    /**
     * {@link DubboBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, java.lang.Class, boolean)}
     * 中调用
     *
     * @param id
     * @param nodeList
     * @param beanDefinition
     * @param parserContext
     */
    @SuppressWarnings("unchecked")
    private static void parseMethods(
        String id, NodeList nodeList, RootBeanDefinition beanDefinition, ParserContext parserContext) {
        if (nodeList == null) {
            return;
        }
        ManagedList methods = null;
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (!(nodeList.item(i) instanceof Element)) {
                continue;
            }
            Element element = (Element) nodeList.item(i);
            if ("method".equals(element.getNodeName()) || "method".equals(element.getLocalName())) {
                String methodName = resolveAttribute(element, "name", parserContext);
                if (StringUtils.isEmpty(methodName)) {
                    throw new IllegalStateException("<dubbo:method> name attribute == null");
                }
                if (methods == null) {
                    methods = new ManagedList();
                }
                RootBeanDefinition methodBeanDefinition = parse(element, parserContext, MethodConfig.class, false);
                String beanName = id + "." + methodName;

                // If the PropertyValue named "id" can't be found,
                // bean name will be taken as the "id" PropertyValue for MethodConfig
                if (!hasPropertyValue(methodBeanDefinition, "id")) {
                    addPropertyValue(methodBeanDefinition, "id", beanName);
                }

                BeanDefinitionHolder methodBeanDefinitionHolder =
                    new BeanDefinitionHolder(methodBeanDefinition, beanName);
                methods.add(methodBeanDefinitionHolder);
            }
        }
        if (methods != null) {
            beanDefinition.getPropertyValues().addPropertyValue("methods", methods);
        }
    }

    private static boolean hasPropertyValue(AbstractBeanDefinition beanDefinition, String propertyName) {
        return beanDefinition.getPropertyValues().contains(propertyName);
    }

    private static void addPropertyValue(
        AbstractBeanDefinition beanDefinition, String propertyName, String propertyValue) {
        if (StringUtils.isBlank(propertyName) || StringUtils.isBlank(propertyValue)) {
            return;
        }
        beanDefinition.getPropertyValues().addPropertyValue(propertyName, propertyValue);
    }

    /**
     * {@link DubboBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, java.lang.Class, boolean)}
     * 中调用
     *
     * @param id
     * @param nodeList
     * @param beanDefinition
     * @param parserContext
     */
    @SuppressWarnings("unchecked")
    private static void parseArguments(
        String id, NodeList nodeList, RootBeanDefinition beanDefinition, ParserContext parserContext) {
        if (nodeList == null) {
            return;
        }
        ManagedList arguments = null;
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (!(nodeList.item(i) instanceof Element)) {
                continue;
            }
            Element element = (Element) nodeList.item(i);
            if ("argument".equals(element.getNodeName()) || "argument".equals(element.getLocalName())) {
                String argumentIndex = resolveAttribute(element, "index", parserContext);
                if (arguments == null) {
                    arguments = new ManagedList();
                }
                BeanDefinition argumentBeanDefinition = parse(element, parserContext, ArgumentConfig.class, false);
                String name = id + "." + argumentIndex;
                BeanDefinitionHolder argumentBeanDefinitionHolder =
                    new BeanDefinitionHolder(argumentBeanDefinition, name);
                arguments.add(argumentBeanDefinitionHolder);
            }
        }
        if (arguments != null) {
            beanDefinition.getPropertyValues().addPropertyValue("arguments", arguments);
        }
    }

    /**
     * 该类的入口方法吧
     *
     * @param element       自定义的xml标签
     * @param parserContext
     * @return
     */
    @Override
    public BeanDefinition parse(Element element, ParserContext parserContext) {
        return parse(element, parserContext, beanClass, true);
    }

    /**
     * 从自定义标签element上，获取属性attributeName的值
     * <p>
     * {@link DubboBeanDefinitionParser#parse(org.w3c.dom.Element, org.springframework.beans.factory.xml.ParserContext, java.lang.Class, boolean)}
     * 中调用
     *
     * @param element
     * @param attributeName
     * @param parserContext
     * @return
     */
    private static String resolveAttribute(Element element, String attributeName, ParserContext parserContext) {
        String attributeValue = element.getAttribute(attributeName);
        // Early resolve place holder may be wrong ( Before
        // PropertySourcesPlaceholderConfigurer/PropertyPlaceholderConfigurer )
        // https://github.com/apache/dubbo/pull/6079
        // https://github.com/apache/dubbo/issues/6035
        //        Environment environment = parserContext.getReaderContext().getEnvironment();
        //        return environment.resolvePlaceholders(attributeValue);
        return attributeValue;
    }

    private static String resolvePlaceholders(String str, ParserContext parserContext) {
        if (resolvePlaceholdersEnabled) {
            try {
                return parserContext.getReaderContext().getEnvironment().resolveRequiredPlaceholders(str);
            } catch (NoSuchMethodError e) {
                resolvePlaceholdersEnabled = false;
            }
        }
        return str;
    }
}
