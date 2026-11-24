(() => {
    const filters = document.getElementById('review-filters');
    const loading = document.getElementById('review-loading');
    if (!filters || !loading) {
        return;
    }
    const announce = () => {
        loading.textContent = loading.dataset.i18nLoading;
    };
    filters.addEventListener('submit', announce);
    document.getElementById('review-next')?.addEventListener('click', (event) => {
        if (!event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) {
            announce();
        }
    });
    window.addEventListener('pageshow', () => {
        loading.textContent = '';
    });
})();
