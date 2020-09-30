package com.dotmarketing.osgi.webinterceptors;

import org.osgi.framework.BundleContext;
import com.dotcms.filters.interceptor.FilterWebInterceptorProvider;
import com.dotcms.filters.interceptor.WebInterceptorDelegate;
import com.dotmarketing.filters.AutoLoginFilter;
import com.dotmarketing.filters.CMSFilter;
import com.dotmarketing.loggers.Log4jUtil;
import com.dotmarketing.osgi.GenericBundleActivator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import com.dotmarketing.util.Config;

public class Activator extends GenericBundleActivator {
    private LoggerContext pluginLoggerContext;
    private String interceptorName;

    @SuppressWarnings("unchecked")
    public void start(BundleContext context) throws Exception {
        
        //Initializing log4j...
        LoggerContext dotcmsLoggerContext = Log4jUtil.getLoggerContext();
        //Initialing the log4j context of this plugin based on the dotCMS logger context
        pluginLoggerContext = (LoggerContext) LogManager
                .getContext(this.getClass().getClassLoader(),
                        false,
                        dotcmsLoggerContext,
                        dotcmsLoggerContext.getConfigLocation());
        // Initializing services...
        initializeServices(context);


        final FilterWebInterceptorProvider filterWebInterceptorProvider =
                        FilterWebInterceptorProvider.getInstance(Config.CONTEXT);

        final WebInterceptorDelegate delegate = filterWebInterceptorProvider.getDelegate(AutoLoginFilter.class);

        final DownloadBundleInterceptor downloadBundleInterceptor = new DownloadBundleInterceptor();
        this.interceptorName = downloadBundleInterceptor.getName();
        delegate.addFirst(downloadBundleInterceptor);


        CMSFilter.addExclude("/app/helloworld");
    }

    public void stop(BundleContext context) throws Exception {

        Log4jUtil.shutdown(pluginLoggerContext);
        final FilterWebInterceptorProvider filterWebInterceptorProvider =
                        FilterWebInterceptorProvider.getInstance(Config.CONTEXT);

        final WebInterceptorDelegate delegate = filterWebInterceptorProvider.getDelegate(AutoLoginFilter.class);

        delegate.remove(this.interceptorName, true);


    }

}
