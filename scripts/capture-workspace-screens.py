"""Exercise the live Workspace browser test server and optionally save its fictional screenshots."""

import argparse
import os
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
    page.evaluate("window.scrollTo(0, 0)")
    path = folder / f"{name}-{theme}.png"
    height = page.locator("html").evaluate("(html) => html.scrollHeight")
    if height <= MAX_IMAGE_HEIGHT:
        page.screenshot(path=path, full_page=True, animations="disabled", scale="css")
    else:
        # Keep the same 1280px responsive layout and show the top of very long pages.
        page.set_viewport_size({"width": VIEWPORT["width"], "height": MAX_IMAGE_HEIGHT})
        try:
            page.screenshot(path=path, animations="disabled", scale="css")
        finally:
            page.set_viewport_size(VIEWPORT)
    optimize_png(path)


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


def run_import(page, account, workbook, folder=None, theme="light"):
    open_page(page, f"{WORKSPACE}/import?lang=en", "workspace-import")
    page.locator("#console-theme").select_option(theme)
    expect(page.locator("html")).to_have_attribute("data-console-theme", theme)
    page.locator("#account").select_option(account)
    page.locator("#kind").select_option("AMAZON_BULK")
    page.locator("#file").set_input_files(workbook)
    page.locator("#import-form button[type=submit]").click()
    checked_page(page, "workspace-sheet")
    expect(page.locator("#sheet-preview")).to_contain_text("item_sku")
    page.locator("#sheet").select_option("Template")
    page.locator("#fieldRow").fill("3")
    page.locator("#firstDataRow").fill("4")
    page.locator("#skuCol").select_option("0")
    save_screen(page, folder, "workspace-import-sheet", theme)

    page.locator("#sheet-next").click()
    checked_page(page, "workspace-mapping")
    expect(page.locator("html")).to_have_attribute("data-console-theme", theme)
    expect(page.locator("#mapping-table")).to_contain_text("item_name")
    save_screen(page, folder, "workspace-mapping", theme)
    page.locator("#mapping-next").click()
    checked_page(page, "workspace-preview")


def inspect(page, account, workbook, detail, folder, theme):
    run_import(page, account, workbook, folder, theme)
    for name, count in (("created", 1), ("updated", 1), ("unchanged", 2), ("errors", 1)):
        expect(page.locator(f"#count-{name}")).to_have_text(str(count))
    expect(page.locator("#preview-issues")).to_be_visible()
    save_screen(page, folder, "workspace-preview", theme)

    open_page(page, WORKSPACE, "workspace-page")
    expect(page.locator("#workspace-filters select[name=check]")).to_be_visible()
    expect(page.locator("#workspace-table")).to_contain_text("New risk")
    save_screen(page, folder, "workspace-list", theme)

    open_page(page, f"{WORKSPACE}/listings/{detail}", "workspace-detail")
    mark = page.locator("#audit-content .lg-hl--severe mark").filter(has_text="Vellumzoq")
    mark.focus()
    expect(mark).to_be_focused()
    tooltip = page.locator("#" + mark.get_attribute("aria-describedby"))
    expect(tooltip).to_be_visible()
    expect(tooltip).to_contain_text("New risk")
    expect(page.locator("#audit-table tr[data-new=true]").first).to_be_visible()
    save_screen(page, folder, "workspace-detail", theme)

    open_page(page, f"{WORKSPACE}/audit", "workspace-audit-page")
    expect(page.locator("#audit-progress")).to_have_attribute("aria-busy", "false")
    expect(page.locator("#audit-history")).to_contain_text("Watchlist changed")
    expect(page.locator("#audit-email-form input[name=enabled]")).to_be_visible()
    save_screen(page, folder, "workspace-audit", theme)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("seed", "inspect"))
    parser.add_argument("account")
    parser.add_argument("workbook", type=Path)
    args = parser.parse_args()
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
            if args.phase == "seed":
                run_import(page, args.account, args.workbook)
                expect(page.locator("#count-created")).to_have_text("3")
                page.locator("#commit-button").click()
                checked_page(page, "workspace-page")
                expect(page.locator("#workspace-table")).to_contain_text("TOTE-001")
            else:
                staged = Path(temp) if dest else None
                for theme in ("light", "dark"):
                    inspect(page, args.account, args.workbook, os.environ["LG_WORKSPACE_DETAIL"], staged, theme)
                if staged:
                    output = Path(dest)
                    output.mkdir(parents=True, exist_ok=True)
                    for file in sorted(staged.glob("*.png")):
                        (output / file.name).write_bytes(file.read_bytes())
        browser.close()


if __name__ == "__main__":
    main()
