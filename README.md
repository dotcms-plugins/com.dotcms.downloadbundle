# README

When creating and downloaing a large bundle built in dotCMS, the http request can often time out.  This is because a large bundle can take a long time to generate and load balancers see no activity and will shut down the "dead" connection.

This plugin intercepts the call to generate a bundle and run the bundle generation in the background.  Once this process is done, the plugin will notify the user that their bundle is ready for download, whereby the user can click the link and download the resulting bundle.

This bundle intercepts calls to 
`/DotAjaxDirector/com.dotcms.publisher.ajax.RemotePublishAjaxAction/cmd/downloadUnpushedBundle/*`


## How to build this plugin

To install all you need to do is build the JAR. to do this run
`./gradlew jar`

This will build two jars in the `build/libs` directory: a bundle fragment (in order to expose needed 3rd party libraries from dotCMS) and the plugin jar 

* **To install this bundle:**

    Copy the bundle jar files inside the Felix OSGI container (*dotCMS/felix/load*).
        
    OR
        
    Upload the bundle jars files using the dotCMS UI (*CMS Admin->Dynamic Plugins->Upload Plugin*).

* **To uninstall this bundle:**
    
    Remove the bundle jars files from the Felix OSGI container (*dotCMS/felix/load*).

    OR

    Undeploy the bundle jars using the dotCMS UI (*CMS Admin->Dynamic Plugins->Undeploy*).
