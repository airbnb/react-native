/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 * <p>
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.modules.network;

import com.facebook.react.bridge.ExecutorToken;
import com.facebook.react.bridge.GuardedAsyncTask;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.common.network.OkHttpCallUtil;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.JavaNetCookieJar;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Implements the XMLHttpRequest JavaScript interface.
 */
@ReactModule(name = "RCTNetworking", supportsWebWorkers = true)
public final class NetworkingModule extends ReactContextBaseJavaModule {

  private static final String CONTENT_ENCODING_HEADER_NAME = "content-encoding";
  private static final String CONTENT_TYPE_HEADER_NAME = "content-type";
  private static final String REQUEST_BODY_KEY_STRING = "string";
  private static final String REQUEST_BODY_KEY_URI = "uri";
  private static final String REQUEST_BODY_KEY_FORMDATA = "formData";
  private static final String USER_AGENT_HEADER_NAME = "user-agent";
  private static final int CHUNK_TIMEOUT_NS = 100 * 1000000; // 100ms

  private final OkHttpClient mClient;
  private final ForwardingCookieHandler mCookieHandler;
  private final @Nullable String mDefaultUserAgent;
  private final CookieJarContainer mCookieJarContainer;
  private final Set<Integer> mRequestIds;
  private boolean mShuttingDown;

  /* package */ NetworkingModule(
    ReactApplicationContext reactContext,
    @Nullable String defaultUserAgent,
    OkHttpClient client,
    @Nullable List<NetworkInterceptorCreator> networkInterceptorCreators) {
    super(reactContext);

    if (networkInterceptorCreators != null) {
      OkHttpClient.Builder clientBuilder = client.newBuilder();
      for (NetworkInterceptorCreator networkInterceptorCreator : networkInterceptorCreators) {
        clientBuilder.addNetworkInterceptor(networkInterceptorCreator.create());
      }
      client = clientBuilder.build();
    }
    mClient = client;
    OkHttpClientProvider.replaceOkHttpClient(client);
    mCookieHandler = new ForwardingCookieHandler(reactContext);
    mCookieJarContainer = (CookieJarContainer) mClient.cookieJar();
    mShuttingDown = false;
    mDefaultUserAgent = defaultUserAgent;
    mRequestIds = new HashSet<>();
  }

  /**
   * @param context the ReactContext of the application
   * @param defaultUserAgent the User-Agent header that will be set for all requests where the
   * caller does not provide one explicitly
   * @param client the {@link OkHttpClient} to be used for networking
   */
  /* package */ NetworkingModule(
      ReactApplicationContext context,
      @Nullable String defaultUserAgent,
      OkHttpClient client) {
    this(context, defaultUserAgent, client, null);
  }

  /**
   * @param context the ReactContext of the application
   */
  public NetworkingModule(final ReactApplicationContext context) {
    this(context, null, OkHttpClientProvider.getOkHttpClient(), null);
  }

  /**
   * @param context the ReactContext of the application
   * @param networkInterceptorCreators list of {@link NetworkInterceptorCreator}'s whose create()
   * methods would be called to attach the interceptors to the client.
   */
  public NetworkingModule(
    ReactApplicationContext context,
    List<NetworkInterceptorCreator> networkInterceptorCreators) {
    this(context, null, OkHttpClientProvider.getOkHttpClient(), networkInterceptorCreators);
  }

  /**
   * @param context the ReactContext of the application
   * @param defaultUserAgent the User-Agent header that will be set for all requests where the
   * caller does not provide one explicitly
   */
  public NetworkingModule(ReactApplicationContext context, String defaultUserAgent) {
    this(context, defaultUserAgent, OkHttpClientProvider.getOkHttpClient(), null);
  }

  @Override
  public void initialize() {
    mCookieJarContainer.setCookieJar(new JavaNetCookieJar(mCookieHandler));
  }

  @Override
  public String getName() {
    return "RCTNetworking";
  }

  @Override
  public void onCatalystInstanceDestroy() {
    mShuttingDown = true;
    cancelAllRequests();

    mCookieHandler.destroy();
    mCookieJarContainer.removeCookieJar();
  }

