package com.dotmarketing.osgi.webinterceptors;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import com.dotcms.api.system.event.message.MessageSeverity;
import com.dotcms.api.system.event.message.MessageType;
import com.dotcms.api.system.event.message.SystemMessageEventUtil;
import com.dotcms.api.system.event.message.builder.SystemMessage;
import com.dotcms.api.system.event.message.builder.SystemMessageBuilder;
import com.dotcms.business.CloseDBIfOpened;
import com.dotcms.concurrent.DotConcurrentFactory;
import com.dotcms.filters.interceptor.Result;
import com.dotcms.filters.interceptor.WebInterceptor;
import com.dotcms.publisher.ajax.RemotePublishAjaxAction;
import com.dotcms.publisher.bundle.bean.Bundle;
import com.dotcms.publisher.business.DotPublisherException;
import com.dotcms.publisher.business.PublishQueueElement;
import com.dotcms.publisher.business.PublisherAPI;
import com.dotcms.publisher.pusher.PushPublisher;
import com.dotcms.publisher.pusher.PushPublisherConfig;
import com.dotcms.publisher.pusher.PushUtils;
import com.dotcms.publisher.util.PublisherUtil;
import com.dotcms.publishing.BundlerStatus;
import com.dotcms.publishing.BundlerUtil;
import com.dotcms.publishing.DotBundleException;
import com.dotcms.publishing.DotPublishingException;
import com.dotcms.publishing.FilterDescriptor;
import com.dotcms.publishing.IBundler;
import com.dotcms.publishing.Publisher;
import com.dotcms.rest.WebResource;
import com.dotcms.util.TimeUtil;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.db.DbConnectionFactory;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.exception.DotRuntimeException;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.UtilMethods;
import com.google.common.collect.ImmutableList;
import com.liferay.portal.model.User;
import io.vavr.control.Try;

/**
 * This web interceptor adds the access control allow origin in addition to overrides the request
 * and response
 * 
 * @author jsanca
 */
public class DownloadBundleInterceptor implements WebInterceptor {

    @Override
    public String[] getFilters() {
        return new String[] {
                "/DotAjaxDirector/com.dotcms.publisher.ajax.RemotePublishAjaxAction/cmd/downloadUnpushedBundle/*"};
    }

    @Override
    public Result intercept(final HttpServletRequest request, final HttpServletResponse response) throws IOException {

        Result result = Result.SKIP_NO_CHAIN;
        User user = new WebResource.InitBuilder().requiredBackendUser(true).requestAndResponse(request, response)
                        .rejectWhenNoUser(true).requiredPortlet("publishing-queue").init().getUser();


        RemotePublishAjaxAction action = new RemotePublishAjaxAction();
        action.setURIParams(request);


        // Read the parameters
        final Map<String, String> map = action.getURIParams();
        final String bundleId = map.get("bundleId");
        final String paramOperation = map.get("operation");
        final String bundleFilter = UtilMethods.isSet(map.get("filterKey")) ? map.get("filterKey") : "";
        if (bundleId == null || bundleId.isEmpty()) {
            Logger.error(this.getClass(), "No Bundle Found with id: " + bundleId);
            response.sendError(500, "No Bundle Found with id: " + bundleId);
            return result;
        }

        

        BundleGenerator generator = new BundleGenerator(bundleId, paramOperation, bundleFilter, user);
        DotConcurrentFactory.getInstance().getSubmitter().submit(generator);
        response.setContentType( "application/x-tgz" );
        response.setHeader( "Content-Disposition", "attachment; filename=" + bundleId + ".tar.gz" );
        byte[] whiteByte =  " ".getBytes();
        
        final long nowsers = System.currentTimeMillis();
        
        
        try(final OutputStream outStream = response.getOutputStream()){
            while(generator.bundleFile==null) {
                Logger.info(this.getClass(), "writing bundleId:" + bundleId + " time elapsed:" + Duration.ofMillis(System.currentTimeMillis()-nowsers).toString().substring(2).replaceAll("(\\d[HMS])(?!$)", "$1 ").toLowerCase());
                
                
                outStream.write(whiteByte);
                Try.run(()->Thread.sleep(5000));
            }
    
            
            try(BufferedInputStream in = new BufferedInputStream(Files.newInputStream(generator.bundleFile.toPath()))){
                IOUtils.copy(in, outStream);
            }
        }
        

        return result;
    }


    class BundleGenerator implements Runnable {


        public BundleGenerator(String bundleId, String paramOperation, String bundleFilter, User user) {
            super();
            this.bundleId = bundleId;
            this.paramOperation = paramOperation;
            this.bundleFilter = bundleFilter;
            this.user = user;
            
        }

        File bundleFile;
        final String bundleId;
        final String paramOperation;
        final String bundleFilter;
        final User user;
        @Override
        public void run() {
            bundleFile = generateUnpublishedBundle();
        }

