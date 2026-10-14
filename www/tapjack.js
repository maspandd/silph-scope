'use strict';

var exec = require('cordova/exec');
var channel = require('cordova/channel');
var cordova = require('cordova');

var SERVICE = 'TapjackProtection';
var EVENT_NAME = 'tapjackdetected';
var ENABLE_MODES = ['block', 'detect'];

var listeners = [];

function call(action, args) {
    return new Promise(function (resolve, reject) {
        exec(resolve, function (err) {
            reject(err instanceof Error ? err : new Error(String(err)));
        }, SERVICE, action, args || []);
    });
}

function validateOptions(options) {
    if (options === undefined || options === null) {
        return {};
    }
    if (typeof options !== 'object' || Array.isArray(options)) {
        throw new TypeError('tapjackProtection.enable: options must be an object');
    }
    var out = {};
    if (options.mode !== undefined) {
        if (ENABLE_MODES.indexOf(options.mode) === -1) {
            throw new TypeError("tapjackProtection.enable: mode must be 'block' or 'detect' " +
                '(use disable() to turn protection off)');
        }
        out.mode = options.mode;
    }
    ['strict', 'hideOverlays'].forEach(function (key) {
        if (options[key] !== undefined) {
            if (typeof options[key] !== 'boolean') {
                throw new TypeError('tapjackProtection.enable: ' + key + ' must be a boolean');
            }
            out[key] = options[key];
        }
    });
    return out;
}

function dispatch(event) {
    if (!event || typeof event !== 'object') {
        return;
    }
    listeners.slice().forEach(function (listener) {
        try {
            listener(event);
        } catch (e) {
            console.error('[TapjackProtection] onObscuredTouch listener threw:', e);
        }
    });
    cordova.fireDocumentEvent(EVENT_NAME, event);
}

function subscribeNative() {
    exec(dispatch, function (err) {
        console.warn('[TapjackProtection] obscured-touch events unavailable:', err);
    }, SERVICE, 'subscribe', []);
}

channel.onCordovaReady.subscribe(subscribeNative);

module.exports = {
    /**
     * Turns protection on, or changes its options. Options left out keep their current value;
     * mode defaults to 'block' when protection is off.
     * @param {{mode?: 'block'|'detect', strict?: boolean, hideOverlays?: boolean}} [options]
     * @returns {Promise<Object>} the resulting status
     */
    enable: function (options) {
        var opts;
        try {
            opts = validateOptions(options);
        } catch (e) {
            return Promise.reject(e);
        }
        return call('enable', [opts]);
    },

    /** @returns {Promise<Object>} the resulting status */
    disable: function () {
        return call('disable');
    },

    /** @returns {Promise<Object>} the current status */
    getStatus: function () {
        return call('getStatus');
    },

    /**
     * Called with {partial, blocked, timestamp} when a touch arrives while another window
     * covers the app. The same data is also fired as the 'tapjackdetected' document event.
     * @param {function(Object): void} listener
     * @returns {function(): void} call to unsubscribe
     */
    onObscuredTouch: function (listener) {
        if (typeof listener !== 'function') {
            throw new TypeError('tapjackProtection.onObscuredTouch: listener must be a function');
        }
        listeners.push(listener);
        return function unsubscribe() {
            var index = listeners.indexOf(listener);
            if (index !== -1) {
                listeners.splice(index, 1);
            }
        };
    }
};
