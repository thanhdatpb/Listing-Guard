"""Exercise the POD draft UI with Chromium and a synthetic Console response."""

import os
from pathlib import Path
from urllib.parse import parse_qs

from playwright.sync_api import expect, sync_playwright

assets = Path(__file__).resolve().parents[1] / "api/src/main/resources/static/console"
base = "http://pod.test"
markup = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="/app.css">
<script src="/pod-readiness.js" defer></script>
</head>
<body><main class="lg-page"><div class="lg-split lg-split--even">
<section class="lg-card">
<form id="pod-draft" method="post" action="/pod" autocomplete="off">
<input type="hidden" name="_csrf" value="synthetic">
<label for="title">Title</label><input id="title" class="lg-input" name="title" value="Old draft">
<button type="submit" class="lg-btn">Check again</button>
</form>
</section>
<section class="lg-card">
<p id="pod-loading" role="status" hidden>Checking</p>
<p id="pod-unchecked" role="status" hidden>Draft unchecked</p>
<div id="pod-results">
<p class="lg-code">aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</p>
<p data-status="INCOMPLETE">Incomplete</p>
<form action="/review" method="post"><button type="submit">Open review</button></form>
</div>
</section>
</div></main></body></html>
"""

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(executable_path=os.environ.get("LG_CHROMIUM_PATH"))
    page = browser.new_page(viewport={"width": 360, "height": 740}, color_scheme="dark")
    submissions = []

    def respond(route):
        request = route.request
        if request.method == "POST":
            submissions.append(parse_qs(request.post_data))
            route.fulfill(content_type="text/html", body=markup)
        elif request.url.endswith("pod-readiness.js"):
            route.fulfill(content_type="text/javascript", path=assets / "pod-readiness.js")
        elif request.url.endswith("app.css"):
            route.fulfill(content_type="text/css", path=assets / "app.css")
        else:
            route.fulfill(content_type="text/html", body=markup)

    page.route(base + "/**", respond)
    page.goto(base + "/pod")
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
    page.keyboard.press("Tab")
    title = page.get_by_label("Title")
    expect(title).to_be_focused()
    title.fill("New draft")
    expect(page.get_by_text("Draft unchecked")).to_be_visible()
    expect(page.locator("#pod-results [data-status]")).to_be_hidden()
    expect(page.get_by_role("button", name="Open review")).to_be_hidden()
    with page.expect_navigation(wait_until="load"):
        page.evaluate("""() => {
            const draft = document.getElementById('pod-draft');
            draft.requestSubmit();
            draft.requestSubmit();
        }""")
    assert submissions == [{"_csrf": ["synthetic"], "title": ["New draft"]}], submissions
    assert page.evaluate("localStorage.length === 0 && sessionStorage.length === 0")
    browser.close()

print("Passed: narrow dark viewport, keyboard input, stale status, single explicit POST, CSRF and no Web Storage.")
