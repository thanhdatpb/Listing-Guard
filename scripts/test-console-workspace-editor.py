"""Exercise the workspace editor, clone, history, status and new-listing pages of the live Workspace browser test server (ADR-062).

Not run in the cloud session that wrote it (no Python Playwright, no Windows): run it on a PC. Prerequisites and the command are in docs/workspace.md,
section 11.8; the JUnit test WorkspaceEditorBrowserIntegrationTest starts the server and calls this script. It only talks to a local test server and optionally
saves fictional screenshots (set LG_WORKSPACE_SCREENSHOTS).
"""

import argparse
import os
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright


WORKSPACE = "/console/workspace"
VIEWPORT = {"width": 1280, "height": 820}
MAX_IMAGE_HEIGHT = 1800


def open_page(page, path, main):
    response = page.goto(path, wait_until="domcontentloaded")
    if response is None or not response.ok:
        raise RuntimeError(f"Workspace editor test page failed: {path}")
    expect(page.locator(f"main#{main}")).to_be_visible()
    expect(page.locator("html")).to_have_attribute("lang", "en")


def save_screen(page, folder, name, theme):
    if folder is None:
        return
    page.evaluate("document.fonts.ready")
    page.evaluate("window.scrollTo(0, 0)")
    path = folder / f"{name}-{theme}.png"
    height = page.locator("html").evaluate("(html) => html.scrollHeight")
    if height <= MAX_IMAGE_HEIGHT:
        page.screenshot(path=path, full_page=True, animations="disabled", scale="css")
    else:
        page.set_viewport_size({"width": VIEWPORT["width"], "height": MAX_IMAGE_HEIGHT})
        try:
            page.screenshot(path=path, animations="disabled", scale="css")
        finally:
            page.set_viewport_size(VIEWPORT)


def editor(page, listing, folder, theme):
    """Columns are grouped by standard field, counters follow the ruleset, a save shows the new check, a stale save is refused."""
    open_page(page, f"{WORKSPACE}/listings/{listing}/edit", "workspace-edit")
    page.locator("#console-theme").select_option(theme)
    expect(page.locator("html")).to_have_attribute("data-console-theme", theme)
    bullets = page.locator("fieldset[data-target=bullet_points] textarea, fieldset[data-target=bullet_points] input[type=text]")
    expect(bullets).to_have_count(2)
    expect(page.locator("fieldset[data-target=title]")).to_contain_text("item_name")
    expect(page.locator("#edit-passthrough")).to_be_visible()
    expect(page.locator("input[name='v.item_sku']")).to_have_attribute("readonly", "readonly")
    # the counter of the title is live and says so when the ruleset's limit is passed
    title = page.locator("input[name='v.item_name']")
    counter = page.locator("fieldset[data-target=title] .counter")
    expect(counter).to_be_visible()
    original = title.input_value()
    title.fill("x" * 400)
    expect(counter).to_have_class("lg-field__hint counter over")
    title.fill(original + " edited")
    expect(counter).not_to_have_class("lg-field__hint counter over")
    save_screen(page, folder, "workspace-edit", theme)

    page.locator("#edit-save").click()
    expect(page.locator("main#workspace-edit")).to_be_visible()
    expect(page.locator("#notice")).to_contain_text("Saved")
    expect(page.locator("input[name='v.item_name']")).to_have_value(original + " edited")
    expect(page.locator("#audit-content")).to_be_visible()
    expect(page.locator("#edit-suggestions")).to_be_visible()
    save_screen(page, folder, "workspace-edit-saved", theme)

    # a second window opened before this save is refused with the columns that differ
    other = page.context.new_page()
    open_page(other, f"{WORKSPACE}/listings/{listing}/edit", "workspace-edit")
    page.locator("input[name='v.item_name']").fill(original + " edited twice")
    page.locator("#edit-save").click()
    expect(page.locator("#notice")).to_contain_text("Saved")
    other.locator("input[name='v.item_name']").fill(original + " from the other window")
    other.locator("#edit-save").click()
    expect(other.locator("#edit-conflict")).to_be_visible()
    expect(other.locator("#edit-conflict-table")).to_contain_text("edited twice")
    save_screen(other, folder, "workspace-edit-conflict", theme)
    other.locator("#edit-reload").click()
    expect(other.locator("input[name='v.item_name']")).to_have_value(original + " edited twice")
    other.close()


def clone_and_compare(page, listing, folder, theme):
    """Clone from the detail page with a suffix; the clone says which columns are still the original's and warns of the same title."""
    open_page(page, f"{WORKSPACE}/listings/{listing}", "workspace-detail")
    page.locator("#detail-clone").click()
    expect(page.locator("main#workspace-clone")).to_be_visible()
    page.locator("#clone-suffix").fill("-CLONE")
    page.locator("#clone-refresh").click()
    expect(page.locator("#clone-items")).to_contain_text("-CLONE")
    expect(page.locator("#clone-items tr[data-ok=true]")).to_have_count(1)
    save_screen(page, folder, "workspace-clone", theme)
    page.locator("#clone-commit").click()
    expect(page.locator("main#workspace-edit")).to_be_visible()
    expect(page.locator("#edit-compared")).to_be_visible()
    expect(page.locator("#edit-title-warning")).to_be_visible()
    expect(page.locator("#edit-title-original")).to_be_visible()
    expect(page.locator("fieldset[data-target=title] .lg-chip--flag")).to_contain_text("Not edited yet")
    save_screen(page, folder, "workspace-edit-clone", theme)
    # changing the title removes the mark and the warning
    page.locator("input[name='v.item_name']").fill("A title that is only this clone's")
    page.locator("#edit-save").click()
    expect(page.locator("#notice")).to_be_visible()
    expect(page.locator("#edit-title-warning")).to_have_count(0)
    expect(page.locator("fieldset[data-target=title] .lg-chip--flag")).to_have_count(0)
    return page.url


