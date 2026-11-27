"""Capture the real local DEMO Console. No credentials or browser state are written to the gallery."""
import argparse
from datetime import UTC, datetime
from html import escape
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import parse_qs, urlencode, urlparse
import time

from playwright.sync_api import expect, sync_playwright

ROOT = Path(__file__).resolve().parents[1]
GALLERY = ROOT / "docs" / "design" / "screens" / "current"
PAGES = (
    ("home", "Tổng quan", "/console/"),
    ("check", "Kiểm tra một listing", "/console/check"),
    ("bulk", "Kiểm tra hàng loạt", "/console/bulk"),
    ("edit", "Sửa listing", "/console/check/edit"),
    ("keywords", "Kiểm keyword", "/console/phrase-screening"),
    ("history", "Lịch sử", "/console/history"),
    ("reaudit", "Rà định kỳ", "/console/reaudit"),
    ("watchlist", "Watchlist", "/console/watchlist"),
    ("stats", "Thống kê", "/console/stats"),
    ("readiness", "Sẵn sàng pilot", "/console/readiness"),
    ("keys", "API keys", "/console/api-keys"),
    ("report", "Báo cáo sức khỏe listing", "/console/report"),
    ("manager", "Báo cáo cho quản lý", "/console/stats/pilot"),
    ("explain", "Giải thích", "/console/explain"),
)


def open_page(page, url, demo=True):
    response = page.goto(url, wait_until="domcontentloaded")
    if not response or not response.ok:
        raise RuntimeError(f"Không mở được trang {urlparse(url).path}. Kiểm tra trạng thái hoặc quyền pilot.")
    if demo:
        expect(page.locator("#demo-banner")).to_contain_text("DỮ LIỆU DEMO")
        expect(page.locator("main h1").first).to_be_visible()
    page.evaluate("document.fonts.ready")


def log_in(page, origin, email, env_file):
    since = datetime.now(UTC).isoformat(timespec="seconds")
    open_page(page, f"{origin}/login?lang=vi", demo=False)
    page.locator("#email").fill(email)
    page.locator('form[action="/login"] button[type="submit"]').click()
    token = None
    for _ in range(15):
        logs = subprocess.run(
            ["docker", "compose", "--env-file", str(env_file), "logs", "--since", since, "api"],
            cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=10, check=False,
        )
        if logs.returncode:
            raise RuntimeError("Không đọc được log API cục bộ. Kiểm tra Docker Desktop và .env.demo.")
        candidates = re.findall(r"Development sign-in link: (https?://\S+)", logs.stdout)
        for url in reversed(candidates):
            parts = urlparse(url)
            if parts.hostname in ("localhost", "127.0.0.1") and parts.port == urlparse(origin).port:
                token = parse_qs(parts.query).get("token", [None])[0]
                if token:
                    break
        if token:
            break
        time.sleep(1)
    if not token:
        raise RuntimeError("Không tìm thấy magic link. Kiểm tra email đã seed và profile dev.")
    try:
        page.goto(f"{origin}/login/verify?{urlencode({'token': token})}", wait_until="domcontentloaded")
        page.locator('form[action="/login/verify"] button[type="submit"]').click()
    except Exception:
        raise RuntimeError("Magic link hết hạn hoặc không hợp lệ.") from None
    open_page(page, f"{origin}/console/?lang=vi")
    identity = page.locator(".app-account__who").inner_text()
    if email.lower() not in identity.lower():
        raise RuntimeError("Phiên demo không thuộc email vừa yêu cầu.")


def capture(page, filename, entries, title):
    page.evaluate("document.fonts.ready")
    sidebar = page.locator(".app-side")
    mask_color = sidebar.evaluate("(node) => getComputedStyle(node).backgroundColor") if sidebar.count() else "transparent"
    page.screenshot(path=GALLERY / filename, full_page=True, animations="disabled",
                    mask=[page.locator(".app-account__who span:last-child")], mask_color=mask_color)
    entries.append((title, filename))


