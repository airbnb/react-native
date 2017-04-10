package com.facebook.react.modules.network;

public interface NetworkStateManager {
  boolean isShuttingDown();
  void removeRequest(int requestId);
}
