"""Verify theme selection and print with real assets on an offline Chromium fixture."""
import os
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright

STATIC = Path(__file__).resolve().parents[1] / "api" / "src" / "main" / "resources" / "static"
FIXTURE = """<!doctype html><html lang="en"><head>
<meta name="theme-color" content="light" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="dark" media="(prefers-color-scheme: dark)">
<script src="/brand/theme.js"></script><link rel="stylesheet" href="/brand/tokens.css">
<link rel="stylesheet" href="/console/app.css">
<script defer src="/brand/console-interactions.js"></script></head><body>
<div data-console-theme-controls hidden><label for="console-theme">Appearance</label>
<select id="console-theme" data-console-theme-switch>
<option value="system">System</option><option value="light">Light</option><option value="dark">Dark</option>
</select></div><p id="console-working" hidden>Working…</p>
<form id="fixture-form" data-console-submit action="/" method="post" target="transport">
<input name="payload" value="sample">
<button type="submit" name="choice" value="saved">Save</button></form><button form="fixture-form" type="submit">Recheck</button><iframe name="transport" hidden></iframe>
</body></html>"""


def main():
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=os.environ.get("LG_CHROMIUM_PATH"))
        context = browser.new_context(color_scheme="dark")
        payloads = []

        def respond(route):
            path = urlparse(route.request.url).path
            if path == "/":
                if route.request.method == "POST":
                    payloads.append(route.request.post_data)
                    route.fulfill(body="done")
                else:
                    route.fulfill(content_type="text/html; charset=utf-8", body=FIXTURE)
                return
            assets = {"/brand/theme.js", "/brand/tokens.css", "/console/app.css", "/brand/console-interactions.js"}
            if path in assets:
                route.fulfill(path=str(STATIC / path.lstrip("/")))
            else:
                route.abort()

        context.route("**/*", respond)
        page = context.new_page()
        page.goto("http://fixture.test/")
        root = page.locator("html")
        switch = page.locator("#console-theme")
        background = lambda: page.locator("body").evaluate("(body) => getComputedStyle(body).backgroundColor")
        expect(switch).to_have_value("system")
        system_dark = background()
        switch.select_option("light")
        expect(root).to_have_attribute("data-console-theme", "light")
        explicit_light = background()
        assert explicit_light != system_dark
        page.reload()
        expect(switch).to_have_value("light")
        expect(root).to_have_attribute("data-console-theme", "light")
        assert background() == explicit_light
        page.emulate_media(color_scheme="light")
        switch.select_option("dark")
        assert background() == system_dark
        expect(root).to_have_attribute("data-console-theme", "dark")
        page.emulate_media(media="print", color_scheme="dark")
        assert background() == explicit_light
        page.emulate_media(media="screen", color_scheme="dark")
        switch.select_option("system")
        assert root.get_attribute("data-console-theme") is None
        assert background() == system_dark
        assert page.evaluate("localStorage.getItem('listing-guard.console.theme')") == "system"
        page.evaluate("localStorage.setItem('listing-guard.console.theme', 'invalid')")
        page.reload()
        expect(switch).to_have_value("system")

        page.get_by_role("button", name="Save").click()
        expect(page.locator("form")).to_have_attribute("aria-busy", "true")
        expect(page.get_by_role("button", name="Save")).to_be_disabled()
        expect(page.get_by_role("button", name="Recheck")).to_be_disabled()
        expect(page.get_by_role("status")).to_have_text("Working…")
        assert payloads == ["payload=sample&choice=saved"], payloads
        page.evaluate("window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))")
        expect(page.get_by_role("button", name="Save")).to_be_enabled()
        expect(page.get_by_role("status")).to_have_count(0)
        page.evaluate("""() => {
            window.fixtureXHR = new EventTarget();
            document.dispatchEvent(new CustomEvent('htmx:beforeRequest', {cancelable: true, detail: {
                elt: document.querySelector('form button'), xhr: window.fixtureXHR, requestConfig: {verb: 'post'}
            }}));
        }""")
        expect(page.get_by_role("status")).to_have_text("Working…")
        expect(page.get_by_role("button", name="Save")).to_be_disabled()
        expect(page.get_by_role("button", name="Recheck")).to_be_disabled()
        page.evaluate("window.fixtureXHR.dispatchEvent(new Event('loadend'))")
        expect(page.get_by_role("button", name="Save")).to_be_enabled()
        expect(page.get_by_role("status")).to_have_count(0)
        browser.close()
    print("Theme, print palette, and native form loading verified.")


if __name__ == "__main__":
    main()
