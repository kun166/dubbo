package org.apache.dubbo.rpc;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

/**
 * @ClassName: ProxyFactory$Adaptive
 * @Description:
 * @Author: qinfajia
 * @Date: 2024/3/19 09:19
 * @Version: 1.0
 */
public class ProxyFactory$AdaptiveSimple implements ProxyFactory {

    public Object getProxy(Invoker arg0) throws RpcException {
        if (arg0 == null)
            throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
        if (arg0.getUrl() == null)
            throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
        org.apache.dubbo.common.URL url = arg0.getUrl();
        String extName = url.getParameter("proxy", "javassist");
        if (extName == null)
            throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
        ScopeModel scopeModel = ScopeModelUtil.getOrDefault(url.getScopeModel(), ProxyFactory.class);
        ProxyFactory extension = (ProxyFactory) scopeModel.getExtensionLoader(ProxyFactory.class).getExtension(extName);
        return extension.getProxy(arg0);
    }

    public Object getProxy(Invoker arg0, boolean arg1) throws RpcException {
        if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
        if (arg0.getUrl() == null)
            throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
        org.apache.dubbo.common.URL url = arg0.getUrl();
        String extName = url.getParameter("proxy", "javassist");
        if (extName == null)
            throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
        ScopeModel scopeModel = ScopeModelUtil.getOrDefault(url.getScopeModel(), ProxyFactory.class);
        ProxyFactory extension = (ProxyFactory) scopeModel.getExtensionLoader(ProxyFactory.class).getExtension(extName);
        return extension.getProxy(arg0, arg1);
    }

    public Invoker getInvoker(Object arg0,
                              Class arg1,
                              URL arg2) throws RpcException {
        if (arg2 == null)
            throw new IllegalArgumentException("url == null");
        URL url = arg2;
        String extName = url.getParameter("proxy", "javassist");
        if (extName == null)
            throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
        ScopeModel scopeModel = ScopeModelUtil.getOrDefault(url.getScopeModel(), ProxyFactory.class);
        ProxyFactory extension = scopeModel.getExtensionLoader(ProxyFactory.class).getExtension(extName);
        return extension.getInvoker(arg0, arg1, arg2);
    }
}
