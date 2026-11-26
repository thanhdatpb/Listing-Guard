#!/usr/bin/env python3
"""Playwright smoke test for a test deployment. Uses three synthetic input rows and consumes one screening request."""

import os

from playwright.sync_api import sync_playwright


def main():
    base_url = os.environ["LG_BASE_URL"].rstrip("/")
    api_key = os.environ["LG_API_KEY"]
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=os.environ.get("LG_CHROMIUM_PATH"))
        page = browser.new_page()
        health = page.goto(f"{base_url}/health")
        assert health and health.ok, "The test deployment health endpoint is unavailable."
        result = page.evaluate(
            """async ({apiKey}) => {
                const headers = {"X-API-Key": apiKey, "Content-Type": "application/json"};
                const response = await fetch("/api/v1/capabilities", {headers});
                if (!response.ok) return {capabilitiesStatus: response.status};
                const enabled = (await response.json()).phraseScreening?.enabled;
                const run = await fetch("/api/v1/phrase-screenings", {
                    method: "POST", headers,
                    body: JSON.stringify({marketplace: "ETSY", phrases: [
                        {text: "woven storage pouch", searchVolume: 22},
                        {text: "WOVEN STORAGE POUCH!"},
                        {text: " "}
                    ]})
                });
                return {enabled, status: run.status, body: await run.json()};
            }""",
            {"apiKey": api_key},
        )
        assert result.get("enabled"), "Enable phrase screening on the test deployment."
        assert result["status"] == 200, f"Unexpected screening status: HTTP {result['status']}"
        summary = result["body"]["summary"]
        assert summary["duplicates"] == 1 and summary["dropped"] == 1
        row, = result["body"]["phrases"]
        assert row["searchVolume"] == 22 and row["verdict"] in {"CLEAR", "REVIEW", "BLOCK"}
        assert row["completeness"] in {"COMPLETE", "INCOMPLETE"}
        browser.close()
    print("Phrase screening browser smoke test passed.")


if __name__ == "__main__":
    main()
