(() => {
  'use strict';

  const form = document.getElementById('edit-form');
  const output = document.getElementById('edit-output');
  const unchecked = document.getElementById('edit-unchecked');
  const error = document.getElementById('edit-request-error');
  const submit = form.querySelector('button[type=submit]');
  const draft = form.elements.after;
  let revision = 0;
  let pending = false;
  const snapshot = () => [form.elements.marketplace.value, form.elements.before.value, draft.value].join('\u0000');
  let checked = snapshot();

  function invalidate() {
    revision++;
    unchecked.hidden = false;
    output.replaceChildren();
    error.textContent = '';
  }

  function revealFixes() {
    output.querySelectorAll('[data-draft-fix]').forEach(button => { button.hidden = false; });
  }

  form.addEventListener('input', invalidate);
  output.addEventListener('click', event => {
    const button = event.target.closest('[data-draft-fix]');
    if (!button) return;
    if (checked !== snapshot()) {
      invalidate();
      return;
    }
    const { field, value, action, proposed } = button.dataset;
    let listing;
    try {
      listing = JSON.parse(draft.value);
    } catch {
      invalidate();
      return;
    }
    const normalized = text => typeof text === 'string' ? text.trim().normalize('NFC') : null;
    let applied = false;
    if (field === 'title' && action === 'REPLACE' && normalized(listing.title) === value) {
      listing.title = proposed;
      applied = true;
    }
    const tag = /^tags\[(\d+)]$/.exec(field);
    if (tag && Array.isArray(listing.tags)) {
      const indices = listing.tags.flatMap((text, index) => normalized(text) ? [index] : []);
      const index = indices[Number(tag[1])];
      if (normalized(listing.tags[index]) === value) {
        if (action === 'REMOVE') listing.tags.splice(index, 1);
        else if (action === 'REPLACE') listing.tags[index] = proposed;
        applied = action === 'REMOVE' || action === 'REPLACE';
      }
    }
    if (applied) {
      draft.value = JSON.stringify(listing, null, 2);
      invalidate();
      draft.focus();
    }
  });

  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (pending) return;
    invalidate();
    const submitted = revision;
    const submittedFields = snapshot();
    const body = new URLSearchParams(new FormData(form));
    pending = true;
    submit.disabled = true;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 30000);
    try {
      const response = await fetch(form.action, { method: 'POST', body, signal: controller.signal });
      if (!response.ok || response.redirected) throw new Error();
      const parsed = new DOMParser().parseFromString(await response.text(), 'text/html');
      const result = parsed.getElementById('edit-output');
      if (!result) throw new Error();
      if (submitted !== revision || submittedFields !== snapshot()) return;
      checked = submittedFields;
      output.replaceChildren(...result.childNodes);
      unchecked.hidden = Boolean(output.querySelector('#edit-result'));
      revealFixes();
      if (window.htmx) window.htmx.process(output);
    } catch {
      if (submitted === revision) error.textContent = form.dataset.i18nRequestError;
    } finally {
      clearTimeout(timer);
      pending = false;
      submit.disabled = false;
    }
  });

  revealFixes();
})();
