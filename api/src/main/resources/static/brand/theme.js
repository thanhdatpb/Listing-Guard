(() => {
  const key = 'listing-guard.console.theme';
  const root = document.documentElement;
  const valid = new Set(['system', 'light', 'dark']);
  let selection = 'system';

  function setTheme(value) {
    selection = valid.has(value) ? value : 'system';
    if (selection === 'system') delete root.dataset.consoleTheme;
    else root.dataset.consoleTheme = selection;
    for (const meta of document.querySelectorAll('meta[name="theme-color"]')) {
      const original = meta.dataset.systemMedia ?? meta.media;
      meta.dataset.systemMedia = original;
      meta.media = selection === 'system' ? original
        : original === `(prefers-color-scheme: ${selection})` ? 'screen' : 'not all';
    }
    for (const select of document.querySelectorAll('[data-console-theme-switch]')) {
      select.value = selection;
      select.closest('[data-console-theme-controls]').hidden = false;
    }
  }

  try { setTheme(localStorage.getItem(key)); } catch { setTheme('system'); }
  document.addEventListener('DOMContentLoaded', () => setTheme(selection), { once: true });
  document.addEventListener('change', (event) => {
    if (!event.target.matches('[data-console-theme-switch]')) return;
    setTheme(event.target.value);
    try { localStorage.setItem(key, selection); } catch { /* Apply it for this page. */ }
  });
  window.addEventListener('storage', (event) => {
    if (event.key === key || event.key === null) setTheme(event.newValue);
  });
})();