  @ReactMethod
  /**
   * @param timeout value of 0 results in no timeout
   */
  public void sendRequest(
      final ExecutorToken executorToken,
      String method,
      String url,
      final int requestId,
      ReadableArray headers,
      ReadableMap data,
      final String responseType,
      final boolean useIncrementalUpdates,
      int timeout) {
    RCTDeviceEventEmitter eventEmitter = getEventEmitter(executorToken);
    Request.Builder requestBuilder = buildRequest(executorToken, method, url, requestId, headers,
      data, eventEmitter);
    if (requestBuilder != null) {
      OkHttpClient client = buildOkHttpClient(responseType, useIncrementalUpdates, timeout,
        eventEmitter, requestId);
      addRequest(requestId);
      ResponseCallback callback = new ResponseCallback(requestId, responseType, eventEmitter,
        useIncrementalUpdates, this);
      fireRequest(requestBuilder, client, callback);
    }
  }

  public OkHttpClient buildOkHttpClient(
      final String responseType,
      final boolean useIncrementalUpdates,
      int timeout,
      final RCTDeviceEventEmitter eventEmitter,
      final int requestId) {
    OkHttpClient.Builder clientBuilder = mClient.newBuilder();

    // If JS is listening for progress updates, install a ProgressResponseBody that intercepts the
    // response and counts bytes received.
    if (useIncrementalUpdates) {
      clientBuilder.addNetworkInterceptor(new Interceptor() {
        @Override
        public Response intercept(Interceptor.Chain chain) throws IOException {
          Response originalResponse = chain.proceed(chain.request());
          ProgressResponseBody responseBody = new ProgressResponseBody(
            originalResponse.body(),
            new ProgressListener() {
              long last = System.nanoTime();

              @Override
              public void onProgress(long bytesWritten, long contentLength, boolean done) {
                long now = System.nanoTime();
                if (!done && !shouldDispatch(now, last)) {
                  return;
                }
                if (responseType.equals("text")) {
                  // For 'text' responses we continuously send response data with progress info to
                  // JS below, so no need to do anything here.
                  return;
                }
                ResponseUtil.onDataReceivedProgress(
                  eventEmitter,
                  requestId,
                  bytesWritten,
                  contentLength);
                last = now;
              }
            });
          return originalResponse.newBuilder().body(responseBody).build();
        }
      });
    }

    // If the current timeout does not equal the passed in timeout, we need to clone the existing
    // client and set the timeout explicitly on the clone.  This is cheap as everything else is
    // shared under the hood.
    // See https://github.com/square/okhttp/wiki/Recipes#per-call-configuration for more information
    if (timeout != mClient.connectTimeoutMillis()) {
      clientBuilder.readTimeout(timeout, TimeUnit.MILLISECONDS);
    }
    return clientBuilder.build();
  }

