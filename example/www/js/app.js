'use strict';

document.addEventListener('deviceready', function () {
    var tapjack = cordova.plugins.tapjackProtection;
    var count = 0;

    function show(status) {
        document.getElementById('status').textContent = JSON.stringify(status, null, 2);
    }

    function fail(err) {
        document.getElementById('status').textContent = 'Error: ' + err.message;
    }

    document.getElementById('tap').addEventListener('click', function () {
        count += 1;
        document.getElementById('count').textContent = String(count);
    });

    Array.prototype.forEach.call(document.querySelectorAll('[data-mode]'), function (button) {
        button.addEventListener('click', function () {
            tapjack.enable({ mode: button.dataset.mode, strict: button.dataset.strict === 'true' })
                .then(show, fail);
        });
    });

    document.getElementById('disable').addEventListener('click', function () {
        tapjack.disable().then(show, fail);
    });

    tapjack.onObscuredTouch(function (event) {
        var item = document.createElement('li');
        item.textContent = new Date(event.timestamp).toLocaleTimeString() +
            (event.partial ? ' partial' : ' full') + (event.blocked ? ', blocked' : ', allowed');
        document.getElementById('log').prepend(item);
        document.getElementById('warning').hidden = !event.blocked;
    });

    tapjack.getStatus().then(show, fail);
}, false);
