/* The "Copy" button of a new API key. It copies what is in the (read-only) field and sends nothing anywhere.
   Without clipboard support the button stays hidden and the field is selected, so the key can be copied by hand. */
(function () {
  'use strict';

  var button = document.getElementById('copy-secret');
  if (!button) {
    return;
  }
  var source = document.getElementById(button.getAttribute('data-copy-from'));
  var status = document.getElementById('copy-status');
  if (!source) {
    return;
  }

  function say(text) {
    if (status) {
      status.textContent = text;
    }
  }

  source.addEventListener('focus', function () {
    source.select();
  });

  // The sentences are written by the page, in its language (data-i18n-* on the button), so this file holds none.
  function text(name) {
    return button.getAttribute('data-i18n-' + name) || '';
  }

  if (!(navigator.clipboard && window.isSecureContext)) {
    say(text('select'));
    return;
  }

  button.hidden = false;
  button.addEventListener('click', function () {
    navigator.clipboard.writeText(source.value).then(function () {
      say(text('copied'));
    }, function () {
      source.focus();
      source.select();
      say(text('failed'));
    });
  });
})();