def history_and_restore(page, edit_url, folder, theme):
    page.goto(edit_url, wait_until="domcontentloaded")
    page.locator("#edit-history").click()
    expect(page.locator("main#workspace-history")).to_be_visible()
    expect(page.locator("#history-table")).to_contain_text("Clone")
    page.locator("#history-compare").click()
    expect(page.locator("#history-changes-table")).to_be_visible()
    save_screen(page, folder, "workspace-history", theme)
    page.locator("#history-table button", has_text="Restore").first.click()
    expect(page.locator("#notice")).to_contain_text("Restored")
    expect(page.locator("input[name='v.item_name']")).not_to_have_value("A title that is only this clone's")


def status_gate(page, folder, theme):
    """The clone of a listing with an error is held back; an administrator opens the block with a reason."""
    open_page(page, f"{WORKSPACE}?owner=me&status=DRAFT", "workspace-page")
    page.locator("#workspace-table input[name=listing]").first.check()
    page.locator("#bulk-ready").click()
    expect(page.locator("main#workspace-status")).to_be_visible()
    expect(page.locator("#status-table")).to_be_visible()
    save_screen(page, folder, "workspace-status", theme)
    if page.locator("#status-open").count():
        page.locator("#status-open input[name=open]").check()
        page.locator("#status-reason").fill("The mark of this fictional listing belongs to the seller")
    page.locator("#status-apply").click()
    expect(page.locator("#status-table")).to_contain_text("Moved")


def new_listing(page, etsy, folder, theme):
    open_page(page, f"{WORKSPACE}/new", "workspace-new")
    expect(page.locator("#workspace-new-accounts")).to_be_visible()
    open_page(page, f"{WORKSPACE}/new?account={etsy}", "workspace-edit")
    page.locator("input[name='v.sku']").fill("ET-BROWSER-1")
    page.locator("input[name='v.title']").fill("A browser test mug")
    page.locator("[name='v.tags']").fill("mug, ceramic, gift")
    save_screen(page, folder, "workspace-new", theme)
    page.locator("#edit-save").click()
    expect(page.locator("#notice")).to_contain_text("created")
    # the same SKU again is refused and keeps what was typed
    open_page(page, f"{WORKSPACE}/new?account={etsy}", "workspace-edit")
    page.locator("input[name='v.sku']").fill("ET-BROWSER-1")
    page.locator("input[name='v.title']").fill("Typed again")
    page.locator("#edit-save").click()
    expect(page.locator("#error")).to_contain_text("already has a listing with this SKU")
    expect(page.locator("input[name='v.title']")).to_have_value("Typed again")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("listing", help="id of a draft listing of the Amazon test account")
    parser.add_argument("etsy", help="id of the Etsy test account")
    args = parser.parse_args()
    origin = os.environ["LG_WORKSPACE_URL"].rstrip("/")
    address = urlsplit(origin)
    if address.scheme != "http" or address.hostname not in {"localhost", "127.0.0.1"} or address.netloc != f"{address.hostname}:{address.port}":
        raise SystemExit("The workspace editor test requires a local test server.")
    dest = os.environ.get("LG_WORKSPACE_SCREENSHOTS")
    with sync_playwright() as playwright, TemporaryDirectory(prefix="lg-workspace-editor-") as temp:
        browser = playwright.chromium.launch(executable_path=os.environ.get("LG_CHROMIUM_PATH"))
        with browser.new_context(base_url=origin, viewport=VIEWPORT, device_scale_factor=1, locale="en-US", service_workers="block") as context:
            context.route("**/*", lambda route: route.continue_(headers={
                **route.request.headers, "Cf-Access-Jwt-Assertion": os.environ["LG_WORKSPACE_TEST_ASSERTION"]
            }) if route.request.url.startswith(origin + "/") else route.abort())
            page = context.new_page()
            staged = Path(temp) if dest else None
            for theme in ("light", "dark"):
                editor(page, args.listing, staged, theme)
            clone_url = clone_and_compare(page, args.listing, staged, "light")
            history_and_restore(page, clone_url, staged, "light")
            status_gate(page, staged, "light")
            new_listing(page, args.etsy, staged, "light")
            if staged:
                output = Path(dest)
                output.mkdir(parents=True, exist_ok=True)
                for file in sorted(staged.glob("*.png")):
                    (output / file.name).write_bytes(file.read_bytes())
        browser.close()
    print("Passed: editor, counters, stale save, clone, compare with the original, history, restore, status gate and new listing.")


if __name__ == "__main__":
    main()
