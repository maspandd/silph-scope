'use strict';

var path = require('path');

var MODULE_PATH = path.join(__dirname, '..', '..', 'www', 'tapjack.js');

function loadPlugin() {
    var calls = [];
    var readyHandlers = [];
    var firedEvents = [];

    jest.resetModules();
    jest.doMock('cordova/exec', function () {
        return function (success, fail, service, action, args) {
            calls.push({ success: success, fail: fail, service: service, action: action, args: args });
        };
    }, { virtual: true });
    jest.doMock('cordova/channel', function () {
        return { onCordovaReady: { subscribe: function (fn) { readyHandlers.push(fn); } } };
    }, { virtual: true });
    jest.doMock('cordova', function () {
        return { fireDocumentEvent: function (name, data) { firedEvents.push({ name: name, data: data }); } };
    }, { virtual: true });

    var plugin = require(MODULE_PATH);
    return { plugin: plugin, calls: calls, readyHandlers: readyHandlers, firedEvents: firedEvents };
}

function lastCall(ctx) {
    return ctx.calls[ctx.calls.length - 1];
}

describe('tapjackProtection', function () {
    var ctx;

    beforeEach(function () {
        ctx = loadPlugin();
    });

    describe('enable', function () {
        it('sends an empty options object by default', function () {
            ctx.plugin.enable();
            expect(lastCall(ctx)).toMatchObject({ service: 'TapjackProtection', action: 'enable', args: [{}] });
        });

        it('passes through only the known options', function () {
            ctx.plugin.enable({ mode: 'detect', strict: true, hideOverlays: false, extra: 1 });
            expect(lastCall(ctx).args).toEqual([{ mode: 'detect', strict: true, hideOverlays: false }]);
        });

        it('resolves with the native status', async function () {
            var promise = ctx.plugin.enable({ mode: 'block' });
            lastCall(ctx).success({ supported: true, mode: 'block' });
            await expect(promise).resolves.toEqual({ supported: true, mode: 'block' });
        });

        it('rejects with an Error when native fails', async function () {
            var promise = ctx.plugin.enable();
            lastCall(ctx).fail('boom');
            await expect(promise).rejects.toThrow('boom');
        });

        it.each([
            [{ mode: 'off' }, /mode must be/],
            [{ mode: 'BLOCK' }, /mode must be/],
            [{ strict: 'yes' }, /strict must be a boolean/],
            [{ hideOverlays: 1 }, /hideOverlays must be a boolean/],
            ['block', /options must be an object/],
            [[], /options must be an object/]
        ])('rejects invalid options %p without calling native', async function (options, message) {
            await expect(ctx.plugin.enable(options)).rejects.toThrow(message);
            expect(ctx.calls).toHaveLength(0);
        });
    });

    it('disable and getStatus call native with no args', function () {
        ctx.plugin.disable();
        expect(lastCall(ctx)).toMatchObject({ action: 'disable', args: [] });
        ctx.plugin.getStatus();
        expect(lastCall(ctx)).toMatchObject({ action: 'getStatus', args: [] });
    });

    describe('obscured-touch events', function () {
        function subscribeCall() {
            expect(ctx.readyHandlers).toHaveLength(1);
            ctx.readyHandlers[0]();
            var call = lastCall(ctx);
            expect(call.action).toBe('subscribe');
            return call;
        }

        it('subscribes to native once cordova is ready', function () {
            expect(ctx.calls).toHaveLength(0);
            subscribeCall();
        });

        it('fans events out to listeners and the document event', function () {
            var sub = subscribeCall();
            var a = jest.fn();
            var b = jest.fn();
            ctx.plugin.onObscuredTouch(a);
            ctx.plugin.onObscuredTouch(b);

            var event = { partial: false, blocked: true, timestamp: 1 };
            sub.success(event);

            expect(a).toHaveBeenCalledWith(event);
            expect(b).toHaveBeenCalledWith(event);
            expect(ctx.firedEvents).toEqual([{ name: 'tapjackdetected', data: event }]);
        });

        it('stops calling a listener after unsubscribe', function () {
            var sub = subscribeCall();
            var listener = jest.fn();
            var unsubscribe = ctx.plugin.onObscuredTouch(listener);
            unsubscribe();
            unsubscribe();
            sub.success({ partial: true, blocked: false, timestamp: 2 });
            expect(listener).not.toHaveBeenCalled();
        });

        it('keeps notifying other listeners when one throws', function () {
            var sub = subscribeCall();
            var spy = jest.spyOn(console, 'error').mockImplementation(function () {});
            var good = jest.fn();
            ctx.plugin.onObscuredTouch(function () { throw new Error('bad listener'); });
            ctx.plugin.onObscuredTouch(good);

            sub.success({ partial: false, blocked: true, timestamp: 3 });

            expect(good).toHaveBeenCalled();
            expect(spy).toHaveBeenCalled();
            spy.mockRestore();
        });

        it('ignores empty native results', function () {
            var sub = subscribeCall();
            var listener = jest.fn();
            ctx.plugin.onObscuredTouch(listener);
            sub.success(undefined);
            sub.success('OK');
            expect(listener).not.toHaveBeenCalled();
            expect(ctx.firedEvents).toHaveLength(0);
        });

        it('only warns when the event channel fails', function () {
            var sub = subscribeCall();
            var spy = jest.spyOn(console, 'warn').mockImplementation(function () {});
            sub.fail('Class not found');
            expect(spy).toHaveBeenCalledWith(expect.stringContaining('unavailable'), 'Class not found');
            spy.mockRestore();
        });

        it('rejects a non-function listener', function () {
            expect(function () { ctx.plugin.onObscuredTouch('nope'); }).toThrow(TypeError);
        });
    });
});
