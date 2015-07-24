/*
 *
 * Copyright 2015, Google Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

'use strict';

var assert = require('assert');
var grpc = require('bindings')('grpc.node');

describe('server', function() {
  describe('constructor', function() {
    it('should work with no arguments', function() {
      assert.doesNotThrow(function() {
        new grpc.Server();
      });
    });
    it('should work with an empty list argument', function() {
      assert.doesNotThrow(function() {
        new grpc.Server([]);
      });
    });
  });
  describe('addHttp2Port', function() {
    var server;
    before(function() {
      server = new grpc.Server();
    });
    it('should bind to an unused port', function() {
      var port;
      assert.doesNotThrow(function() {
        port = server.addHttp2Port('0.0.0.0:0');
      });
      assert(port > 0);
    });
  });
  describe('addSecureHttp2Port', function() {
    var server;
    before(function() {
      server = new grpc.Server();
    });
    it('should bind to an unused port with fake credentials', function() {
      var port;
      var creds = grpc.ServerCredentials.createFake();
      assert.doesNotThrow(function() {
        port = server.addSecureHttp2Port('0.0.0.0:0', creds);
      });
      assert(port > 0);
    });
  });
  describe('listen', function() {
    var server;
    before(function() {
      server = new grpc.Server();
      server.addHttp2Port('0.0.0.0:0');
    });
    after(function() {
      server.shutdown();
    });
    it('should listen without error', function() {
      assert.doesNotThrow(function() {
        server.start();
      });
    });
  });
});
