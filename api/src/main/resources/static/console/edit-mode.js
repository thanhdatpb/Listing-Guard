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
  let policyRequest;
  const snapshot = () => [form.elements.marketplace.value, form.elements.before.value, draft.value].join('\u0000');
  let checked = snapshot();

  function invalidate() {
    revision++;
    policyRequest?.abort();
    unchecked.hidden = false;
    output.replaceChildren();
    error.textContent = '';
  }

  function revealFixes() {
    output.querySelectorAll('[data-draft-fix],[data-policy-panel]').forEach(control => { control.hidden = false; });
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

  output.addEventListener('click', async event => {
    const button = event.target.closest('[data-policy-explain]');
    if (!button || policyRequest || checked !== snapshot()) return;
    const panel = button.closest('[data-policy-panel]');
    const status = panel.querySelector('[data-policy-status]');
    const evidence = panel.querySelector('[data-policy-evidence]');
    const submitted = revision;
    const controller = new AbortController();
    policyRequest = controller;
    button.disabled = true;
    evidence.replaceChildren();
    status.textContent = panel.dataset.i18nLoading;
    const timer = setTimeout(() => controller.abort(), 45000);
    try {
      const response = await fetch(panel.dataset.policyUrl, {
        method: 'POST',
        signal: controller.signal,
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': form.elements._csrf.value },
        body: JSON.stringify({
          marketplace: form.elements.marketplace.value,
          language: document.documentElement.lang === 'vi' ? 'vi' : 'en',
          listing: JSON.parse(draft.value)
        })
      });
      if (!response.ok || response.redirected) throw new Error();
      const result = await response.json();
      if (submitted !== revision || checked !== snapshot()) return;
      const analysis = result.analysis;
      const field = button.dataset.field.replace(/\[\d+]$/, '');
      const matching = analysis.findings.filter(item => item.field === field);
      if (['LLM_TIMEOUT', 'ANALYZER_TIMEOUT'].includes(analysis.reason)) {
        status.textContent = panel.dataset.i18nTimeout;
      } else if (['LLM_ERROR', 'LLM_INVALID_OUTPUT', 'LLM_REFUSED', 'LLM_TRUNCATED', 'RETRIEVAL_FAILED'].includes(analysis.reason)) {
        status.textContent = panel.dataset.i18nError;
      } else if (matching.length === 0) {
        status.textContent = panel.dataset.i18nInsufficient;
      } else {
        status.textContent = analysis.status === 'INCOMPLETE' ? panel.dataset.i18nPartial : panel.dataset.i18nReady;
        appendText(evidence, 'p', panel.dataset.i18nAnalyzed + ' ' + result.analyzedAt);
        for (const item of matching) {
          appendText(evidence, 'p', item.message);
          appendText(evidence, 'blockquote', item.citation.quote);
          appendText(evidence, 'p', panel.dataset.i18nVersion + ' ' + item.citation.version);
          try {
            const source = new URL(item.citation.url);
            if (source.protocol === 'https:' && source.hostname === 'www.etsy.com' && !source.username && !source.password) {
              const link = appendText(evidence, 'a', panel.dataset.i18nSource);
              link.href = source.href;
            }
          } catch {
            // A malformed URL provides no link.
          }
        }
      }
    } catch {
      if (submitted === revision) {
        status.textContent = controller.signal.aborted ? panel.dataset.i18nTimeout : panel.dataset.i18nError;
      }
    } finally {
      clearTimeout(timer);
      policyRequest = undefined;
      button.disabled = false;
    }
  });

  function appendText(parent, tag, value) {
    const element = document.createElement(tag);
    element.textContent = value;
    parent.append(element);
    return element;
  }

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
