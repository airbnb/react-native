package com.facebook.react.modules.network;

import android.util.Base64;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.IOException;
import java.io.Reader;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * This class has been originally extracted out of {@link NetworkingModule} by pulling all the
 * network response parsing logic and related methods into this Callback.
 */
public class ResponseCallback implements Callback {
  private static final int MAX_CHUNK_SIZE_BETWEEN_FLUSHES = 8 * 1024; // 8K
  private final int requestId;
  private final String responseType;
  private final DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter;
  private final boolean useIncrementalUpdates;
  private final NetworkingModule networkingModule;

  public ResponseCallback(
      int requestId,
      String responseType,
      DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter,
      boolean useIncrementalUpdates,
      NetworkingModule networkingModule) {
    this.requestId = requestId;
    this.responseType = responseType;
    this.eventEmitter = eventEmitter;
    this.useIncrementalUpdates = useIncrementalUpdates;
    this.networkingModule = networkingModule;
  }

  @Override
  public void onFailure(Call call, IOException e) {
    if (networkingModule.isShuttingDown()) {
      return;
    }
    networkingModule.removeRequest(requestId);
    ResponseUtil.onRequestError(eventEmitter, requestId, e.getMessage(), toIOException(e));
  }

  private static IOException toIOException(Throwable e) {
    IOException ioException;
    if (e instanceof IOException) {
      ioException = (IOException) e;
    } else if (e.getCause() instanceof IOException) {
      ioException = (IOException) e.getCause();
    } else {
      ioException = new IOException(e.getMessage());
    }
    return ioException;
  }

  @Override
  public void onResponse(Call call, Response response) {
    if (networkingModule.isShuttingDown()) {
      return;
    }
    networkingModule.removeRequest(requestId);
    // Before we touch the body send headers to JS
    ResponseUtil.onResponseReceived(
      eventEmitter,
      requestId,
      response.code(),
      translateHeaders(response.headers()),
      response.request().url().toString());

    ResponseBody responseBody = response.body();
    try {
      // If JS wants progress updates during the download, and it requested a text response,
      // periodically send response data updates to JS.
      if (useIncrementalUpdates && responseType.equals("text")) {
        readWithProgress(eventEmitter, requestId, responseBody);
        ResponseUtil.onRequestSuccess(eventEmitter, requestId);
        return;
      }

      // Otherwise send the data in one big chunk, in the format that JS requested.
      String responseString = "";
      if (responseType.equals("text")) {
        responseString = responseBody.string();
      } else if (responseType.equals("base64")) {
        responseString = Base64.encodeToString(responseBody.bytes(), Base64.NO_WRAP);
      }
      ResponseUtil.onDataReceived(eventEmitter, requestId, responseString);
      ResponseUtil.onRequestSuccess(eventEmitter, requestId);
    } catch (IOException e) {
      ResponseUtil.onRequestError(eventEmitter, requestId, e.getMessage(), e);
    }
  }

  private void readWithProgress(
      DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter,
      int requestId,
      ResponseBody responseBody) throws IOException {
    long totalBytesRead = -1;
    long contentLength = -1;
    try {
      ProgressResponseBody progressResponseBody = (ProgressResponseBody) responseBody;
      totalBytesRead = progressResponseBody.totalBytesRead();
      contentLength = progressResponseBody.contentLength();
    } catch (ClassCastException e) {
      // Ignore
    }

    Reader reader = responseBody.charStream();
    try {
      char[] buffer = new char[MAX_CHUNK_SIZE_BETWEEN_FLUSHES];
      int read;
      while ((read = reader.read(buffer)) != -1) {
        ResponseUtil.onIncrementalDataReceived(
          eventEmitter,
          requestId,
          new String(buffer, 0, read),
          totalBytesRead,
          contentLength);
      }
    } finally {
      reader.close();
    }
  }

  private static WritableMap translateHeaders(Headers headers) {
    WritableMap responseHeaders = Arguments.createMap();
    for (int i = 0; i < headers.size(); i++) {
      String headerName = headers.name(i);
      // multiple values for the same header
      if (responseHeaders.hasKey(headerName)) {
        responseHeaders.putString(
          headerName,
          responseHeaders.getString(headerName) + ", " + headers.value(i));
      } else {
        responseHeaders.putString(headerName, headers.value(i));
      }
    }
    return responseHeaders;
  }
}
