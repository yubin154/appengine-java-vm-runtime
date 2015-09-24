/**
 * Copyright 2015 Google Inc. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS-IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.apphosting.vmruntime.jetty9;

import com.google.appengine.api.memcache.MemcacheSerialization;
import com.google.appengine.spi.ServiceFactoryFactory;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.runtime.DatastoreSessionStore;
import com.google.apphosting.runtime.DeferredDatastoreSessionStore;
import com.google.apphosting.runtime.MemcacheSessionStore;
import com.google.apphosting.runtime.SessionStore;
import com.google.apphosting.runtime.jetty9.SessionManager;
import com.google.apphosting.runtime.timer.Timer;
import com.google.apphosting.utils.config.AppEngineConfigException;
import com.google.apphosting.utils.config.AppEngineWebXml;
import com.google.apphosting.utils.config.AppEngineWebXmlReader;
import com.google.apphosting.utils.servlet.HttpServletRequestAdapter;
import com.google.apphosting.vmruntime.VmApiProxyDelegate;
import com.google.apphosting.vmruntime.VmApiProxyEnvironment;
import com.google.apphosting.vmruntime.VmEnvironmentFactory;
import com.google.apphosting.vmruntime.VmMetadataCache;
import com.google.apphosting.vmruntime.VmRequestUtils;
import com.google.apphosting.vmruntime.VmRuntimeFileLogHandler;
import com.google.apphosting.vmruntime.VmRuntimeLogHandler;
import com.google.apphosting.vmruntime.VmRuntimeUtils;
import com.google.apphosting.vmruntime.VmTimer;


import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.session.AbstractSessionManager;
import org.eclipse.jetty.util.log.AbstractLogger;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * WebAppContext for VM Runtimes. This class extends the "normal" AppEngineWebAppContext with
 * functionality that installs a request specific thread local environment on each incoming request.
 */