def write_index(entries):
    cards = "\n".join(
        f'<figure><a href="{name}"><img src="{name}" alt="{escape(title)}" loading="lazy"></a>'
        f'<figcaption>{escape(title)}</figcaption></figure>' for title, name in entries
    )
    (GALLERY / "index.html").write_text(
        '<!doctype html><html lang="vi"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        '<title>Listing Guard — Gallery</title><link rel="stylesheet" href="../../tokens.css">'
        '<link rel="stylesheet" href="gallery.css"></head><body><h1>Listing Guard — Gallery</h1>'
        f'<main>{cards}</main></body></html>\n', encoding="utf-8",
    )
    (GALLERY / "gallery.css").write_text(
        'body { background: var(--surface-0); color: var(--ink); font-family: var(--font-sans); '
        'margin: var(--space-6); } main { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 22rem), 1fr)); '
        'gap: var(--space-4); } figure { margin: 0; padding: var(--space-3); background: var(--surface-1); '
        'border: 1px solid var(--border); border-radius: var(--radius-lg); } img { width: 100%; height: auto; } '
        'figcaption { margin-block-start: var(--space-2); }\n', encoding="utf-8",
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8088")
    parser.add_argument("--email", default="demo-admin@example.test")
    parser.add_argument("--env-file", type=Path, default=Path(".env.demo"))
    parser.add_argument("--storage-state", type=Path, help="Optional local ADMIN demo session created by Playwright.")
    parser.add_argument("--member-state", type=Path, help="Optional local MEMBER demo session created by Playwright.")
    args = parser.parse_args()
    url = urlparse(args.url)
    if (url.hostname not in ("localhost", "127.0.0.1") or url.scheme != "http" or not url.port
            or url.username or url.password or url.path not in ("", "/") or url.query or url.fragment):
        parser.error("--url phải là địa chỉ HTTP localhost của Docker Desktop, gồm cổng.")
    origin = args.url.rstrip("/")
    env_file = args.env_file if args.env_file.is_absolute() else ROOT / args.env_file
    entries = []
    GALLERY.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=os.environ.get("LG_CHROMIUM_PATH"))
        options = dict(viewport={"width": 1440, "height": 1000}, device_scale_factor=1)

        def local_context(storage_state=None):
            context = browser.new_context(**options, storage_state=storage_state)
            context.route("**/*", lambda route: route.continue_() if route.request.url.startswith(f"{origin}/") else route.abort())
            return context

        with local_context() as public:
            page = public.new_page()
            open_page(page, f"{origin}/login?lang=vi", demo=False)
            for theme, label in (("light", "Sáng"), ("dark", "Tối")):
                page.locator("#console-theme").select_option(theme)
                capture(page, f"login-{theme}.png", entries, f"Đăng nhập · {label}")
        with local_context(args.storage_state) as admin:
            page = admin.new_page()
            if not args.storage_state:
                log_in(page, origin, args.email, env_file)
            for theme, label in (("light", "Sáng"), ("dark", "Tối")):
                open_page(page, f"{origin}/console/?lang=vi")
                page.locator("#console-theme").select_option(theme)
                for slug, title, path in PAGES:
                    open_page(page, f"{origin}{path}?lang=vi")
                    expect(page.locator("html")).to_have_attribute("data-console-theme", theme)
                    capture(page, f"{slug}-{theme}.png", entries, f"{title} · {label}")
                    if slug == "history":
                        detail = page.locator('a[href^="/console/history/listing-rules/"]').first
                        if detail.count():
                            detail.click()
                            expect(page.locator("#demo-banner")).to_be_visible()
                            expect(page.locator("main h1").first).to_be_visible()
                            capture(page, f"listing-detail-{theme}.png", entries, f"Chi tiết listing · {label}")
            open_page(page, f"{origin}/console/stats/pilot?lang=vi")
            expect(page.locator(".lg-manager-print")).to_be_visible()
            page.emulate_media(media="print", color_scheme="dark")
            assert page.locator("html").evaluate("(node) => getComputedStyle(node).colorScheme") == "light"
            capture(page, "manager-print.png", entries, "Báo cáo cho quản lý · Bản in")
        if args.member_state:
            with local_context(args.member_state) as member:
                page = member.new_page()
                open_page(page, f"{origin}/console/?lang=vi")
                expect(page.locator("#work-actions")).to_be_visible()
                for theme, label in (("light", "Sáng"), ("dark", "Tối")):
                    page.locator("#console-theme").select_option(theme)
                    capture(page, f"member-{theme}.png", entries, f"Việc của tôi · {label}")
        browser.close()
    write_index(entries)
    print(f"Đã chụp {len(entries)} ảnh trong {GALLERY.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
