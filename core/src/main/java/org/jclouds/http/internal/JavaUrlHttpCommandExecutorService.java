/**
 *
 * Copyright (C) 2009 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */
package org.jclouds.http.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.ws.rs.core.HttpHeaders;

import org.jclouds.http.HttpCommandExecutorService;
import org.jclouds.http.HttpConstants;
import org.jclouds.http.HttpRequest;
import org.jclouds.http.HttpResponse;
import org.jclouds.http.handlers.DelegatingErrorHandler;
import org.jclouds.http.handlers.DelegatingRetryHandler;

import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.io.Closeables;
import com.google.inject.Inject;

/**
 * Basic implementation of a {@link HttpCommandExecutorService}.
 * 
 * @author Adrian Cole
 */
@Singleton
public class JavaUrlHttpCommandExecutorService extends
         BaseHttpCommandExecutorService<HttpURLConnection> {

   public static final String USER_AGENT = "jclouds/1.0 java/" + System.getProperty("java.version");

   @Inject(optional = true)
   @Named(HttpConstants.PROPERTY_HTTP_RELAX_HOSTNAME)
   private boolean relaxHostname = false;
   private final Map<String, String> sslMap;

   @Inject(optional = true)
   @Named(HttpConstants.PROPERTY_HTTP_PROXY_SYSTEM)
   private boolean systemProxies = System.getProperty("java.net.useSystemProxies") != null ? Boolean
            .parseBoolean(System.getProperty("java.net.useSystemProxies"))
            : false;

   @Inject
   public JavaUrlHttpCommandExecutorService(ExecutorService executorService,
            DelegatingRetryHandler retryHandler, DelegatingErrorHandler errorHandler, HttpWire wire) {
      super(executorService, retryHandler, errorHandler, wire);
      sslMap = Maps.newHashMap();
   }

   /**
    * 
    * Used to get more information about HTTPS hostname wrong errors.
    * 
    * @author Adrian Cole
    */
   class LogToMapHostnameVerifier implements HostnameVerifier {

      public boolean verify(String hostname, SSLSession session) {
         logger.warn("hostname was %s while session was %s", hostname, session.getPeerHost());
         sslMap.put(hostname, session.getPeerHost());
         return true;
      }
   }

   @Override
   protected HttpResponse invoke(HttpURLConnection connection) throws IOException {
      HttpResponse response = new HttpResponse();
      InputStream in;
      try {
         in = connection.getInputStream();
      } catch (IOException e) {
         in = connection.getErrorStream();
      }
      if (in != null) {
         response.setContent(in);
      }
      for (String header : connection.getHeaderFields().keySet()) {
         response.getHeaders().putAll(header, connection.getHeaderFields().get(header));
      }
      response.setStatusCode(connection.getResponseCode());
      response.setMessage(connection.getResponseMessage());
      return response;
   }

   @Override
   protected HttpURLConnection convert(HttpRequest request) throws IOException {
      URL url = request.getEndpoint().toURL();
      HttpURLConnection connection;
      if (systemProxies) {
         System.setProperty("java.net.useSystemProxies", "true");
         Iterable<Proxy> proxies = ProxySelector.getDefault().select(request.getEndpoint());
         Proxy proxy = Iterables.getLast(proxies);
         connection = (HttpURLConnection) url.openConnection(proxy);
      } else {
         connection = (HttpURLConnection) url.openConnection();
      }
      if (relaxHostname && connection instanceof HttpsURLConnection) {
         HttpsURLConnection sslCon = (HttpsURLConnection) connection;
         sslCon.setHostnameVerifier(new LogToMapHostnameVerifier());
      }
      connection.setDoOutput(true);
      connection.setAllowUserInteraction(false);
      // do not follow redirects since https redirects don't work properly
      // ex. Caused by: java.io.IOException: HTTPS hostname wrong: should be
      // <adriancole.s3int0.s3-external-3.amazonaws.com>
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod(request.getMethod().toString());
      for (String header : request.getHeaders().keySet()) {
         for (String value : request.getHeaders().get(header)) {
            connection.setRequestProperty(header, value);

            if ("Transfer-Encoding".equals(header) && "chunked".equals(value)) {
               connection.setChunkedStreamingMode(8192);
            }
         }
      }
      connection.setRequestProperty(HttpHeaders.HOST, request.getEndpoint().getHost());
      connection.setRequestProperty(HttpHeaders.USER_AGENT, USER_AGENT);

      if (request.getPayload() != null) {
         OutputStream out = connection.getOutputStream();
         try {
            request.getPayload().writeTo(out);
         } finally {
            Closeables.closeQuietly(out);
         }
      } else {
         connection.setRequestProperty(HttpHeaders.CONTENT_LENGTH, "0");
      }
      return connection;
   }

   /**
    * Only disconnect if there is no content, as disconnecting will throw away unconsumed content.
    */
   @Override
   protected void cleanup(HttpURLConnection connection) {
      if (connection != null && connection.getContentLength() == 0)
         connection.disconnect();
   }

}
