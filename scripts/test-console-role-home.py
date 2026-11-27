#!/usr/bin/env python3
"""Exercise the pilot role home with two authenticated test accounts."""
import os
import re

from playwright.sync_api import expect, sync_playwright


def main():
    base = os.environ.get("LG_DEMO_URL", "http://localhost:8088").rstrip("/")
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        manager = browser.new_context(storage_state=os.environ["LG_MANAGER_STORAGE_STATE"])
        page = manager.new_page()
        colors = {}
        for scheme in ("light", "dark"):
            page.emulate_media(color_scheme=scheme)
            for days in (7, 30, 90):
                response = page.goto(f"{base}/console/?lang=vi&days={days}")
                assert response and response.ok
                expect(page.get_by_role("heading", name="Tổng quan", exact=True)).to_be_visible()
                expect(page.get_by_role("link", name=f"{days} ngày")).to_have_attribute("aria-current", "true")
                expect(page.locator("#manager-checks")).to_be_visible()
            colors[scheme] = page.locator("body").evaluate("(body) => getComputedStyle(body).backgroundColor")
        assert colors["light"] != colors["dark"], colors
        page.locator("#manager-report-link").click()
        expect(page.locator(".lg-manager-print")).to_be_visible()
        page.evaluate("window.print = () => document.body.dataset.printRequested = 'true'")
        page.get_by_role("button", name="In / lưu PDF").click()
        expect(page.locator("body")).to_have_attribute("data-print-requested", "true")
        with page.expect_download() as download:
            page.locator('a[href*="pilot.csv"]').click()
        assert download.value.suggested_filename.endswith(".csv")
        page.emulate_media(media="print")
        expect(page.locator("#manager-print-button")).to_be_hidden()
        pdf = page.pdf(prefer_css_page_size=True)
        assert len(re.findall(rb"/Type\s*/Page\b", pdf)) == 1, "Expected a one-page A4 report"

        member = browser.new_context(storage_state=os.environ["LG_MEMBER_STORAGE_STATE"])
        page = member.new_page()
        page.goto(f"{base}/console/?lang=en")
        expect(page.get_by_role("heading", name="My work", exact=True)).to_be_visible()
        expect(page.locator("#work-actions a")).to_have_count(3)
        expect(page.locator("#manager-report-link")).to_have_count(0)
        expect(page.locator("#work-guide")).to_be_visible()
        page.get_by_role("button", name="Hide this guide").click()
        page.reload()
        expect(page.locator("#work-guide")).to_have_count(0)
        response = page.goto(f"{base}/console/stats/pilot")
        assert response and response.status == 403
        browser.close()


if __name__ == "__main__":
    main()
