package org.apache.dubbo.metrics.report;

import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

/**
 * @ClassName: MetricsReporterFactory$Adaptive
 * @Description:
 * @Author: qinfajia
 * @Date: 2024/3/19 09:13
 * @Version: 1.0
 */
public class MetricsReporterFactory$Adaptive implements org.apache.dubbo.metrics.report.MetricsReporterFactory {


    public org.apache.dubbo.metrics.report.MetricsReporter createMetricsReporter(org.apache.dubbo.common.URL arg0) {
        if (arg0 == null)
            throw new IllegalArgumentException("url == null");
        org.apache.dubbo.common.URL url = arg0;
        String extName = (url.getProtocol() == null ? "nop" : url.getProtocol());
        if (extName == null)
            throw new IllegalStateException("Failed to get extension (org.apache.dubbo.metrics.report.MetricsReporterFactory) name from url (" + url.toString() + ") use keys([protocol])");
        ScopeModel scopeModel = ScopeModelUtil.getOrDefault(url.getScopeModel(), org.apache.dubbo.metrics.report.MetricsReporterFactory.class);
        org.apache.dubbo.metrics.report.MetricsReporterFactory extension = (org.apache.dubbo.metrics.report.MetricsReporterFactory) scopeModel.getExtensionLoader(org.apache.dubbo.metrics.report.MetricsReporterFactory.class).getExtension(extName);
        return extension.createMetricsReporter(arg0);
    }

}
