"""Measure the real Seller Pilot Console flow against an isolated test app."""
import os
from pathlib import Path

from playwright.sync_api import expect, sync_playwright


origin = os.environ["LG_PILOT_URL"]
assertion = os.environ["LG_PILOT_TEST_ASSERTION"]
screenshot = os.environ.get("LG_PILOT_SCREENSHOT")
rows = (
    "SKU\tTitle\tDescription\tTags\tPrice\tQuantity\tWho made\tWhen made\n"
    "DEMO-01\tLy ceramic sao nhỏ\tLy gốm làm thủ công.\tly gốm,gốm\t19.99\t4\ti_did\tmade_to_order\n"
    "DEMO-02\tNến vườn nhà\tMột mẫu nến thử nghiệm.\tnến,quà nhỏ\t12.00\t8\ti_did\tmade_to_order"
)
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
    click("#run-button")
    expect(page.locator("#result")).to_be_visible()
    click("#pilot-draft summary")
    draft = page.locator("#pilot-data")
    expect(draft).to_contain_text("DEMO-01")
    draft.fill(draft.input_value().replace("Ly ceramic sao nhỏ", "Ly ceramic họa tiết sao"))
    expect(page.locator("#pilot-unchecked")).to_be_visible()
    expect(page.locator("#copy-sheet")).to_be_disabled()
    click("#pilot-recheck-form button[type=submit]")
    expect(page.locator("#result")).to_be_visible()
    expect(page.locator("#pilot-data")).to_have_value(rows.replace("Ly ceramic sao nhỏ", "Ly ceramic họa tiết sao"))
    click("#copy-sheet")
    expect(page.locator("#copy-status")).not_to_be_empty()
    copied = page.evaluate("navigator.clipboard.readText()")
    assert copied == page.locator("#sheet-block").input_value()
    assert len(copied.splitlines()) == 3
    assert clicks == 6
    if screenshot:
        destination = Path(screenshot)
        destination.parent.mkdir(parents=True, exist_ok=True)
        page.screenshot(path=destination, full_page=False, animations="disabled")
    print(f"Seller Pilot Console: {clicks} clicks; 2 synthetic rows; clipboard verified")
    browser.close()
