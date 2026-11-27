(() => {
    const button = document.getElementById("manager-print-button");
    if (button) {
        button.hidden = false;
        button.addEventListener("click", () => window.print());
    }
})();
