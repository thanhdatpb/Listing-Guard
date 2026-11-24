(() => {
    const draft = document.getElementById('pod-draft');
    const results = document.getElementById('pod-results');
    const loading = document.getElementById('pod-loading');
    const unchecked = document.getElementById('pod-unchecked');

    function markUnchecked(event) {
        if (!results || !event.target.matches('input:not([type="hidden"]),select,textarea')) {
            return;
        }
        // Keep verified fix proposals actionable. The server refuses fixes against a changed source value.
        results.querySelectorAll('form,[data-status]').forEach(element => { element.hidden = true; });
        unchecked.hidden = false;
    }

    draft.addEventListener('input', markUnchecked);
    draft.addEventListener('change', markUnchecked);
    draft.addEventListener('submit', event => {
        if (draft.dataset.submitting) {
            event.preventDefault();
            return;
        }
        draft.dataset.submitting = 'true';
        draft.setAttribute('aria-busy', 'true');
        if (!event.submitter || event.submitter.name !== 'apply') {
            loading.hidden = false;
        }
    });
    window.addEventListener('pageshow', event => {
        delete draft.dataset.submitting;
        draft.removeAttribute('aria-busy');
        loading.hidden = true;
        if (event.persisted && results) {
            results.hidden = true;
            unchecked.hidden = false;
        }
    });
})();
