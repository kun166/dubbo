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

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Code generator for Adaptive class
 */
public class AdaptiveClassCodeGenerator {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveClassCodeGenerator.class);

    private static final String CLASS_NAME_INVOCATION = "org.apache.dubbo.rpc.Invocation";

    private static final String CODE_PACKAGE = "package %s;\n";

    private static final String CODE_IMPORTS = "import %s;\n";

    private static final String CODE_CLASS_DECLARATION = "public class %s$Adaptive implements %s {\n";

    private static final String CODE_METHOD_DECLARATION = "public %s %s(%s) %s {\n%s}\n";

    private static final String CODE_METHOD_ARGUMENT = "%s arg%d";

    private static final String CODE_METHOD_THROWS = "throws %s";

    private static final String CODE_UNSUPPORTED =
        "throw new UnsupportedOperationException(\"The method %s of interface %s is not adaptive method!\");\n";

    private static final String CODE_URL_NULL_CHECK =
        "if (arg%d == null) throw new IllegalArgumentException(\"url == null\");\n%s url = arg%d;\n";

    private static final String CODE_EXT_NAME_ASSIGNMENT = "String extName = %s;\n";

    private static final String CODE_EXT_NAME_NULL_CHECK = "if(extName == null) "
        + "throw new IllegalStateException(\"Failed to get extension (%s) name from url (\" + url.toString() + \") use keys(%s)\");\n";

    private static final String CODE_INVOCATION_ARGUMENT_NULL_CHECK =
        "if (arg%d == null) throw new IllegalArgumentException(\"invocation == null\"); "
            + "String methodName = arg%d.getMethodName();\n";

    private static final String CODE_SCOPE_MODEL_ASSIGNMENT =
        "ScopeModel scopeModel = ScopeModelUtil.getOrDefault(url.getScopeModel(), %s.class);\n";
    private static final String CODE_EXTENSION_ASSIGNMENT =
        "%s extension = (%<s)scopeModel.getExtensionLoader(%s.class).getExtension(extName);\n";

    private static final String CODE_EXTENSION_METHOD_INVOKE_ARGUMENT = "arg%d";

    private final Class<?> type;

    private final String defaultExtName;

    /**
     * <p>
     * {@link ExtensionLoader#createAdaptiveExtensionClass()}中调用
     * </p>
     *
     * @param type
     * @param defaultExtName
     */
    public AdaptiveClassCodeGenerator(Class<?> type, String defaultExtName) {
        this.type = type;
        this.defaultExtName = defaultExtName;
    }

    /**
     * test if given type has at least one method annotated with <code>Adaptive</code>
     */
    private boolean hasAdaptiveMethod() {
        return Arrays.stream(type.getMethods()).anyMatch(m -> m.isAnnotationPresent(Adaptive.class));
    }

    /**
     * generate and return class code
     * <p>
     * {@link ExtensionLoader#createAdaptiveExtensionClass()}中调用
     * </p>
     */
    public String generate() {
        return this.generate(false);
    }

    /**
     * generate and return class code
     * <p>
     * 参考{@link org.apache.dubbo.rpc.Protocol$Adaptive}
     *
     * <p>
     * {@link AdaptiveClassCodeGenerator#generate()}中调用
     * </p>
     *
     * @param sort - whether sort methods
     */
    public String generate(boolean sort) {
        // no need to generate adaptive class since there's no adaptive method found.
        if (!hasAdaptiveMethod()) {
            /**
             * 如果没有任何一个标有{@link Adaptive}注解的方法,则抛异常
             * 最少有一个就行
             */
            throw new IllegalStateException("No adaptive method exist on extension " + type.getName()
                + ", refuse to create the adaptive class!");
        }

        StringBuilder code = new StringBuilder();
        // 注意，返回的包名字符串以";"结尾
        code.append(generatePackageInfo());
        /**
         * import
         * {@link ScopeModel}
         * {@link ScopeModelUtil}
         * 两个引用类
         */
        code.append(generateImports());
        /**
         * 翻译type的一个代理类，public class %s$Adaptive implements %s
         */
        code.append(generateClassDeclaration());

        Method[] methods = type.getMethods();
        if (sort) {
            Arrays.sort(methods, Comparator.comparing(Method::toString));
        }
        for (Method method : methods) {
            code.append(generateMethod(method));
        }
        code.append('}');

        if (logger.isDebugEnabled()) {
            logger.debug(code.toString());
        }
        return code.toString();
    }

    /**
     * generate package info
     * 返回type的包名
     * <p>
     * {@link AdaptiveClassCodeGenerator#generate(boolean)}中调用
     * </p>
     */
    private String generatePackageInfo() {
        return String.format(CODE_PACKAGE, type.getPackage().getName());
    }

    /**
     * generate imports
     * import
     * {@link ScopeModel}
     * {@link ScopeModelUtil}
     * 两个引用类
     * <p>
     * {@link AdaptiveClassCodeGenerator#generate(boolean)}中调用
     * </p>
     */
    private String generateImports() {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format(CODE_IMPORTS, ScopeModel.class.getName()));
        builder.append(String.format(CODE_IMPORTS, ScopeModelUtil.class.getName()));
        return builder.toString();
    }

    /**
     * generate class declaration
     */
    private String generateClassDeclaration() {
        return String.format(CODE_CLASS_DECLARATION, type.getSimpleName(), type.getCanonicalName());
    }

    /**
     * generate method not annotated with Adaptive with throwing unsupported exception
     */
    private String generateUnsupported(Method method) {
        return String.format(CODE_UNSUPPORTED, method, type.getName());
    }

    /**
     * get index of parameter with type URL
     */
    private int getUrlTypeIndex(Method method) {
        int urlTypeIndex = -1;
        Class<?>[] pts = method.getParameterTypes();
        for (int i = 0; i < pts.length; ++i) {
            if (pts[i].equals(URL.class)) {
                urlTypeIndex = i;
                break;
            }
        }
        return urlTypeIndex;
    }

    /**
     * generate method declaration
     * {@link AdaptiveClassCodeGenerator#generate(boolean)}中调用
     */
    private String generateMethod(Method method) {
        String methodReturnType = method.getReturnType().getCanonicalName();
        String methodName = method.getName();
        String methodContent = generateMethodContent(method);
        String methodArgs = generateMethodArguments(method);
        String methodThrows = generateMethodThrows(method);
        return String.format(
            CODE_METHOD_DECLARATION, methodReturnType, methodName, methodArgs, methodThrows, methodContent);
    }

    /**
     * generate method arguments
     */
    private String generateMethodArguments(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        return IntStream.range(0, pts.length)
            .mapToObj(i -> String.format(CODE_METHOD_ARGUMENT, pts[i].getCanonicalName(), i))
            .collect(Collectors.joining(", "));
    }

    /**
     * generate method throws
     */
    private String generateMethodThrows(Method method) {
        Class<?>[] ets = method.getExceptionTypes();
        if (ets.length > 0) {
            String list = Arrays.stream(ets).map(Class::getCanonicalName).collect(Collectors.joining(", "));
            return String.format(CODE_METHOD_THROWS, list);
        } else {
            return "";
        }
    }

    /**
     * generate method URL argument null check
     * {@link AdaptiveClassCodeGenerator#generateMethodContent(Method)}中调用
     */
    private String generateUrlNullCheck(int index) {
        return String.format(CODE_URL_NULL_CHECK, index, URL.class.getName(), index);
    }

    /**
     * generate method content
     * {@link AdaptiveClassCodeGenerator#generateMethod(Method)}中调用
     */
    private String generateMethodContent(Method method) {
        Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
        StringBuilder code = new StringBuilder(512);
        if (adaptiveAnnotation == null) {
            /**
             * 如果方法上未标注{@link Adaptive}注解,则生成一个不支持的方法，即该方法不需要实现，不能被调用
             */
            return generateUnsupported(method);
        } else {
            /**
             * 寻找类型为{@link URL}的参数，在参数中的第几个位置
             * org.apache.dubbo.common.URL
             */
            int urlTypeIndex = getUrlTypeIndex(method);

            // found parameter in URL type
            /**
             * 生成参数判空代码
             */
            if (urlTypeIndex != -1) {
                // Null Point check
                /**
                 * 找到了某个位置的参数类型为{@link URL}.
                 * 参考{@link org.apache.dubbo.rpc.Protocol$Adaptive}
                 */
                code.append(generateUrlNullCheck(urlTypeIndex));
            } else {
                // did not find parameter in URL type
                /**
                 * 未找到了某个位置的参数类型为{@link URL}
                 */
                code.append(generateUrlAssignmentIndirectly(method));
            }

            /**
             * 方法上标注的{@link Adaptive}的{@link Adaptive#value()}
             */
            String[] value = getMethodAdaptiveValue(adaptiveAnnotation);

            /**
             * 方法的传参上,是否有{@link org.apache.dubbo.rpc.Invocation}
             */
            boolean hasInvocation = hasInvocationArgument(method);

            code.append(generateInvocationArgumentNullCheck(method));

            code.append(generateExtNameAssignment(value, hasInvocation));
            // check extName == null?
            code.append(generateExtNameNullCheck(value));

            code.append(generateScopeModelAssignment());
            code.append(generateExtensionAssignment());

            // return statement
            code.append(generateReturnAndInvocation(method));
        }

        return code.toString();
    }

    /**
     * generate code for variable extName null check
     */
    private String generateExtNameNullCheck(String[] value) {
        return String.format(CODE_EXT_NAME_NULL_CHECK, type.getName(), Arrays.toString(value));
    }

    /**
     * generate extName assignment code
     */
    private String generateExtNameAssignment(String[] value, boolean hasInvocation) {
        // TODO: refactor it
        String getNameCode = null;
        for (int i = value.length - 1; i >= 0; --i) {
            if (i == value.length - 1) {
                if (null != defaultExtName) {
                    if (!CommonConstants.PROTOCOL_KEY.equals(value[i])) {
                        if (hasInvocation) {
                            getNameCode = String.format(
                                "url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                        } else {
                            getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                        }
                    } else {
                        getNameCode = String.format(
                            "( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                    }
                } else {
                    if (!CommonConstants.PROTOCOL_KEY.equals(value[i])) {
                        if (hasInvocation) {
                            getNameCode = String.format(
                                "url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                        } else {
                            getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                        }
                    } else {
                        getNameCode = "url.getProtocol()";
                    }
                }
            } else {
                if (!CommonConstants.PROTOCOL_KEY.equals(value[i])) {
                    if (hasInvocation) {
                        getNameCode = String.format(
                            "url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                    } else {
                        getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                    }
                } else {
                    getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                }
            }
        }

        return String.format(CODE_EXT_NAME_ASSIGNMENT, getNameCode);
    }

    /**
     * @return
     */
    private String generateScopeModelAssignment() {
        return String.format(CODE_SCOPE_MODEL_ASSIGNMENT, type.getName());
    }

    private String generateExtensionAssignment() {
        return String.format(CODE_EXTENSION_ASSIGNMENT, type.getName(), type.getName());
    }

    /**
     * generate method invocation statement and return it if necessary
     */
    private String generateReturnAndInvocation(Method method) {
        String returnStatement = method.getReturnType().equals(void.class) ? "" : "return ";

        String args = IntStream.range(0, method.getParameters().length)
            .mapToObj(i -> String.format(CODE_EXTENSION_METHOD_INVOKE_ARGUMENT, i))
            .collect(Collectors.joining(", "));

        return returnStatement + String.format("extension.%s(%s);\n", method.getName(), args);
    }

    /**
     * test if method has argument of type <code>Invocation</code>
     */
    private boolean hasInvocationArgument(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        return Arrays.stream(pts).anyMatch(p -> CLASS_NAME_INVOCATION.equals(p.getName()));
    }

    /**
     * generate code to test argument of type <code>Invocation</code> is null
     */
    private String generateInvocationArgumentNullCheck(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        return IntStream.range(0, pts.length)
            .filter(i -> CLASS_NAME_INVOCATION.equals(pts[i].getName()))
            .mapToObj(i -> String.format(CODE_INVOCATION_ARGUMENT_NULL_CHECK, i, i))
            .findFirst()
            .orElse("");
    }

    /**
     * get value of adaptive annotation or if empty return splitted simple name
     */
    private String[] getMethodAdaptiveValue(Adaptive adaptiveAnnotation) {
        String[] value = adaptiveAnnotation.value();
        // value is not set, use the value generated from class name as the key
        if (value.length == 0) {
            String splitName = StringUtils.camelToSplitName(type.getSimpleName(), ".");
            value = new String[]{splitName};
        }
        return value;
    }

    /**
     * get parameter with type <code>URL</code> from method parameter:
     * <p>
     * test if parameter has method which returns type <code>URL</code>
     * <p>
     * if not found, throws IllegalStateException
     * {@link AdaptiveClassCodeGenerator#generateMethodContent(Method)}中调用
     * 参考{@link org.apache.dubbo.rpc.Protocol$Adaptive}
     */
    private String generateUrlAssignmentIndirectly(Method method) {
        /**
         * 获取方法的所有参数类型
         */
        Class<?>[] pts = method.getParameterTypes();
        /**
         * value是本方法参数method方法的参数下标
         * key是该下标的参数拥有的无参且返回类型为{@link URL}的get方法
         */
        Map<String, Integer> getterReturnUrl = new HashMap<>();
        // find URL getter method
        for (int i = 0; i < pts.length; ++i) {
            /**
             * 遍历方法的所有参数类型
             */
            for (Method m : pts[i].getMethods()) {
                /**
                 * 遍历第一层循环的每一个参数类型的所有方法
                 */
                String name = m.getName();
                if ((name.startsWith("get") || name.length() > 3)
                    && Modifier.isPublic(m.getModifiers())
                    && !Modifier.isStatic(m.getModifiers())
                    && m.getParameterTypes().length == 0
                    && m.getReturnType() == URL.class) {
                    /**
                     * 1,方法名称以get开始
                     * 2,方法是public的
                     * 3,方法不是static的
                     * 4,方法无参
                     * 5,方法返回类型为{@link URL}
                     *
                     * 从这可以看出来，如果有重名的方法，则取的是最后的那个参数的
                     */
                    getterReturnUrl.put(name, i);
                }
            }
        }

        if (getterReturnUrl.size() <= 0) {
            // getter method not found, throw
            throw new IllegalStateException("Failed to create adaptive class for interface " + type.getName()
                + ": not found url parameter or url attribute in parameters of method " + method.getName());
        }

        /**
         * 如果getterReturnUrl中，有方法名为"getUrl"的，选中该方法
         */
        Integer index = getterReturnUrl.get("getUrl");
        if (index != null) {
            /**
             * 参数判空
             */
            return generateGetUrlNullCheck(index, pts[index], "getUrl");
        } else {
            Map.Entry<String, Integer> entry =
                getterReturnUrl.entrySet().iterator().next();
            /**
             * 否则选中第一个
             * 参数判空
             */
            return generateGetUrlNullCheck(entry.getValue(), pts[entry.getValue()], entry.getKey());
        }
    }

    /**
     * 1, test if argi is null
     * 2, test if argi.getXX() returns null
     * 3, assign url with argi.getXX()
     * {@link AdaptiveClassCodeGenerator#generateUrlAssignmentIndirectly(Method)}中调用
     * 参考{@link org.apache.dubbo.rpc.Protocol$Adaptive}
     *
     * @param index  原方法中的参数下标位置
     * @param type   原方法中的该参数下标位置的参数类型
     * @param method type中,以get开头,无参且返回类型为{@link URL}的方法
     * @return
     */
    private String generateGetUrlNullCheck(int index, Class<?> type, String method) {
        // Null point check
        StringBuilder code = new StringBuilder();
        code.append(String.format(
            "if (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");\n",
            index, type.getName()));
        code.append(String.format(
            "if (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");\n",
            index, method, type.getName(), method));

        code.append(String.format("%s url = arg%d.%s();\n", URL.class.getName(), index, method));
        return code.toString();
    }
}