        /**
         * Generates and flush an Unpublish bundle for a given bundle id and operation (publish/unpublish)
         *
         * @param request HttpRequest
         * @param response HttpResponse
         * @throws IOException If fails sending back to the user response information
         */
        public File generateUnpublishedBundle() {
            try {
                // What we want to do with this bundle
                PushPublisherConfig.Operation operation = PushPublisherConfig.Operation.PUBLISH;
                if (paramOperation != null && paramOperation.equalsIgnoreCase("unpublish")) {
                    operation = PushPublisherConfig.Operation.UNPUBLISH;
                }
    
                final Bundle dbBundle = Try.of(() -> APILocator.getBundleAPI().getBundleById(bundleId))
                                .getOrElseThrow(e -> new DotRuntimeException(e));
    


                // set Filter to the bundle
                dbBundle.setFilterKey(bundleFilter);
                // set ForcePush value of the filter to the bundle
                dbBundle.setForcePush((boolean) APILocator.getPublisherAPI().getFilterDescriptorByKey(bundleFilter)
                                .getFilters().getOrDefault(FilterDescriptor.FORCE_PUSH_KEY, false));
                // Update Bundle
                APILocator.getBundleAPI().updateBundle(dbBundle);

                // Generate the bundle file for this given operation
                final Map<String, Object> bundleData = generateBundle(bundleId, operation);
                
                

                final SystemMessageBuilder systemMessageBuilder = new SystemMessageBuilder();

                final String message = "Bundle '" + dbBundle.getName() + "' can be downloaded here: <a href='/DotAjaxDirector/com.dotcms.publisher.ajax.RemotePublishAjaxAction/cmd/downloadBundle/bid/" + bundleId + "'>download</a>";

                SystemMessage systemMessage = systemMessageBuilder.setMessage(message).setType(MessageType.SIMPLE_MESSAGE)
                                .setSeverity(MessageSeverity.SUCCESS).setLife(1000*60*12).create();

                SystemMessageEventUtil.getInstance().pushMessage(systemMessage, ImmutableList.of(user.getUserId()));
                
                
                
                
                return (File) bundleData.get("file");
            } catch (Exception e) {
                throw new DotRuntimeException(e);
            }finally {
                DbConnectionFactory.closeSilently();
            }

        }

        /**
         * Generates an Unpublish bundle for a given bundle id operation (publish/unpublish)
         *
         * @param bundleId The Bundle id of the Bundle we want to generate
         * @param operation Download for publish or un-publish
         * @return The generated requested Bundle file
         * @throws DotPublisherException If fails retrieving the Bundle contents
         * @throws DotDataException If fails finding the system user
         * @throws DotPublishingException If fails initializing the Publisher
         * @throws IllegalAccessException If fails creating new Bundlers instances
         * @throws InstantiationException If fails creating new Bundlers instances
         * @throws DotBundleException If fails generating the Bundle
         * @throws IOException If fails compressing the all the Bundle contents into the final Bundle file
         */
        @CloseDBIfOpened
        @SuppressWarnings("unchecked")
        private Map<String, Object> generateBundle(String bundleId, PushPublisherConfig.Operation operation)
                        throws DotPublisherException, DotDataException, DotPublishingException, IllegalAccessException,
                        InstantiationException, DotBundleException, IOException {

            final PushPublisherConfig pushPublisherConfig = new PushPublisherConfig();
            final PublisherAPI publisherAPI = PublisherAPI.getInstance();

            final List<PublishQueueElement> tempBundleContents = publisherAPI.getQueueElementsByBundleId(bundleId);
            final List<PublishQueueElement> assetsToPublish = new ArrayList<PublishQueueElement>();

            for (final PublishQueueElement publishQueueElement : tempBundleContents) {
                assetsToPublish.add(publishQueueElement);
            }

            pushPublisherConfig.setDownloading(true);
            pushPublisherConfig.setOperation(operation);

            pushPublisherConfig.setAssets(assetsToPublish);
            // Queries creation
            pushPublisherConfig.setLuceneQueries(PublisherUtil.prepareQueries(tempBundleContents));
            pushPublisherConfig.setId(bundleId);
            pushPublisherConfig.setUser(APILocator.getUserAPI().getSystemUser());

            // BUNDLERS

            final List<Class<IBundler>> bundlers = new ArrayList<Class<IBundler>>();
            final List<IBundler> confBundlers = new ArrayList<IBundler>();

            final Publisher publisher = new PushPublisher();
            publisher.init(pushPublisherConfig);
            // Add the bundles for this publisher
            for (final Class clazz : publisher.getBundlers()) {
                if (!bundlers.contains(clazz)) {
                    bundlers.add(clazz);
                }
            }
            final File bundleRoot = BundlerUtil.getBundleRoot(pushPublisherConfig);

            // Run bundlers
            BundlerUtil.writeBundleXML(pushPublisherConfig);
            for (final Class<IBundler> clazzBundler : bundlers) {

                final IBundler bundler = clazzBundler.newInstance();
                confBundlers.add(bundler);
                bundler.setConfig(pushPublisherConfig);
                bundler.setPublisher(publisher);
                final BundlerStatus bundlerStatus = new BundlerStatus(bundler.getClass().getName());
                // Generate the bundler
                Logger.info(this, "Start of Bundler: " + clazzBundler.getSimpleName());
                bundler.generate(bundleRoot, bundlerStatus);
                Logger.info(this, "End of Bundler: " + clazzBundler.getSimpleName());
                DbConnectionFactory.closeSilently();
            }

            pushPublisherConfig.setBundlers(confBundlers);

            // Compressing bundle
            final ArrayList<File> fileList = new ArrayList<File>();
            fileList.add(bundleRoot);
            final File bundle = new File(bundleRoot + File.separator + ".." + File.separator
                            + pushPublisherConfig.getId() + ".tar.gz");

            final Map<String, Object> bundleData = new HashMap<String, Object>();
            bundleData.put("file", PushUtils.compressFiles(fileList, bundle, bundleRoot.getAbsolutePath()));
            
            
            
            
            
            
            return bundleData;
        }


    }
}
