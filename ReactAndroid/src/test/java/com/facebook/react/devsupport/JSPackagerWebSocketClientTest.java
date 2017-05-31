/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.devsupport;

import okio.ByteString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import okhttp3.MediaType;
import okhttp3.ResponseBody;

import static org.mockito.Mockito.*;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class JSPackagerWebSocketClientTest {

  @Test
  public void test_onMessage_ShouldTriggerCallback() throws IOException {
    final JSPackagerWebSocketClient.JSPackagerCallback callback =
      mock(JSPackagerWebSocketClient.JSPackagerCallback.class);
    final JSPackagerWebSocketClient client = new JSPackagerWebSocketClient("ws://not_needed", callback);
    client.onMessage(null,
      "{\"version\": 1, \"target\": \"targetValue\", \"action\": \"actionValue\"}");
    verify(callback).onMessage("targetValue", "actionValue");
  }

  @Test
  public void test_onMessage_WithInvalidContentType_ShouldNotTriggerCallback() throws IOException {
    final JSPackagerWebSocketClient.JSPackagerCallback callback =
      mock(JSPackagerWebSocketClient.JSPackagerCallback.class);
    final JSPackagerWebSocketClient client = new JSPackagerWebSocketClient("ws://not_needed", callback);
    client.onMessage(null,
            ByteString.encodeUtf8("{\"version\": 1, \"target\": \"targetValue\", \"action\": \"actionValue\"}"));
    verify(callback, never()).onMessage(anyString(), anyString());
  }

  @Test
  public void test_onMessage_WithoutTarget_ShouldNotTriggerCallback() throws IOException {
    final JSPackagerWebSocketClient.JSPackagerCallback callback =
      mock(JSPackagerWebSocketClient.JSPackagerCallback.class);
    final JSPackagerWebSocketClient client = new JSPackagerWebSocketClient("ws://not_needed", callback);
    client.onMessage(null, "{\"version\": 1, \"action\": \"actionValue\"}");
    verify(callback, never()).onMessage(anyString(), anyString());
  }

  @Test
  public void test_onMessage_With_Null_Target_ShouldNotTriggerCallback() throws IOException {
    final JSPackagerWebSocketClient.JSPackagerCallback callback =
      mock(JSPackagerWebSocketClient.JSPackagerCallback.class);
    final JSPackagerWebSocketClient client = new JSPackagerWebSocketClient("ws://not_needed", callback);
    client.onMessage(null,
      "{\"version\": 1, \"target\": null, \"action\": \"actionValue\"}");
    verify(callback, never()).onMessage(anyString(), anyString());
  }

  @Test
  public void test_onMessage_WithoutAction_ShouldNotTriggerCallback() throws IOException {
    final JSPackagerWebSocketClient.JSPackagerCallback callback =
      mock(JSPackagerWebSocketClient.JSPackagerCallback.class);
    final JSPackagerWebSocketClient client = new JSPackagerWebSocketClient("ws://not_needed", callback);
    client.onMessage(null, "{\"version\": 1, \"target\": \"targetValue\"}");
    verify(callback, never()).onMessage(anyString(), anyString());
  }

  @Test
  public void test_onMessage_With_Null_Action_ShouldNotTriggerCallback() throws IOException {
    final JSPackagerWebSocketClient.JSPackagerCallback callback =
      mock(JSPackagerWebSocketClient.JSPackagerCallback.class);
    final JSPackagerWebSocketClient client = new JSPackagerWebSocketClient("ws://not_needed", callback);
    client.onMessage(null,
      "{\"version\": 1, \"target\": \"targetValue\", \"action\": null}");
    verify(callback, never()).onMessage(anyString(), anyString());
  }

  @Test
  public void test_onMessage_WrongVersion_ShouldNotTriggerCallback() throws IOException {
    final JSPackagerWebSocketClient.JSPackagerCallback callback =
      mock(JSPackagerWebSocketClient.JSPackagerCallback.class);
    final JSPackagerWebSocketClient client = new JSPackagerWebSocketClient("ws://not_needed", callback);
    client.onMessage(null,
      "{\"version\": 2, \"target\": \"targetValue\", \"action\": \"actionValue\"}");
    verify(callback, never()).onMessage(anyString(), anyString());
  }
}