  @Nullable public Request.Builder buildRequest(
      ExecutorToken executorToken,
      String method,
      String url,
      final int requestId,
      ReadableArray headers,
      ReadableMap data,
      final RCTDeviceEventEmitter eventEmitter) {
    Request.Builder requestBuilder = new Request.Builder().url(url);

    if (requestId != 0) {
      requestBuilder.tag(requestId);
    }


    Headers requestHeaders = extractHeaders(headers, data);
    if (requestHeaders == null) {
      ResponseUtil.onRequestError(eventEmitter, requestId, "Unrecognized headers format", null);
      return null;
    }
    String contentType = requestHeaders.get(CONTENT_TYPE_HEADER_NAME);
    String contentEncoding = requestHeaders.get(CONTENT_ENCODING_HEADER_NAME);
    requestBuilder.headers(requestHeaders);

    if (data == null) {
      requestBuilder.method(method, RequestBodyUtil.getEmptyBody(method));
    } else if (data.hasKey(REQUEST_BODY_KEY_STRING)) {
      if (contentType == null) {
        ResponseUtil.onRequestError(
          eventEmitter,
          requestId,
          "Payload is set but no content-type header specified",
          null);
        return null;
      }
      String body = data.getString(REQUEST_BODY_KEY_STRING);
      MediaType contentMediaType = MediaType.parse(contentType);
      if (RequestBodyUtil.isGzipEncoding(contentEncoding)) {
        RequestBody requestBody = RequestBodyUtil.createGzip(contentMediaType, body);
        if (requestBody == null) {
          ResponseUtil.onRequestError(eventEmitter, requestId, "Failed to gzip request body", null);
          return null;
        }
        requestBuilder.method(method, requestBody);
      } else {
        requestBuilder.method(method, RequestBody.create(contentMediaType, body));
      }
    } else if (data.hasKey(REQUEST_BODY_KEY_URI)) {
      if (contentType == null) {
        ResponseUtil.onRequestError(
          eventEmitter,
          requestId,
          "Payload is set but no content-type header specified",
          null);
        return null;
      }
      String uri = data.getString(REQUEST_BODY_KEY_URI);
      InputStream fileInputStream =
        RequestBodyUtil.getFileInputStream(getReactApplicationContext(), uri);
      if (fileInputStream == null) {
        ResponseUtil.onRequestError(
          eventEmitter,
          requestId,
          "Could not retrieve file for uri " + uri,
          null);
        return null;
      }
      requestBuilder.method(
        method,
        RequestBodyUtil.create(MediaType.parse(contentType), fileInputStream));
    } else if (data.hasKey(REQUEST_BODY_KEY_FORMDATA)) {
      if (contentType == null) {
        contentType = "multipart/form-data";
      }
      ReadableArray parts = data.getArray(REQUEST_BODY_KEY_FORMDATA);
      MultipartBody.Builder multipartBuilder =
        constructMultipartBody(executorToken, parts, contentType, requestId);
      if (multipartBuilder == null) {
        return null;
      }

      requestBuilder.method(
        method,
        RequestBodyUtil.createProgressRequest(
          multipartBuilder.build(),
          new ProgressListener() {
            long last = System.nanoTime();

            @Override
            public void onProgress(long bytesWritten, long contentLength, boolean done) {
              long now = System.nanoTime();
              if (done || shouldDispatch(now, last)) {
                ResponseUtil.onDataSend(eventEmitter, requestId, bytesWritten, contentLength);
                last = now;
              }
            }
          }));
    } else {
      // Nothing in data payload, at least nothing we could understand anyway.
      requestBuilder.method(method, RequestBodyUtil.getEmptyBody(method));
    }
    return requestBuilder;
  }

  public void fireRequest(
      Request.Builder requestBuilder,
      OkHttpClient client,
      Callback callback) {
    client.newCall(requestBuilder.build()).enqueue(callback);
  }

  private static boolean shouldDispatch(long now, long last) {
    return last + CHUNK_TIMEOUT_NS < now;
  }

  public synchronized void addRequest(int requestId) {
    mRequestIds.add(requestId);
  }

  @Override public boolean isShuttingDown() {
    return mShuttingDown;
  }

  @Override public synchronized void removeRequest(int requestId) {
    mRequestIds.remove(requestId);
  }

  private synchronized void cancelAllRequests() {
    for (Integer requestId : mRequestIds) {
      cancelRequest(requestId);
    }
    mRequestIds.clear();
  }

  @ReactMethod
  public void abortRequest(ExecutorToken executorToken, final int requestId) {
    cancelRequest(requestId);
    removeRequest(requestId);
  }

