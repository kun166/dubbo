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

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.ExtensionPostProcessor;

public class ScopeModelAwareExtensionProcessor implements ExtensionPostProcessor, ScopeModelAccessor {

    /**
     * {@link ScopeModelAwareExtensionProcessor#ScopeModelAwareExtensionProcessor(org.apache.dubbo.rpc.model.ScopeModel)}
     * 构造函数中赋值
     * {@link FrameworkModel}
     * {@link ApplicationModel}
     * {@link ModuleModel}
     */
    private ScopeModel scopeModel;
    private FrameworkModel frameworkModel;
    private ApplicationModel applicationModel;
    private ModuleModel moduleModel;

    /**
     * <p>
     * {@link ScopeModel#initialize()}中调用
     * </p>
     *
     * @param scopeModel
     */
    public ScopeModelAwareExtensionProcessor(ScopeModel scopeModel) {
        this.scopeModel = scopeModel;
        initialize();
    }

    /**
     * 根据传入的{@link ScopeModelAwareExtensionProcessor#scopeModel}分别初始化
     * {@link ScopeModelAwareExtensionProcessor#frameworkModel}
     * {@link ScopeModelAwareExtensionProcessor#applicationModel}
     * {@link ScopeModelAwareExtensionProcessor#moduleModel}
     * <p>
     * {@link ScopeModelAwareExtensionProcessor#ScopeModelAwareExtensionProcessor(org.apache.dubbo.rpc.model.ScopeModel)}
     * 构造函数中调用
     * </p>
     */
    private void initialize() {

        // NOTE: Do not create a new model or use the default application/module model here!
        // Only the visible and only matching scope model can be injected, that is, module -> application -> framework.
        // The converse is a one-to-many relationship and cannot be injected.
        // One framework may have multiple applications, and one application may have multiple modules.
        // So, the spi extension/bean of application scope can be injected its application model and framework model,
        // but the spi extension/bean of framework scope cannot be injected an application or module model.

        if (scopeModel instanceof FrameworkModel) {
            frameworkModel = (FrameworkModel) scopeModel;
        } else if (scopeModel instanceof ApplicationModel) {
            applicationModel = (ApplicationModel) scopeModel;
            frameworkModel = applicationModel.getFrameworkModel();
        } else if (scopeModel instanceof ModuleModel) {
            // 从这里可以看到，这三者有分层关系
            moduleModel = (ModuleModel) scopeModel;
            applicationModel = moduleModel.getApplicationModel();
            frameworkModel = applicationModel.getFrameworkModel();
        }
    }

    /**
     * <p>
     * {@link ExtensionLoader#postProcessAfterInitialization(java.lang.Object, java.lang.String)}中调用
     * </p>
     *
     * @param instance
     * @param name
     * @return
     * @throws Exception
     */
    @Override
    public Object postProcessAfterInitialization(Object instance, String name) throws Exception {
        if (instance instanceof ScopeModelAware) {
            ScopeModelAware modelAware = (ScopeModelAware) instance;
            modelAware.setScopeModel(scopeModel);
            if (this.moduleModel != null) {
                modelAware.setModuleModel(this.moduleModel);
            }
            if (this.applicationModel != null) {
                modelAware.setApplicationModel(this.applicationModel);
            }
            if (this.frameworkModel != null) {
                modelAware.setFrameworkModel(this.frameworkModel);
            }
        }
        return instance;
    }

    @Override
    public ScopeModel getScopeModel() {
        return scopeModel;
    }

    @Override
    public FrameworkModel getFrameworkModel() {
        return frameworkModel;
    }

    @Override
    public ApplicationModel getApplicationModel() {
        return applicationModel;
    }

    @Override
    public ModuleModel getModuleModel() {
        return moduleModel;
    }
}