public class VmRuntimeWebAppContext
  extends WebAppContext implements VmRuntimeTrustedAddressChecker {

  // It's undesirable to have the user app override classes provided by us.
  // So we mark them as Jetty system classes, which cannot be overridden.
  private static final String[] SYSTEM_CLASSES = {
    // The trailing dot means these are all Java packages, not individual classes.
    "com.google.appengine.api.",
    "com.google.appengine.tools.",
    "com.google.apphosting.",
    "com.google.cloud.sql.jdbc.",
    "com.google.protos.cloud.sql.",
    "com.google.storage.onestore.",
  };
  // constant.  If it's much larger than this we may need to
  // restructure the code a bit.
  protected static final int MAX_RESPONSE_SIZE = 32 * 1024 * 1024;

  private final VmMetadataCache metadataCache;
  private final Timer wallclockTimer;
  private VmApiProxyEnvironment defaultEnvironment;
  // Indicates if the context is running via the Cloud SDK, or the real runtime.
  
  boolean isDevMode;
  static {
    // Set SPI classloader priority to prefer the WebAppClassloader.
    System.setProperty(
        ServiceFactoryFactory.USE_THREAD_CONTEXT_CLASSLOADER_PROPERTY, Boolean.TRUE.toString());
    // Use thread context class loader for memcache deserialization.
    System.setProperty(
        MemcacheSerialization.USE_THREAD_CONTEXT_CLASSLOADER_PROPERTY, Boolean.TRUE.toString());
  }

  // List of Jetty configuration only needed if the quickstart process has been
  // executed, so we do not need the webinf, wedxml, fragment and annotation configurations
  // because they have been executed via the SDK.
  private static final String[] quickstartConfigurationClasses  = {
    org.eclipse.jetty.quickstart.QuickStartConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.plus.webapp.EnvConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.plus.webapp.PlusConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.webapp.JettyWebXmlConfiguration.class.getCanonicalName()
  };

  // List of all the standard Jetty configurations that need to be executed when there
  // is no quickstart-web.xml.
  private static final String[] preconfigurationClasses = {
    org.eclipse.jetty.webapp.WebInfConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.webapp.WebXmlConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.webapp.MetaInfConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.webapp.FragmentConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.plus.webapp.EnvConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.plus.webapp.PlusConfiguration.class.getCanonicalName(),
    org.eclipse.jetty.annotations.AnnotationConfiguration.class.getCanonicalName()
  };
  
  @Override
  protected void doStart() throws Exception {
    // unpack and Adjust paths.
    Resource base = getBaseResource();
    if (base == null) {
      String war=getWar();
      if (war==null)
        throw new IllegalStateException("No war");
      base = Resource.newResource(getWar());
    }
    Resource dir;
    if (base.isDirectory()) {
      dir = base;
    } else {
      throw new IllegalArgumentException("Bad base:"+base);
    }
    Resource qswebxml = dir.addPath("/WEB-INF/quickstart-web.xml");
    if (qswebxml.exists()) {
      setConfigurationClasses(quickstartConfigurationClasses);
    }
    super.doStart();
  }
  /**
   * Creates a List of SessionStores based on the configuration in the provided AppEngineWebXml.
   *
   * @param appEngineWebXml The AppEngineWebXml containing the session configuration.
   * @return A List of SessionStores in write order.
   */
  private static List<SessionStore> createSessionStores(AppEngineWebXml appEngineWebXml) {
    DatastoreSessionStore datastoreSessionStore =
        appEngineWebXml.getAsyncSessionPersistence() ? new DeferredDatastoreSessionStore(
            appEngineWebXml.getAsyncSessionPersistenceQueueName())
            : new DatastoreSessionStore();
    // Write session data to the datastore before we write to memcache.
    return Arrays.asList(datastoreSessionStore, new MemcacheSessionStore());
  }

  /**
   * Checks if the request was made over HTTPS. If so it modifies the request so that
   * {@code HttpServletRequest#isSecure()} returns true, {@code HttpServletRequest#getScheme()}
   * returns "https", and {@code HttpServletRequest#getServerPort()} returns 443. Otherwise it sets
   * the scheme to "http" and port to 80.
   *
   * @param request The request to modify.
   */
  private void setSchemeAndPort(Request request) {
    String https = request.getHeader(VmApiProxyEnvironment.HTTPS_HEADER);
    if ("on".equals(https)) {
      request.setSecure(true);
      request.setScheme(HttpScheme.HTTPS.toString());
      request.setAuthority(request.getServerName(), 443);
    } else {
      request.setSecure(false);
      request.setScheme(HttpScheme.HTTP.toString());
      request.setAuthority(request.getServerName(), defaultEnvironment.getServerPort());
    }
  }

  /**
   * Creates a new VmRuntimeWebAppContext.
   */
  public VmRuntimeWebAppContext() {
    setServerInfo(VmRuntimeUtils.getServerInfo());
    setLogger(new ContextLogger());
    _scontext = new VmRuntimeServletContext();

    // Configure the Jetty SecurityHandler to understand our method of authentication
    // (via the UserService). Only the default ConstraintSecurityHandler is supported.
    AppEngineAuthentication.configureSecurityHandler(
        (ConstraintSecurityHandler) getSecurityHandler(), this);

    setMaxFormContentSize(MAX_RESPONSE_SIZE);
    setConfigurationClasses(preconfigurationClasses);
    // See http://www.eclipse.org/jetty/documentation/current/configuring-webapps.html#webapp-context-attributes
    // We also want the Jetty container libs to be scanned for annotations.
    setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern", ".*\\.jar");
    metadataCache = new VmMetadataCache();
    wallclockTimer = new VmTimer();
    ApiProxy.setDelegate(new VmApiProxyDelegate());
  }

  /**
   * Initialize the WebAppContext for use by the VmRuntime.
   *
   * This method initializes the WebAppContext by setting the context path and application folder.
   * It will also parse the appengine-web.xml file provided to set System Properties and session
   * manager accordingly.
   *
   * @param appengineWebXmlFile The appengine-web.xml file path (relative to appDir).
   * @throws AppEngineConfigException If there was a problem finding or parsing the
   *         appengine-web.xml configuration.
   * @throws IOException If the runtime was unable to find/read appDir.
   */
  public void init(String appengineWebXmlFile)
      throws AppEngineConfigException, IOException {  
    String appDir=getBaseResource().getFile().getCanonicalPath();  
    defaultEnvironment = VmApiProxyEnvironment.createDefaultContext(
        System.getenv(), metadataCache, VmRuntimeUtils.getApiServerAddress(), wallclockTimer,
        VmRuntimeUtils.ONE_DAY_IN_MILLIS, appDir);
    ApiProxy.setEnvironmentForCurrentThread(defaultEnvironment);
    if (ApiProxy.getEnvironmentFactory() == null) {
      // Need the check above since certain unit tests initialize the context multiple times.
      ApiProxy.setEnvironmentFactory(new VmEnvironmentFactory(defaultEnvironment));
    }

    isDevMode = defaultEnvironment.getPartition().equals("dev");
    AppEngineWebXml appEngineWebXml = null;
    File appWebXml = new File(appDir, appengineWebXmlFile);
    if (appWebXml.exists()) {
      AppEngineWebXmlReader appEngineWebXmlReader
              = new AppEngineWebXmlReader(appDir, appengineWebXmlFile);
      appEngineWebXml = appEngineWebXmlReader.readAppEngineWebXml();
   }
    VmRuntimeUtils.installSystemProperties(defaultEnvironment, appEngineWebXml);
    VmRuntimeLogHandler.init();
    VmRuntimeFileLogHandler.init();

    for (String systemClass : SYSTEM_CLASSES) {
      addSystemClass(systemClass);
    }
    if (appEngineWebXml == null) {
      // No need to configure the session manager.
      return;
    }
    AbstractSessionManager sessionManager;
    if (appEngineWebXml.getSessionsEnabled()) {
      sessionManager = new SessionManager(createSessionStores(appEngineWebXml));
      getSessionHandler().setSessionManager(sessionManager);
    }
  }

  @Override
  public boolean isTrustedRemoteAddr(String remoteAddr) {
    return VmRequestUtils.isTrustedRemoteAddr(isDevMode, remoteAddr);
  }

  /**

   * Overrides doScope from ScopedHandler.
   *
   *  Configures a thread local environment before the request is forwarded on to be handled by the
   * SessionHandler, SecurityHandler, and ServletHandler in turn. The environment is required for
   * AppEngine APIs to function. A request specific environment is required since some information
   * is encoded in request headers on the request (for example current user).
   */
  @Override
  public final void doScope(
      String target, Request baseRequest, HttpServletRequest httpServletRequest ,
      HttpServletResponse httpServletResponse)
      throws IOException, ServletException {

    // For JSP Includes do standard processing, everything else has been done
    // in the main request before the include.
    if (DispatcherType.INCLUDE.equals(httpServletRequest.getDispatcherType())
        || DispatcherType.FORWARD.equals(httpServletRequest.getDispatcherType())) {
      super.doScope(target, baseRequest, httpServletRequest, httpServletResponse);
      return;
    }
    
    // Find or create a Request specific context
    RequestContext requestContext = getRequestContext(baseRequest);
    
    try {
      // Enter the request environment
      requestContext.enter();
      
      // Check for SkipAdminCheck and set attributes accordingly.
      VmRuntimeUtils.handleSkipAdminCheck(requestContext);
      
      // Change scheme to HTTPS based on headers set by the appserver.
      setSchemeAndPort(baseRequest);
      // Forward the request to the rest of the handlers.
      super.doScope(target, baseRequest, httpServletRequest, httpServletResponse);
      
    } finally {
      // Exit the request environment
      requestContext.exitDispatch();
    }
  }

  @Override
  public void handle(Request baseRequest, Runnable runnable) {
    // TODO Use pluggable ContextHandler.ContextScopeListener rather than override for this
    RequestContext requestContext = baseRequest==null?null:(RequestContext)baseRequest.getAttribute(RequestContext.class.getName());
    
    // If we have a requestContext enter/exit it 
    if (requestContext==null) {
      super.handle(baseRequest,runnable);
    } else {
      try {
        requestContext.enter();
        super.handle(baseRequest,runnable);
      }finally{
        requestContext.exit();
      }
    }
  }

  public RequestContext getRequestContext(Request baseRequest) {
    RequestContext requestContext = (RequestContext)baseRequest.getAttribute(RequestContext.class.getName());
    if (requestContext==null) {
      // No instance found, so create a new environment
      requestContext=new RequestContext(baseRequest);
      baseRequest.setAttribute(RequestContext.class.getName(), requestContext);
    }
    return requestContext;
  }

  /**
   * ServletContext for VmRuntime applications.
   * TODO is this still needed? If no securityManager super.getClassLoader() is equivalent
   */
  public class VmRuntimeServletContext extends Context {
    @Override
    public ClassLoader getClassLoader() {
      super.getClassLoader();
      return VmRuntimeWebAppContext.this.getClassLoader();
    }
  }
  
  private class RequestContext extends HttpServletRequestAdapter implements AsyncListener { 
    private final Request request;
    private final VmApiProxyEnvironment requestSpecificEnvironment;
    
    RequestContext(Request request) {
      super(request);

      this.request=request;

      this.requestSpecificEnvironment=
      VmApiProxyEnvironment.createFromHeaders(
          System.getenv(), metadataCache, this, VmRuntimeUtils.getApiServerAddress(),
          wallclockTimer, VmRuntimeUtils.ONE_DAY_IN_MILLIS, defaultEnvironment);
    }
    
    public void enter() {    
      ApiProxy.setEnvironmentForCurrentThread(getRequestSpecificEnvironment());
    }

    public void exitDispatch() {
      try {
        if (request.isAsyncStarted())
          request.getAsyncContext().addListener(this);
        else
          onComplete(null);
      } finally {
        exit();
      }
    }
    
    public void exit() {
        ApiProxy.setEnvironmentForCurrentThread(defaultEnvironment);
    }

    VmApiProxyEnvironment getRequestSpecificEnvironment() {
      return requestSpecificEnvironment;
    }
    
    @Override
    public void onComplete(AsyncEvent event) {
      // TODO is the interrupting and waiting still needed?
      // Interrupt any remaining request threads
      VmRuntimeUtils.interruptRequestThreads(
          requestSpecificEnvironment, VmRuntimeUtils.MAX_REQUEST_THREAD_INTERRUPT_WAIT_TIME_MS);
      requestSpecificEnvironment.waitForAllApiCallsToComplete(VmRuntimeUtils.MAX_REQUEST_THREAD_API_CALL_WAIT_MS);
    }

    @Override
    public void onTimeout(AsyncEvent event) {}

    @Override
    public void onError(AsyncEvent event)  {}

    @Override
    public void onStartAsync(AsyncEvent event) {}
  }
  
  
  private final static String SCL = "javax.servlet.ServletContext log: ";
  private class ContextLogger extends AbstractLogger  {
    org.eclipse.jetty.util.log.Logger context = Log.getLogger(VmRuntimeWebAppContext.class);

    public String getName() {
      return context.getName();
    }

    public void warn(String msg, Object... args) {
      context.warn(SCL+msg, args);
    }

    public void warn(Throwable thrown) {
      context.warn(thrown);
    }

    public void warn(String msg, Throwable thrown) {
      context.warn(SCL+msg, thrown);
    }

    public void info(String msg, Object... args) {
      context.info(SCL+msg, args);
    }

    public void info(Throwable thrown) {
      context.info(thrown);
    }

    public void info(String msg, Throwable thrown) {
      context.info(SCL+msg, thrown);
    }

    public boolean isDebugEnabled() {
      return context.isDebugEnabled();
    }

    public void setDebugEnabled(boolean enabled) {
      context.setDebugEnabled(enabled);
    }

    public void debug(String msg, Object... args) {
      context.debug(SCL+msg, args);
    }

    public void debug(String msg, long value) {
      context.debug(SCL+msg, value);
    }

    public void debug(Throwable thrown) {
      context.debug(thrown);
    }

    public void debug(String msg, Throwable thrown) {
      context.debug(SCL+msg, thrown);
    }

    public void ignore(Throwable ignored) {
      context.ignore(ignored);
    }

    @Override
    protected org.eclipse.jetty.util.log.Logger newLogger(String fullname) {
      return Log.getLogger(fullname);
    }
    
  }
}
