(() => {
    const filters = document.getElementById('impact-filters');
    const loading = document.getElementById('impact-loading');
    if (filters && loading) {
        filters.addEventListener('submit', () => {
            loading.textContent = loading.dataset.i18nLoading;
        });
        window.addEventListener('pageshow', () => {
            loading.textContent = '';
        });
    }
})();
