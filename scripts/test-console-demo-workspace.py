#!/usr/bin/env python3
"""Smoke-test the seeded local demo via Playwright, using an authenticated storage state."""
import os

from playwright.sync_api import expect, sync_playwright


def main():
    base = os.environ.get("LG_DEMO_URL", "http://localhost:8088").rstrip("/")
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        demo = browser.new_context(storage_state=os.environ["LG_DEMO_STORAGE_STATE"])
        page = demo.new_page()
        for path in ("", "/stats", "/stats/pilot", "/history", "/reaudit", "/watchlist", "/api-keys", "/phrase-screening"):
            response = page.goto(f"{base}/console{path}?lang=vi")
            assert response and response.ok, f"Cannot open {path}"
            expect(page.locator("#demo-banner")).to_contain_text("DỮ LIỆU DEMO")
        page.goto(f"{base}/console/api-keys?lang=en")
        expect(page.locator("#demo-banner")).to_contain_text("DEMO DATA")
        expect(page.locator("#create-form")).to_have_count(0)
        if state := os.environ.get("LG_REAL_STORAGE_STATE"):
            real = browser.new_context(storage_state=state).new_page()
            real.goto(f"{base}/console")
            expect(real.locator("#demo-banner")).to_have_count(0)
        browser.close()


if __name__ == "__main__":
    main()
