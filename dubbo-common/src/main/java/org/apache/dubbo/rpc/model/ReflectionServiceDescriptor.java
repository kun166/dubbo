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

import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.metadata.definition.ServiceDefinitionBuilder;
import org.apache.dubbo.metadata.definition.model.FullServiceDefinition;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class ReflectionServiceDescriptor implements ServiceDescriptor {
    private final String interfaceName;
    private final Class<?> serviceInterfaceClass;
    // to accelerate search
    /**
     * {@link ReflectionServiceDescriptor#initMethods()}中添加数据
     * key为methodName
     */
    private final Map<String, List<MethodDescriptor>> methods = new HashMap<>();
    /**
     * {@link ReflectionServiceDescriptor#initMethods()}中添加数据
     * key为methodName,value的key为value的{@link ReflectionMethodDescriptor#paramDesc}
     */
    private final Map<String, Map<String, MethodDescriptor>> descToMethods = new HashMap<>();
    private final ConcurrentNavigableMap<String, FullServiceDefinition> serviceDefinitions =
        new ConcurrentSkipListMap<>();

    public ReflectionServiceDescriptor(String interfaceName, Class<?> interfaceClass) {
        this.interfaceName = interfaceName;
        this.serviceInterfaceClass = interfaceClass;
    }

    public void addMethod(MethodDescriptor methodDescriptor) {
        methods.put(methodDescriptor.getMethodName(), Collections.singletonList(methodDescriptor));
    }

    /**
     * <p>
     * {@link ModuleServiceRepository#registerService(java.lang.Class)}中调用
     * </p>
     *
     * @param interfaceClass
     */
    public ReflectionServiceDescriptor(Class<?> interfaceClass) {
        this.serviceInterfaceClass = interfaceClass;
        this.interfaceName = interfaceClass.getName();
        initMethods();
    }

    public FullServiceDefinition getFullServiceDefinition(String serviceKey) {
        return serviceDefinitions.computeIfAbsent(
            serviceKey,
            (k) -> ServiceDefinitionBuilder.buildFullDefinition(serviceInterfaceClass, Collections.emptyMap()));
    }

    /**
     * <p>
     * {@link ReflectionServiceDescriptor#ReflectionServiceDescriptor(java.lang.Class)}
     * 构造函数中调用
     * </p>
     */
    private void initMethods() {
        // 获取所有public方法,包括继承的
        Method[] methodsToExport = this.serviceInterfaceClass.getMethods();
        for (Method method : methodsToExport) {
            method.setAccessible(true);

            MethodDescriptor methodDescriptor = new ReflectionMethodDescriptor(method);

            List<MethodDescriptor> methodModels = methods.computeIfAbsent(method.getName(), (k) -> new ArrayList<>(1));
            methodModels.add(methodDescriptor);
        }

        methods.forEach((methodName, methodList) -> {
            Map<String, MethodDescriptor> descMap = descToMethods.computeIfAbsent(methodName, k -> new HashMap<>());
            // not support BI_STREAM and SERVER_STREAM at the same time, for example,
            // void foo(Request, StreamObserver<Response>)  ---> SERVER_STREAM
            // StreamObserver<Response> foo(StreamObserver<Request>)   ---> BI_STREAM
            long streamMethodCount = methodList.stream()
                .peek(methodModel -> descMap.put(methodModel.getParamDesc(), methodModel))
                .map(MethodDescriptor::getRpcType)
                .filter(rpcType -> rpcType == MethodDescriptor.RpcType.SERVER_STREAM
                    || rpcType == MethodDescriptor.RpcType.BI_STREAM)
                .count();
            if (streamMethodCount > 1L)
                throw new IllegalStateException("Stream method could not be overloaded.There are " + streamMethodCount
                    + " stream method signatures. method(" + methodName + ")");
        });
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public Class<?> getServiceInterfaceClass() {
        return serviceInterfaceClass;
    }

    public Set<MethodDescriptor> getAllMethods() {
        Set<MethodDescriptor> methodModels = new HashSet<>();
        methods.forEach((k, v) -> methodModels.addAll(v));
        return methodModels;
    }

    /**
     * Does not use Optional as return type to avoid potential performance decrease.
     *
     * @param methodName
     * @param params
     * @return
     */
    public MethodDescriptor getMethod(String methodName, String params) {
        Map<String, MethodDescriptor> methods = descToMethods.get(methodName);
        if (CollectionUtils.isNotEmptyMap(methods)) {
            return methods.get(params);
        }
        return null;
    }

    /**
     * Does not use Optional as return type to avoid potential performance decrease.
     *
     * @param methodName
     * @param paramTypes
     * @return
     */
    public MethodDescriptor getMethod(String methodName, Class<?>[] paramTypes) {
        List<MethodDescriptor> methodModels = methods.get(methodName);
        if (CollectionUtils.isNotEmpty(methodModels)) {
            for (MethodDescriptor descriptor : methodModels) {
                if (Arrays.equals(paramTypes, descriptor.getParameterClasses())) {
                    return descriptor;
                }
            }
        }
        return null;
    }

    public List<MethodDescriptor> getMethods(String methodName) {
        return methods.get(methodName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ReflectionServiceDescriptor that = (ReflectionServiceDescriptor) o;
        return Objects.equals(interfaceName, that.interfaceName)
            && Objects.equals(serviceInterfaceClass, that.serviceInterfaceClass)
            && Objects.equals(methods, that.methods)
            && Objects.equals(descToMethods, that.descToMethods);
    }

    @Override
    public int hashCode() {
        return Objects.hash(interfaceName, serviceInterfaceClass, methods, descToMethods);
    }
}
