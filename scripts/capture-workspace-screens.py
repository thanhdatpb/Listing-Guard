"""Exercise the live Workspace browser test server over its synthetic data and optionally save the fictional screenshots.

Started by WorkspaceBrowserIntegrationTest, which seeds the dataset (WorkspaceDemoDataset) and writes a manifest of ids and file paths; the arguments are:

    python3 scripts/capture-workspace-screens.py <manifest.json>

Environment: LG_WORKSPACE_URL and LG_WORKSPACE_TEST_ASSERTION (set by the test, a local server only), LG_WORKSPACE_SCREENSHOTS (a folder: keep the PNG files),
LG_CHROMIUM_PATH (an installed Chromium, when Playwright's own was not downloaded). Nothing here saves anything in the workspace: a re-import stops at its
preview, a clone at its preview, a status change before the button. Screenshots are 1280 px wide, English, once in the light and once in the dark theme.
"""

import json
import os
import sys
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright


WORKSPACE = "/console/workspace"
VIEWPORT = {"width": 1280, "height": 820}
MAX_IMAGE_HEIGHT = 1800


def checked_page(page, main):
    expect(page.locator(f"main#{main}")).to_be_visible()
    expect(page.locator("html")).to_have_attribute("lang", "en")
    expect(page.locator("#demo-banner")).to_contain_text("DEMO DATA")


def open_page(page, path, main):
    response = page.goto(path, wait_until="domcontentloaded")
    if response is None or not response.ok:
        raise RuntimeError(f"Workspace test page failed: {path}")
    checked_page(page, main)


def save_screen(page, folder, name, theme):
    if folder is None:
        return
    page.evaluate("document.fonts.ready")
    # a table keeps the height of its rows here (the Console caps it at 32rem and scrolls it), so the picture shows the rows
    page.add_style_tag(content=".lg-table-wrap { max-block-size: none; }")
    # The side bar is as tall as the window: give the window the height of the page (at most MAX_IMAGE_HEIGHT; a longer page shows its top).
    page.set_viewport_size(VIEWPORT)
    height = page.evaluate("Math.max(document.documentElement.scrollHeight, (document.querySelector('.app-side') || document.body).scrollHeight)")
    page.set_viewport_size({"width": VIEWPORT["width"], "height": min(max(height, VIEWPORT["height"]), MAX_IMAGE_HEIGHT)})
    try:
        page.evaluate("window.scrollTo(0, 0)")
        page.screenshot(path=folder / f"{name}-{theme}.png", animations="disabled", scale="css")
    finally:
        page.set_viewport_size(VIEWPORT)
    optimize_png(folder / f"{name}-{theme}.png")


def optimize_png(path):
    try:
        from PIL import Image
    except ImportError:
        return
    candidate = path.with_suffix(".optimized.png")
    with Image.open(path) as image:
        image.save(candidate, optimize=True)
    if candidate.stat().st_size < path.stat().st_size:
        candidate.replace(path)
    else:
        candidate.unlink()


def set_theme(page, theme):
    page.locator("#console-theme").select_option(theme)
    expect(page.locator("html")).to_have_attribute("data-console-theme", theme)


def run_import(page, account, workbook, theme, sku_col=0):
    """The first three steps of a re-import: file, sheet, mapping. Returns on the preview; nothing is committed."""
    open_page(page, f"{WORKSPACE}/import?lang=en", "workspace-import")
    set_theme(page, theme)
    page.locator("#account").select_option(account)
    page.locator("#kind").select_option("AMAZON_BULK")
    page.locator("#file").set_input_files(workbook)
    page.locator("#import-form button[type=submit]").click()
    checked_page(page, "workspace-sheet")
    expect(page.locator("#sheet-preview")).to_contain_text("item_sku")
    page.locator("#sheet").select_option("Template")
    page.locator("#fieldRow").fill("3")
    page.locator("#firstDataRow").fill("4")
    page.locator("#skuCol").select_option(str(sku_col))
    return page


def import_screens(page, manifest, folder, theme):
    """workspace-import-sheet, workspace-mapping and workspace-preview: a re-import into the second Amazon account (other template, other columns)."""
    run_import(page, manifest["accountB"], manifest["fileB"], theme)
    save_screen(page, folder, "workspace-import-sheet", theme)
    page.locator("#sheet-next").click()
    checked_page(page, "workspace-mapping")
    expect(page.locator("html")).to_have_attribute("data-console-theme", theme)
    expect(page.locator("#mapping-table")).to_contain_text("item_name")
    expect(page.locator("#mapping-table")).to_contain_text("fabric_note")
    save_screen(page, folder, "workspace-mapping", theme)
    page.locator("#mapping-next").click()
    checked_page(page, "workspace-preview")
    expected = manifest["expectedB"]
    for name in ("created", "updated", "unchanged", "errors"):
        expect(page.locator(f"#count-{name}")).to_have_text(str(expected[name]))
    expect(page.locator("#preview-issues")).to_be_visible()
    save_screen(page, folder, "workspace-preview", theme)


