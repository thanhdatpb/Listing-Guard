/* The bulk check page (ADR-029). Two small conveniences, both optional: the status filter and the "copy" button for the column that
   is pasted back into a Sheet. Without this script every row shows and the same text sits in a read-only box that can be selected by
   hand. It sends nothing anywhere and holds no text of its own about a listing. */
(function () {
  'use strict';

  var filters = document.getElementById('status-filters');
  var rows = document.querySelectorAll('#bulk-results tbody tr[data-group]');
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
        say('Đã sao chép ' + lines() + ' dòng. Dán vào ô đầu tiên của hai cột trống trong Sheet.');
      }, function () {
        selectByHand();
        say('Không sao chép được. Chọn nội dung trong ô bên dưới rồi sao chép thủ công.');
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
    say(copied ? 'Đã sao chép ' + lines() + ' dòng. Dán vào ô đầu tiên của hai cột trống trong Sheet.'
      : 'Không sao chép được. Chọn nội dung trong ô bên dưới rồi sao chép thủ công.');
  });
})();