  private void cancelRequest(final int requestId) {
    // We have to use AsyncTask since this might trigger a NetworkOnMainThreadException, this is an
    // open issue on OkHttp: https://github.com/square/okhttp/issues/869
    new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
      @Override
      protected void doInBackgroundGuarded(Void... params) {
        OkHttpCallUtil.cancelTag(mClient, Integer.valueOf(requestId));
      }
    }.execute();
  }

  @ReactMethod
  public void clearCookies(
      ExecutorToken executorToken,
      com.facebook.react.bridge.Callback callback) {
    mCookieHandler.clearCookies(callback);
  }

  @Override
  public boolean supportsWebWorkers() {
    return true;
  }

  private @Nullable MultipartBody.Builder constructMultipartBody(
      ExecutorToken ExecutorToken,
      ReadableArray body,
      String contentType,
      int requestId) {
    RCTDeviceEventEmitter eventEmitter = getEventEmitter(ExecutorToken);
    MultipartBody.Builder multipartBuilder = new MultipartBody.Builder();
    multipartBuilder.setType(MediaType.parse(contentType));

    for (int i = 0, size = body.size(); i < size; i++) {
      ReadableMap bodyPart = body.getMap(i);

      // Determine part's content type.
      ReadableArray headersArray = bodyPart.getArray("headers");
      Headers headers = extractHeaders(headersArray, null);
      if (headers == null) {
        ResponseUtil.onRequestError(
          eventEmitter,
          requestId,
          "Missing or invalid header format for FormData part.",
          null);
        return null;
      }
      MediaType partContentType = null;
      String partContentTypeStr = headers.get(CONTENT_TYPE_HEADER_NAME);
      if (partContentTypeStr != null) {
        partContentType = MediaType.parse(partContentTypeStr);
        // Remove the content-type header because MultipartBuilder gets it explicitly as an
        // argument and doesn't expect it in the headers array.
        headers = headers.newBuilder().removeAll(CONTENT_TYPE_HEADER_NAME).build();
      }

      if (bodyPart.hasKey(REQUEST_BODY_KEY_STRING)) {
        String bodyValue = bodyPart.getString(REQUEST_BODY_KEY_STRING);
        multipartBuilder.addPart(headers, RequestBody.create(partContentType, bodyValue));
      } else if (bodyPart.hasKey(REQUEST_BODY_KEY_URI)) {
        if (partContentType == null) {
          ResponseUtil.onRequestError(
            eventEmitter,
            requestId,
            "Binary FormData part needs a content-type header.",
            null);
          return null;
        }
        String fileContentUriStr = bodyPart.getString(REQUEST_BODY_KEY_URI);
        InputStream fileInputStream =
          RequestBodyUtil.getFileInputStream(getReactApplicationContext(), fileContentUriStr);
        if (fileInputStream == null) {
          ResponseUtil.onRequestError(
            eventEmitter,
            requestId,
            "Could not retrieve file for uri " + fileContentUriStr,
            null);
          return null;
        }
        multipartBuilder.addPart(headers, RequestBodyUtil.create(partContentType, fileInputStream));
      } else {
        ResponseUtil.onRequestError(eventEmitter, requestId, "Unrecognized FormData part.", null);
      }
    }
    return multipartBuilder;
  }

  /**
   * Extracts the headers from the Array. If the format is invalid, this method will return null.
   */
  private @Nullable Headers extractHeaders(
      @Nullable ReadableArray headersArray,
      @Nullable ReadableMap requestData) {
    if (headersArray == null) {
      return null;
    }
    Headers.Builder headersBuilder = new Headers.Builder();
    for (int headersIdx = 0, size = headersArray.size(); headersIdx < size; headersIdx++) {
      ReadableArray header = headersArray.getArray(headersIdx);
      if (header == null || header.size() != 2) {
        return null;
      }
      String headerName = header.getString(0);
      String headerValue = header.getString(1);
      if (headerName == null || headerValue == null) {
        return null;
      }
      headersBuilder.add(headerName, headerValue);
    }
    if (headersBuilder.get(USER_AGENT_HEADER_NAME) == null && mDefaultUserAgent != null) {
      headersBuilder.add(USER_AGENT_HEADER_NAME, mDefaultUserAgent);
    }

    // Sanitize content encoding header, supported only when request specify payload as string
    boolean isGzipSupported = requestData != null && requestData.hasKey(REQUEST_BODY_KEY_STRING);
    if (!isGzipSupported) {
      headersBuilder.removeAll(CONTENT_ENCODING_HEADER_NAME);
    }

    return headersBuilder.build();
  }

  public RCTDeviceEventEmitter getEventEmitter(ExecutorToken ExecutorToken) {
    return getReactApplicationContext()
      .getJSModule(ExecutorToken, RCTDeviceEventEmitter.class);
  }
}