def conflict_screen(page, manifest, folder, theme):
    """workspace-conflict: the file of account A is read again after two of its listings were edited in the workspace."""
    run_import(page, manifest["accountA"], manifest["fileA"], theme)
    page.locator("#sheet-next").click()
    checked_page(page, "workspace-mapping")
    page.locator("#mapping-next").click()
    checked_page(page, "workspace-preview")
    expect(page.locator("#preview-edited")).to_be_visible()
    expect(page.locator("#preview-edited-table")).to_contain_text("TOTE-N")
    expect(page.locator("#preview-edited-table")).to_contain_text("POSTER-A3")
    expect(page.locator("#count-edited")).to_have_text("2")
    save_screen(page, folder, "workspace-conflict", theme)


def list_screen(page, folder, theme):
    open_page(page, WORKSPACE, "workspace-page")
    set_theme(page, theme)
    expect(page.locator("#workspace-filters select[name=check]")).to_be_visible()
    table = page.locator("#workspace-table")
    for state in ("Clean", "Warning", "Error", "New risk", "Not fully checked"):
        expect(table).to_contain_text(state)
    for sku in ("TOTE-S", "MUG-004", "PILLOW-SET", "ETSY-TOTE-1"):
        expect(table).to_contain_text(sku)
    save_screen(page, folder, "workspace-list", theme)


def detail_screen(page, manifest, folder, theme):
    open_page(page, f"{WORKSPACE}/listings/{manifest['detail']}", "workspace-detail")
    mark = page.locator("#audit-content .lg-hl--severe mark").filter(has_text="Vellumzoq")
    mark.focus()
    expect(mark).to_be_focused()
    tooltip = page.locator("#" + mark.get_attribute("aria-describedby"))
    expect(tooltip).to_be_visible()
    expect(tooltip).to_contain_text("New risk")
    expect(page.locator("#audit-table tr[data-new=true]").first).to_be_visible()
    save_screen(page, folder, "workspace-detail", theme)


def audit_screen(page, folder, theme):
    open_page(page, f"{WORKSPACE}/audit", "workspace-audit-page")
    expect(page.locator("#audit-progress")).to_have_attribute("aria-busy", "false")
    expect(page.locator("#audit-history")).to_contain_text("Watchlist changed")
    expect(page.locator("#audit-email-form input[name=enabled]")).to_be_visible()
    save_screen(page, folder, "workspace-audit", theme)


def editor_screen(page, manifest, folder, theme):
    """workspace-editor: the counters against the ruleset's limits and, further down the same page, the text the audit marked.

    The page is about 4,000 px long (every mapped column is a group), so the picture leaves out the groups that have no counter, the
    collapsed passthrough columns and the hidden ones: what is shown is the page's own markup, only shorter, so that the counters and the marked
    text are in one frame of at most 1,800 px. Nothing is changed or saved."""
    open_page(page, f"{WORKSPACE}/listings/{manifest['editor']}/edit", "workspace-edit")
    expect(page.locator("#audit-content mark").filter(has_text="Grumbleflux").first).to_be_visible()
    title = page.locator("fieldset[data-target=title] .counter")
    expect(title).to_be_visible()
    expect(title).to_have_class("lg-field__hint counter over")
    page.add_style_tag(content=(
        "fieldset.lg-edit-group:not([data-target=sku]):not([data-target=title]):not([data-target=bullet_points])"
        ":not([data-target=search_terms]) { display: none; } #edit-passthrough { display: none; }"))
    save_screen(page, folder, "workspace-editor", theme)


def compare_screen(page, manifest, folder, theme):
    """workspace-compare: a clone whose title is still the original's, with the columns that were not edited yet."""
    open_page(page, f"{WORKSPACE}/listings/{manifest['compare']}/edit", "workspace-edit")
    expect(page.locator("#edit-compared")).to_be_visible()
    expect(page.locator("#edit-title-warning")).to_be_visible()
    expect(page.locator("#edit-title-original")).to_be_visible()
    expect(page.locator("fieldset[data-target=title] .lg-chip--flag")).to_contain_text("Not edited yet")
    save_screen(page, folder, "workspace-compare", theme)


def history_screen(page, manifest, folder, theme):
    """workspace-history: two versions of a clone and what differs between them."""
    open_page(page, f"{WORKSPACE}/listings/{manifest['history']}/history", "workspace-history")
    expect(page.locator("#history-table")).to_contain_text("Clone")
    expect(page.locator("#history-table")).to_contain_text("Edit")
    page.locator("#history-compare").click()
    checked_page(page, "workspace-history")
    expect(page.locator("#history-changes-table")).to_be_visible()
    save_screen(page, folder, "workspace-history", theme)


def pick(page, sku):
    row = page.locator("#workspace-table tbody tr").filter(has_text=sku).first
    row.locator("input[name=listing]").check()


