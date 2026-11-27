"""Smoke test of the keyword console on an isolated pilot deployment with a synthetic catalog."""

import os
from pathlib import Path

from playwright.sync_api import expect, sync_playwright


def main():
    origin = os.environ["LG_PILOT_URL"].rstrip("/")
    assertion = os.environ["LG_PILOT_TEST_ASSERTION"]
    sample = Path(__file__).resolve().parents[1] / "docs" / "samples" / "phrase-screening-sample.csv"

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=os.environ.get("LG_CHROMIUM_PATH"))
        context = browser.new_context(
            base_url=origin,
            viewport={"width": 980, "height": 900},
            extra_http_headers={"Cf-Access-Jwt-Assertion": assertion},
            accept_downloads=True,
        )
        context.grant_permissions(["clipboard-read", "clipboard-write"], origin=origin)
        page = context.new_page()
        page.goto("/console/?lang=vi")
        expect(page.locator("html")).to_have_attribute("lang", "vi")
        page.locator("#home-phrase-screening").click()

        page.locator("#phrase-file").set_input_files(sample)
        page.locator("#phrase-upload button").click()
        expect(page.locator("#phrase-mapping-guess")).to_be_visible()
        expect(page.locator("#phrase-column")).to_have_value("0")
        expect(page.locator("#phrase-volume")).to_have_value("1")
        expect(page.locator("#phrase-counts")).to_contain_text("1 trùng")
        page.locator("#phrase-run input[name=remember]").check()
        page.locator("#phrase-submit").click()
        expect(page.locator("#phrase-result")).to_be_visible()
        for group in ("BLOCK", "REVIEW", "CLEAR"):
            page.locator(f'[data-phrase-tab="{group}"]').click()
            expect(page.locator(f'[data-phrase-tab="{group}"]')).to_have_attribute("aria-current", "page")

        with page.expect_download() as download:
            page.locator("#phrase-safe-csv").click()
        csv = download.value.path().read_text(encoding="utf-8-sig")
        assert csv.splitlines()[0] == "Cụm từ,Lượng tìm kiếm"
        page.locator("#phrase-copy").click()
        expect(page.locator("#phrase-copy-status")).to_contain_text("Đã sao chép")

        with page.expect_download() as download:
            page.locator("#phrase-full-csv").click()
        assert "Zorvex" in download.value.path().read_text(encoding="utf-8-sig")
        page.goto("/console/phrase-screening?lang=en")
        expect(page.get_by_role("heading", name="Screen keywords")).to_be_visible()
        page.locator("#phrase-data").fill("linen tumbler\nwoven storage pouch")
        page.locator("#phrase-paste button").click()
        expect(page.locator("#phrase-counts")).to_contain_text("2 phrases")
        page.locator("#phrase-submit").click()
        expect(page.locator("#phrase-result")).to_be_visible()
        browser.close()
    print("Phrase screening console smoke test passed.")


if __name__ == "__main__":
    main()
