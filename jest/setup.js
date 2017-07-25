/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */
'use strict';

const mockComponent = require.requireActual('./mockComponent');
const mockNativeModules = require.requireActual('./mockNativeModules');

require.requireActual('metro-bundler/build/Resolver/polyfills/babelHelpers.js');
require.requireActual('metro-bundler/build/Resolver/polyfills/Object.es7.js');
require.requireActual('metro-bundler/build/Resolver/polyfills/error-guard');

global.__DEV__ = true;

global.Promise = require.requireActual('promise');
global.regeneratorRuntime = require.requireActual('regenerator-runtime/runtime');

global.requestAnimationFrame = function(callback) {
  setTimeout(callback, 0);
};
global.cancelAnimationFrame = function(id) {
  clearTimeout(id);
};

jest
  .mock('setupDevtools')
  .mock('npmlog');

// there's a __mock__ for it.
jest.setMock('ErrorUtils', require('ErrorUtils'));

jest
  .mock('InitializeCore')
  .mock('Image', () => mockComponent('Image'))
  .mock('Text', () => mockComponent('Text'))
  .mock('TextInput', () => mockComponent('TextInput'))
  .mock('Modal', () => mockComponent('Modal'))
  .mock('View', () => mockComponent('View'))
  .mock('RefreshControl', () => require.requireMock('RefreshControlMock'))
  .mock('ScrollView', () => require.requireMock('ScrollViewMock'))
  .mock(
    'ActivityIndicator',
    () => mockComponent('ActivityIndicator'),
  )
  .mock('ListView', () => require.requireMock('ListViewMock'))
  .mock('ListViewDataSource', () => {
    const DataSource = require.requireActual('ListViewDataSource');
    DataSource.prototype.toJSON = function() {
      function ListViewDataSource(dataBlob) {
        this.items = 0;
        // Ensure this doesn't throw.
        try {
          Object.keys(dataBlob).forEach(key => {
            this.items += dataBlob[key] && (
              dataBlob[key].length || dataBlob[key].size || 0
            );
          });
        } catch (e) {
          this.items = 'unknown';
        }
      }

      return new ListViewDataSource(this._dataBlob);
    };
    return DataSource;
  })
  .mock('ensureComponentIsNative', () => () => true);

const mockEmptyObject = {};

Object.keys(mockNativeModules).forEach(module => {
  try {
    jest.doMock(module, () => mockNativeModules[module]); // needed by FacebookSDK-test
  } catch (e) {
    jest.doMock(module, () => mockNativeModules[module], {virtual: true});
  }
});

jest
  .doMock('NativeModules', () => mockNativeModules)
  .doMock('ReactNativePropRegistry', () => ({
    register: id => id,
    getByID: () => mockEmptyObject,
  }));

jest.doMock('requireNativeComponent', () => {
  const React = require('react');

  return viewName => props => {
    if (global.__JSDOM_MOUNTABLE__) {
      return React.createElement(
        viewName,
        { children: props.children },
      );
    }
    return React.createElement(
      viewName,
      props,
      props.children,
    );
  };
});