def clone_screen(page, manifest, folder, theme):
    """workspace-clone: the new SKUs and the columns of the first account laid on the template of the second one. Nothing is created."""
    open_page(page, WORKSPACE, "workspace-page")
    pick(page, manifest["cloneSku"])
    page.locator("#bulk-clone").click()
    checked_page(page, "workspace-clone")
    page.locator("#clone-target").select_option(label=manifest["cloneTarget"])
    page.locator("input[name=family]").check()
    page.locator("#clone-find").fill("TOTE")
    page.locator("#clone-replace").fill("BAG")
    page.locator("#clone-refresh").click()
    expect(page.locator("#clone-items")).to_contain_text("BAG-P")
    expect(page.locator("#clone-items tbody tr")).to_have_count(4)
    expect(page.locator("#clone-pairs")).to_be_visible()
    expect(page.locator("#clone-unmapped")).to_be_visible()
    save_screen(page, folder, "workspace-clone", theme)


def status_screen(page, manifest, folder, theme):
    """workspace-status: drafts held back by the gate, and the administrator's reason box. The button is not pressed."""
    open_page(page, WORKSPACE, "workspace-page")
    for sku in manifest["statusSkus"]:
        pick(page, sku)
    page.locator("#bulk-ready").click()
    checked_page(page, "workspace-status")
    expect(page.locator("#status-table")).to_contain_text("MUG-004")
    expect(page.locator("#status-open")).to_be_visible()
    page.locator("#status-open input[name=open]").check()
    page.locator("#status-reason").fill("The mark of this fictional listing belongs to the seller")
    save_screen(page, folder, "workspace-status", theme)


def export_screen(page, manifest, folder, theme):
    """workspace-export: the confirmation of an export of selected listings, with the ones that cannot go listed with their reason. Nothing is exported."""
    open_page(page, WORKSPACE, "workspace-page")
    for sku in ("MUG-004", "MUG-001-B", "TOTE-S"):
        pick(page, sku)
    page.locator("#bulk-export").click()
    checked_page(page, "workspace-export")
    expect(page.locator("#export-summary")).to_be_visible()
    expect(page.locator("#export-held-table")).to_contain_text("MUG-004")
    save_screen(page, folder, "workspace-export", theme)


def etsy_copy_screen(page, manifest, folder, theme):
    """workspace-etsy-copy: the panel "copy each item" of an Etsy listing. Nothing is copied or posted."""
    open_page(page, WORKSPACE, "workspace-page")
    page.locator("#workspace-table tbody tr").filter(has_text="ETSY-TOTE-1").first.locator("a").first.click()
    checked_page(page, "workspace-detail")
    expect(page.locator("#etsy-copy-table")).to_be_visible()
    page.locator("#etsy-copy").scroll_into_view_if_needed()
    save_screen(page, folder, "workspace-etsy-copy", theme)


def inspect(page, manifest, folder, theme):
    import_screens(page, manifest, folder, theme)
    list_screen(page, folder, theme)
    detail_screen(page, manifest, folder, theme)
    editor_screen(page, manifest, folder, theme)
    clone_screen(page, manifest, folder, theme)
    compare_screen(page, manifest, folder, theme)
    history_screen(page, manifest, folder, theme)
    status_screen(page, manifest, folder, theme)
    conflict_screen(page, manifest, folder, theme)
    audit_screen(page, folder, theme)
    export_screen(page, manifest, folder, theme)
    etsy_copy_screen(page, manifest, folder, theme)


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: capture-workspace-screens.py <manifest.json>")
    manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    origin = os.environ["LG_WORKSPACE_URL"].rstrip("/")
    address = urlsplit(origin)
    if address.scheme != "http" or address.hostname not in {"localhost", "127.0.0.1"} or address.netloc != f"{address.hostname}:{address.port}":
        raise SystemExit("Workspace screenshots require a local test server.")
    dest = os.environ.get("LG_WORKSPACE_SCREENSHOTS")
    with sync_playwright() as playwright, TemporaryDirectory(prefix="lg-workspace-screens-") as temp:
        browser = playwright.chromium.launch(executable_path=os.environ.get("LG_CHROMIUM_PATH"))
        with browser.new_context(base_url=origin, viewport=VIEWPORT, device_scale_factor=1, locale="en-US",
                                 service_workers="block") as context:
            # The test assertion can only be sent to the local Spring test server.
            context.route("**/*", lambda route: route.continue_(headers={
                **route.request.headers, "Cf-Access-Jwt-Assertion": os.environ["LG_WORKSPACE_TEST_ASSERTION"]
            }) if route.request.url.startswith(origin + "/") else route.abort())
            page = context.new_page()
            staged = Path(temp) if dest else None
            for theme in ("light", "dark"):
                inspect(page, manifest, staged, theme)
            if staged:
                output = Path(dest)
                output.mkdir(parents=True, exist_ok=True)
                for file in sorted(staged.glob("*.png")):
                    (output / file.name).write_bytes(file.read_bytes())
        browser.close()
    print("Passed: import, list, detail, editor, clone, compare, history, status, conflict, audit, export and Etsy copy pages in both themes.")


if __name__ == "__main__":
    main()
