package org.apache.dubbo.remoting;

import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

/**
 * @ClassName: Dispatcher$Adaptive
 * @Description:
 * @Author: qinfajia
 * @Date: 2024/3/19 09:24
 * @Version: 1.0
 */
public class Dispatcher$Adaptive implements org.apache.dubbo.remoting.Dispatcher {
    public org.apache.dubbo.remoting.ChannelHandler dispatch(org.apache.dubbo.remoting.ChannelHandler arg0, org.apache.dubbo.common.URL arg1) {
        if (arg1 == null) throw new IllegalArgumentException("url == null");
        org.apache.dubbo.common.URL url = arg1;
        String extName = url.getParameter("dispatcher", url.getParameter("dispather", url.getParameter("channel.handler", "all")));
        if (extName == null)
            throw new IllegalStateException("Failed to get extension (org.apache.dubbo.remoting.Dispatcher) name from url (" + url.toString() + ") use keys([dispatcher, dispather, channel.handler])");
        ScopeModel scopeModel = ScopeModelUtil.getOrDefault(url.getScopeModel(), org.apache.dubbo.remoting.Dispatcher.class);
        org.apache.dubbo.remoting.Dispatcher extension = (org.apache.dubbo.remoting.Dispatcher) scopeModel.getExtensionLoader(org.apache.dubbo.remoting.Dispatcher.class).getExtension(extName);
        return extension.dispatch(arg0, arg1);
    }
}
