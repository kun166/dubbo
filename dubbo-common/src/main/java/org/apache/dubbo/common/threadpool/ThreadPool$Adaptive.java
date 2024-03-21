package org.apache.dubbo.common.threadpool;

import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

public class ThreadPool$Adaptive implements org.apache.dubbo.common.threadpool.ThreadPool {
    public java.util.concurrent.Executor getExecutor(org.apache.dubbo.common.URL arg0) {
        if (arg0 == null) throw new IllegalArgumentException("url == null");
        org.apache.dubbo.common.URL url = arg0;
        String extName = url.getParameter("threadpool", "fixed");
        if (extName == null)
            throw new IllegalStateException("Failed to get extension (org.apache.dubbo.common.threadpool.ThreadPool) name from url (" + url.toString() + ") use keys([threadpool])");
        ScopeModel scopeModel = ScopeModelUtil.getOrDefault(url.getScopeModel(), org.apache.dubbo.common.threadpool.ThreadPool.class);
        org.apache.dubbo.common.threadpool.ThreadPool extension = (org.apache.dubbo.common.threadpool.ThreadPool) scopeModel.getExtensionLoader(org.apache.dubbo.common.threadpool.ThreadPool.class).getExtension(extName);
        return extension.getExecutor(arg0);
    }
}
