/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.uimanager;

import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.CheckBox;
import android.widget.Switch;

import com.facebook.react.bridge.Dynamic;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableType;

/**
 * Helper class containing logic for setting accessibility View properties.
 */
/* package */ class AccessibilityHelper {

  static final String BUTTON = "button";
  static final String RADIOBUTTON = "radiobutton";
  static final String CHECKBOX = "checkbox";
  static final String SWITCH = "switch";
  static final String CHECKED = "checked";
  static final String DISABLED = "disabled";
  static final String RADIOBUTTON_CHECKED = "radiobutton_checked";
  static final String RADIOBUTTON_UNCHECKED = "radiobutton_unchecked";

  public static void updateAccessibilityComponentType(View view, Dynamic dynamic) {
    AccessibilityDelegate delegate = new AccessibilityDelegate();

    if (dynamic.getType() == ReadableType.String) {
      String prop = dynamic.asString();
      switch (prop) {
        case RADIOBUTTON_CHECKED:
          delegate.setComponentType(RADIOBUTTON);
          delegate.setComponentType(CHECKED);
          break;
        case RADIOBUTTON_UNCHECKED:
          delegate.setComponentType(RADIOBUTTON);
          break;
        default:
          if (!delegate.setComponentType(prop)) {
            delegate = null;
          };
          break;
      }
    } else {
      ReadableArray componentTypeArray = dynamic.asArray();

      for (int i = 0; i < componentTypeArray.size(); i++) {
        String componentType = componentTypeArray.getString(i);
        if (!delegate.setComponentType(componentType)) {
          delegate = null;
          break;
        };
      }
    }

    view.setAccessibilityDelegate(delegate);
  }

  public static void sendAccessibilityEvent(View view, int eventType) {
    view.sendAccessibilityEvent(eventType);
  }
}

class AccessibilityDelegate extends View.AccessibilityDelegate {
  private static final int CHECKABLE = 1;
  private static final int CHECKED = 2;
  private static final int DISABLED = 4;

  private String className = null;
  private int flags = 0;

  boolean setComponentType(String componentType) {
    switch (componentType) {
      case AccessibilityHelper.BUTTON:
        className = Button.class.getName();
        break;
      case AccessibilityHelper.RADIOBUTTON:
        className = RadioButton.class.getName();
        setCheckable();
        break;
      case AccessibilityHelper.CHECKBOX:
        className = CheckBox.class.getName();
        setCheckable();
        break;
      case AccessibilityHelper.SWITCH:
        className = Switch.class.getName();
        setCheckable();
        break;
      case AccessibilityHelper.CHECKED:
        setChecked();
        break;
      case AccessibilityHelper.DISABLED:
        setDisabled();
        break;
      default:
        return false;
    }

    return true;
  }

  private boolean getCheckable() {
    return (flags & CHECKABLE) != 0;
  }

  private void setCheckable() {
    flags |= CHECKABLE;
  }

  private boolean getChecked() {
    return (flags & CHECKED) != 0;
  }

  private void setChecked() {
    flags |= CHECKED;
  }

  private boolean getDisabled() {
    return (flags & DISABLED) != 0;
  }

  private void setDisabled() {
    flags |= DISABLED;
  }

  @Override
  public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
    super.onInitializeAccessibilityEvent(host, event);
    event.setClassName(className);
    event.setChecked(getChecked());
    event.setEnabled(!getDisabled());
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(host, info);
    info.setClassName(className);
    info.setCheckable(getCheckable());
    info.setChecked(getChecked());
    info.setEnabled(!getDisabled());
  }
}

