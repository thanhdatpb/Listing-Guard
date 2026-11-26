(function () {
  'use strict';
  var form = document.getElementById('phrase-run');
  var run = document.getElementById('phrase-submit');
  if (form && run) {
    form.querySelectorAll('[data-phrase-mapping]').forEach(function (select) {
      select.addEventListener('change', function () {
        run.disabled = true;
        var counts = document.getElementById('phrase-counts');
        if (counts) counts.hidden = true;
      });
    });
    form.addEventListener('submit', function (event) {
      if (event.submitter === run) {
        if (form.getAttribute('aria-busy') === 'true') {
          event.preventDefault();
          return;
        }
        form.setAttribute('aria-busy', 'true');
        run.disabled = true;
        document.getElementById('phrase-run-status').textContent = form.dataset.busy;
      }
    });
    window.addEventListener('pageshow', function (event) {
      if (event.persisted && form.getAttribute('aria-busy') === 'true') {
        form.removeAttribute('aria-busy');
        run.disabled = false;
        document.getElementById('phrase-run-status').textContent = '';
      }
    });
  }

  var copy = document.getElementById('phrase-copy');
  if (!copy) return;
  copy.addEventListener('click', async function () {
    var status = document.getElementById('phrase-copy-status');
    var fallback = document.getElementById('phrase-copy-fallback');
    copy.disabled = true;
    try {
      fallback.hidden = true;
      fallback.value = '';
      var response = await fetch(copy.dataset.url, {cache: 'no-store'});
      if (!response.ok) {
        status.textContent = response.status === 410 ? copy.dataset.expired : copy.dataset.failed;
        return;
      }
      var value = await response.text();
      try {
        await navigator.clipboard.writeText(value);
        status.textContent = copy.dataset.copied;
      } catch (_) {
        fallback.hidden = false;
        fallback.value = value;
        fallback.focus();
        fallback.select();
        status.textContent = copy.dataset.failed;
      }
    } catch (_) {
      status.textContent = copy.dataset.failed;
    } finally {
      copy.disabled = false;
    }
  });
}());
