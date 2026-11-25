"""Measure the real Seller Pilot Console flow against an isolated test app."""
import os
from pathlib import Path

from playwright.sync_api import expect, sync_playwright


origin = os.environ["LG_PILOT_URL"]
assertion = os.environ["LG_PILOT_TEST_ASSERTION"]
screenshot = os.environ.get("LG_PILOT_SCREENSHOT")
mapping_screenshot = os.environ.get("LG_PILOT_SCREENSHOT_MAPPING")
# Eight invented listings as a Sheet holds them: SKU, title, description and tags, and no category, price or quantity column (ADR-052).
# The same file is checked in both scopes by SheetContentSampleIntegrationTest.
sample = Path(__file__).resolve().parents[1] / "docs" / "samples" / "sheet-content-sample.tsv"
rows = sample.read_text(encoding="utf-8").rstrip("\n")
assert "Ly ceramic sao nhỏ" in rows
clicks = 0

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(executable_path=os.environ.get("LG_CHROMIUM_PATH"))
    context = browser.new_context(
        base_url=origin,
        viewport={"width": 1240, "height": 920},
        extra_http_headers={"Cf-Access-Jwt-Assertion": assertion},
    )
    context.grant_permissions(["clipboard-read", "clipboard-write"], origin=origin)
    page = context.new_page()

    def click(selector):
        global clicks
        page.locator(selector).click()
        clicks += 1

    page.goto("/console/")
    expect(page.locator("html")).to_have_attribute("lang", "vi")
    expect(page.locator("#pilot-heading")).to_have_text("Pilot")
    click("#pilot-start")
    page.locator("#text").fill(rows)
    click("#paste-form button[type=submit]")
    # under the pilot profile the column step offers the content scope first, so this costs no click
    expect(page.locator("#scope-content")).to_be_checked()
    if mapping_screenshot:
        destination = Path(mapping_screenshot)
        destination.parent.mkdir(parents=True, exist_ok=True)
        page.screenshot(path=destination, full_page=False, animations="disabled")
    click("#run-button")
    expect(page.locator("#result")).to_be_visible()
    expect(page.locator("#result")).to_contain_text("Đạt (nội dung)")
    expect(page.locator("#result")).to_contain_text("Bảng không có cột:")
    expect(page.locator("#result")).not_to_contain_text("'category'")
    click("#pilot-draft summary")
    draft = page.locator("#pilot-data")
    expect(draft).to_contain_text("HC-001")
    draft.fill(draft.input_value().replace("Ly ceramic sao nhỏ", "Ly ceramic họa tiết sao"))
    expect(page.locator("#pilot-unchecked")).to_be_visible()
    expect(page.locator("#copy-sheet")).to_be_disabled()
    click("#pilot-recheck-form button[type=submit]")
    expect(page.locator("#result")).to_be_visible()
    expect(page.locator("#pilot-data")).to_have_value(rows.replace("Ly ceramic sao nhỏ", "Ly ceramic họa tiết sao"))
    click("#copy-sheet")
    expect(page.locator("#copy-status")).not_to_be_empty()
    # a Windows clipboard turns each line break into CR LF; the block is the same text
    copied = page.evaluate("navigator.clipboard.readText()").replace("\r\n", "\n")
    assert copied == page.locator("#sheet-block").input_value()
    assert len(copied.splitlines()) == 9  # the header line and eight rows
    assert copied.splitlines()[0].endswith("Phạm vi")  # a content check names its scope in the pasted block
    assert clicks == 6
    if screenshot:
        destination = Path(screenshot)
        destination.parent.mkdir(parents=True, exist_ok=True)
        # taller than the flow's window, so the picture shows the eight rows and the finding of each
        page.set_viewport_size({"width": 1240, "height": 1240})
        page.screenshot(path=destination, full_page=False, animations="disabled")
    print(f"Seller Pilot Console: {clicks} clicks; 8 synthetic rows; clipboard verified")
    browser.close()
