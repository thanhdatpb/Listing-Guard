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

  if (!(navigator.clipboard && window.isSecureContext)) {
    say('Select the key above and copy it.');
    return;
  }

  button.hidden = false;
  button.addEventListener('click', function () {
    navigator.clipboard.writeText(source.value).then(function () {
      say('Copied.');
    }, function () {
      source.focus();
      source.select();
      say('Could not copy. Select the key above and copy it.');
    });
  });
})();
