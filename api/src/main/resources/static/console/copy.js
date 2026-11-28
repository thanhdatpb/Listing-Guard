(() => {
    // Copies the text of an element to the clipboard. The sentences come from the button's data attributes (ADR-028); without a script the text is
    // in a read-only field the person can select.
    const say = (button, text) => {
        const status = document.getElementById('copy-status');
        if (status) {
            status.textContent = text;
        }
    };
    document.addEventListener('click', async (event) => {
        const button = event.target.closest('[data-copy-from]');
        if (!button) {
            return;
        }
        const source = document.getElementById(button.dataset.copyFrom);
        if (!source) {
            return;
        }
        const text = 'value' in source ? source.value : source.textContent;
        try {
            await navigator.clipboard.writeText(text);
            say(button, button.dataset.copied);
        } catch (error) {
            try {
                source.focus();
                source.select();
                say(button, document.execCommand('copy') ? button.dataset.copied : button.dataset.failed);
            } catch (second) {
                say(button, button.dataset.failed);
            }
        }
    });
})();
