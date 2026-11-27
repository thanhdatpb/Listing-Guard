(() => {
  const working = document.getElementById('console-working');

  const active = new Map();
  const requests = new Map();

  function start(container) {
    if (!working || active.has(container)) return false;
    const buttons = [...container.elements]
      .filter(button => button.type === 'submit' && !button.disabled);
    const status = document.createElement('p');
    status.className = 'lg-working';
    status.setAttribute('role', 'status');
    container.after(status);
    container.setAttribute('aria-busy', 'true');
    const timer = window.setTimeout(() => {
      status.textContent = working.textContent;
      for (const button of buttons) button.disabled = true;
    }, 0);
    active.set(container, { buttons, status, timer });
    return true;
  }

  function finish(container) {
    const pending = active.get(container);
    if (!pending) return;
    window.clearTimeout(pending.timer);
    pending.status.remove();
    container.removeAttribute('aria-busy');
    for (const button of pending.buttons) button.disabled = false;
    active.delete(container);
  }

  document.addEventListener('submit', (event) => {
    if (event.defaultPrevented || !event.target.matches('form[data-console-submit]')) return;
    if (!start(event.target)) event.preventDefault();
  });

  document.addEventListener('htmx:beforeRequest', (event) => {
    const { elt, xhr, requestConfig } = event.detail;
    if (requestConfig?.verb !== 'post') return;
    const form = elt.form ?? elt.closest('form');
    if (!form) return;
    if (start(form)) requests.set(xhr, form);
    else event.preventDefault();
    xhr.addEventListener('loadend', () => {
      if (requests.delete(xhr)) finish(form);
    }, { once: true });
  });

  window.addEventListener('pageshow', (event) => {
    if (event.persisted) for (const form of active.keys()) finish(form);
  });

  function focusScrollableTables() {
    for (const wrapper of document.querySelectorAll('.lg-table-wrap')) {
      if (wrapper.scrollHeight > wrapper.clientHeight || wrapper.scrollWidth > wrapper.clientWidth) {
        wrapper.tabIndex = 0;
        const caption = wrapper.querySelector('caption');
        if (caption?.textContent.trim()) {
          wrapper.setAttribute('role', 'region');
          wrapper.setAttribute('aria-label', caption.textContent.trim());
        }
      } else {
        wrapper.removeAttribute('tabindex');
        wrapper.removeAttribute('aria-label');
        wrapper.removeAttribute('role');
      }
    }
  }

  focusScrollableTables();
  window.addEventListener('resize', focusScrollableTables);
  document.addEventListener('htmx:afterSwap', focusScrollableTables);
})();
