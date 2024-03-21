package org.apache.dubbo.metadata.report;

import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

/**
 * @ClassName: MetadataReportFactory$Adaptive
 * @Description:
 * @Author: qinfajia
 * @Date: 2024/3/19 09:16
 * @Version: 1.0
 */
public class MetadataReportFactory$Adaptive implements org.apache.dubbo.metadata.report.MetadataReportFactory {

    public void destroy() {
        throw new UnsupportedOperationException("The method public default void org.apache.dubbo.metadata.report.MetadataReportFactory.destroy() of interface org.apache.dubbo.metadata.report.MetadataReportFactory is not adaptive method!");
    }

    public org.apache.dubbo.metadata.report.MetadataReport getMetadataReport(org.apache.dubbo.common.URL arg0) {
        if (arg0 == null) throw new IllegalArgumentException("url == null");
        org.apache.dubbo.common.URL url = arg0;
        String extName = (url.getProtocol() == null ? "redis" : url.getProtocol());
        if (extName == null)
            throw new IllegalStateException("Failed to get extension (org.apache.dubbo.metadata.report.MetadataReportFactory) name from url (" + url.toString() + ") use keys([protocol])");
        ScopeModel scopeModel = ScopeModelUtil.getOrDefault(url.getScopeModel(), org.apache.dubbo.metadata.report.MetadataReportFactory.class);
        org.apache.dubbo.metadata.report.MetadataReportFactory extension = (org.apache.dubbo.metadata.report.MetadataReportFactory) scopeModel.getExtensionLoader(org.apache.dubbo.metadata.report.MetadataReportFactory.class).getExtension(extName);
        return extension.getMetadataReport(arg0);
    }
}
