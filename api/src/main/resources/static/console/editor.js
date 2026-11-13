/*
 * Live counters for the console editor. Counting only: it reads the fields, writes the text of the counter next to each,
 * and sends nothing anywhere. The limits come from the page (data-* attributes the server read from the active ruleset);
 * there is no number in this file. Without JavaScript the counters keep the static "Limit: ..." text the server wrote.
 * Lengths are in code points, like the rules (so an emoji counts once); search terms are in UTF-8 bytes.
 */
(function () {
  'use strict';

  var encoder = typeof TextEncoder === 'function' ? new TextEncoder() : null;

  function codePoints(text) {
    return Array.from(text).length;
  }

  function utf8Bytes(text) {
    return encoder ? encoder.encode(text).length : text.length;
  }

  function entries(text) {
    return text.split(/\r\n|\r|\n/).map(function (line) { return line.trim(); }).filter(function (line) { return line.length > 0; });
  }

  function number(value) {
    var parsed = parseInt(value, 10);
    return isNaN(parsed) ? null : parsed;
  }

  // The sentences come from the page (data-i18n-* on the form, written from the messages of the page's language), so this file
  // holds none. A sentence is a pattern with {0}, {1}; without the attribute only the numbers are shown.
  function sentence(name, values) {
    var form = document.getElementById('editor-form');
    var pattern = form ? form.getAttribute('data-i18n-' + name) : null;
    if (!pattern) {
      return values.join(' / ');
    }
    return pattern.replace(/\{(\d+)\}/g, function (match, index) {
      var value = values[Number(index)];
      return value === undefined ? match : String(value);
    });
  }

  function show(counter, text, over) {
    counter.textContent = over ? sentence('over', [text]) : text;
    counter.classList.toggle('over', over);
  }

  function update(counter, field) {
    var mode = counter.getAttribute('data-mode');
    var value = field.value;
    if (mode === 'chars' || mode === 'bytes') {
      var limit = number(counter.getAttribute('data-limit'));
      var used = mode === 'bytes' ? utf8Bytes(value.trim()) : codePoints(value.trim());
      var text = limit === null ? sentence(mode + '-nolimit', [used]) : sentence(mode, [used, limit]);
      show(counter, text, limit !== null && used > limit);
    } else if (mode === 'list') {
      var limitCount = number(counter.getAttribute('data-limit-count'));
      var limitEach = number(counter.getAttribute('data-limit-each'));
      var items = entries(value);
      var longest = items.reduce(function (max, item) { return Math.max(max, codePoints(item)); }, 0);
      var summary = limitCount === null ? sentence('entries-nolimit', [items.length]) : sentence('entries', [items.length, limitCount]);
      if (limitEach !== null) {
        summary += '; ' + sentence('longest', [longest, limitEach]);
      }
      show(counter, summary, (limitCount !== null && items.length > limitCount) || (limitEach !== null && longest > limitEach));
    }
  }

  function init() {
    var counters = document.querySelectorAll('[data-counter-for]');
    Array.prototype.forEach.call(counters, function (counter) {
      var field = document.getElementById(counter.getAttribute('data-counter-for'));
      if (!field) {
        return;
      }
      var refresh = function () { update(counter, field); };
      field.addEventListener('input', refresh);
      refresh();
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
