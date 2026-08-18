(function() {
  'use strict';

  let nextId = 1;
  const instances = {};

  class ShimWebSocket {
    constructor(url, protocols) {
      this._id = nextId++;
      this._url = url;
      this._readyState = 0; // CONNECTING
      this._onopen = null;
      this._onmessage = null;
      this._onclose = null;
      this._onerror = null;

      instances[this._id] = this;

      // Kotlin側に接続要求
      // Android はaddJavascriptInterfaceで公開されたオブジェクト名
      Android.nativeConnect(this._id, url, protocols || '');
    }

    get url() { return this._url; }
    get readyState() { return this._readyState; }

    set onopen(fn) { this._onopen = fn; }
    get onopen() { return this._onopen; }
    set onmessage(fn) { this._onmessage = fn; }
    get onmessage() { return this._onmessage; }
    set onclose(fn) { this._onclose = fn; }
    get onclose() { return this._onclose; }
    set onerror(fn) { this._onerror = fn; }
    get onerror() { return this._onerror; }

    send(data) {
      if (this._readyState !== 1) {
        throw new DOMException('WebSocket is not open', 'InvalidStateError');
      }
      Android.nativeSend(this._id, data);
    }

    close(code, reason) {
      if (this._readyState === 2 || this._readyState === 3) return;
      this._readyState = 2;
      Android.nativeClose(this._id, code || 1000, reason || '');
    }
  }

  ShimWebSocket.CONNECTING = 0;
  ShimWebSocket.OPEN = 1;
  ShimWebSocket.CLOSING = 2;
  ShimWebSocket.CLOSED = 3;

  // Kotlin側からのコールバック受信
  window._wsShim = {
    onNativeOpen: function(id) {
      const ws = instances[id];
      if (!ws) return;
      ws._readyState = 1;
      if (ws._onopen) ws._onopen({ type: 'open' });
    },
    onNativeMessage: function(id, data) {
      const ws = instances[id];
      if (!ws) return;
      if (ws._onmessage) ws._onmessage({ type: 'message', data: data });
    },
    onNativeClose: function(id, code, reason, wasClean) {
      const ws = instances[id];
      if (!ws) return;
      ws._readyState = 3;
      if (ws._onclose) ws._onclose({
        type: 'close',
        code: code,
        reason: reason,
        wasClean: wasClean
      });
      delete instances[id];
    },
    onNativeError: function(id, message) {
      const ws = instances[id];
      if (!ws) return;
      if (ws._onerror) ws._onerror({ type: 'error', message: message });
    }
  };

  window.WebSocket = ShimWebSocket;
})();
