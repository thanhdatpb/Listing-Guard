/* The bulk check page (ADR-029). Two small conveniences, both optional: the status filter and the "copy" button for the column that
   is pasted back into a Sheet. Without this script every row shows and the same text sits in a read-only box that can be selected by
   hand. It sends nothing anywhere and holds no text of its own: the sentences come from the page (data-i18n-*, ADR-028). */
(function () {
  'use strict';

  var filters = document.getElementById('status-filters');
  var table = document.getElementById('bulk-results');
  var rows = table ? Array.prototype.filter.call(table.getElementsByTagName('tr'), function (row) {
    return row.hasAttribute('data-group');
  }) : [];
  if (filters && rows.length) {
    var buttons = filters.querySelectorAll('button[data-filter]');
    filters.hidden = false;
    Array.prototype.forEach.call(buttons, function (button) {
      button.addEventListener('click', function () {
        var wanted = button.getAttribute('data-filter');
        Array.prototype.forEach.call(buttons, function (other) {
          other.setAttribute('aria-pressed', other === button ? 'true' : 'false');
        });
        Array.prototype.forEach.call(rows, function (row) {
          row.hidden = wanted !== 'ALL' && row.getAttribute('data-group') !== wanted;
        });
      });
    });
  }

  var copy = document.getElementById('copy-sheet');
  var status = document.getElementById('copy-status');
  var source = copy ? document.getElementById(copy.getAttribute('data-copy-from')) : null;
  if (!copy || !source) {
    return;
  }

  // The sentences are written by the page, in its language (data-i18n-* on the button); a pattern has {0} for a number.
  function sentence(name, value) {
    var pattern = copy.getAttribute('data-i18n-' + name) || '';
    return pattern.replace('{0}', String(value));
  }

  function say(text) {
    if (status) {
      status.textContent = text;
    }
  }

  function lines() {
    return source.value.split('\n').length;
  }

  function selectByHand() {
    var box = source.closest('details');
    if (box) {
      box.open = true;
    }
    source.focus();
    source.select();
  }

  copy.hidden = false;
  copy.addEventListener('click', function () {
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(source.value).then(function () {
        say(sentence('copied', lines()));
      }, function () {
        selectByHand();
        say(sentence('failed', ''));
      });
      return;
    }
    selectByHand();
    var copied = false;
    try {
      copied = document.execCommand('copy');
    } catch (e) {
      copied = false;
    }
    say(copied ? sentence('copied', lines()) : sentence('failed', ''));
  });
})();
