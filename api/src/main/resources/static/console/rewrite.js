/*
 * The copy buttons of the rewrite suggestions (ADR-037). Pressing one puts the proposed text on the clipboard; the same press posts
 * "copied" to the server through htmx (a form), which is how the use-rate is counted. The script holds no sentence: the word shown after
 * a copy is the data-copied attribute the page wrote in its own language. It reads and sends nothing else.
 */
(function () {
  'use strict';

  function fallbackCopy(text) {
    var area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    try {
      document.execCommand('copy');
    } catch (e) {
      /* nothing to do: the text is on the page for the seller to select */
    }
    document.body.removeChild(area);
  }

  function copied(button) {
    var word = button.getAttribute('data-copied');
    if (!word) {
      return;
    }
    var before = button.textContent;
    button.textContent = word;
    window.setTimeout(function () { button.textContent = before; }, 2000);
  }

  document.addEventListener('click', function (event) {
    var target = event.target;
    var button = target && target.closest ? target.closest('[data-copy-text]') : null;
    if (!button) {
      return;
    }
    var text = button.getAttribute('data-copy-text');
    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
      navigator.clipboard.writeText(text).then(function () { copied(button); }, function () { fallbackCopy(text); });
    } else {
      fallbackCopy(text);
      copied(button);
    }
  });
})();
