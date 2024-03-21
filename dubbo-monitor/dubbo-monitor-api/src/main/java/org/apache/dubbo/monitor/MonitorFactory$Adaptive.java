package org.apache.dubbo.monitor;

import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

/**
 * @ClassName: MonitorFactory$Adaptive
 * @Description:
 * @Author: qinfajia
 * @Date: 2024/3/19 09:21
 * @Version: 1.0
 */
public class MonitorFactory$Adaptive implements org.apache.dubbo.monitor.MonitorFactory {

    public org.apache.dubbo.monitor.Monitor getMonitor(org.apache.dubbo.common.URL arg0) {
        if (arg0 == null)
            throw new IllegalArgumentException("url == null");
        org.apache.dubbo.common.URL url = arg0;
        String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
        if (extName == null)
            throw new IllegalStateException("Failed to get extension (org.apache.dubbo.monitor.MonitorFactory) name from url (" + url.toString() + ") use keys([protocol])");
        ScopeModel scopeModel = ScopeModelUtil.getOrDefault(url.getScopeModel(), org.apache.dubbo.monitor.MonitorFactory.class);
        org.apache.dubbo.monitor.MonitorFactory extension = (org.apache.dubbo.monitor.MonitorFactory) scopeModel.getExtensionLoader(org.apache.dubbo.monitor.MonitorFactory.class).getExtension(extName);
        return extension.getMonitor(arg0);
    }
}
