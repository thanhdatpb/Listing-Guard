"""Browser regression check for the edit workbench, with synthetic responses."""

import os
from pathlib import Path
from playwright.sync_api import sync_playwright
repo = Path(__file__).resolve().parents[1]
script = (repo / 'api/src/main/resources/static/console/edit-mode.js').read_text(encoding='utf-8')
base='http://workbench.test'
page_html = '''<form id="edit-form" action="/console/check/edit" data-i18n-request-error="Request failed">
<input name="_csrf" value="synthetic"><input name="marketplace" value="ETSY">
<textarea name="before">{"title":"Before"}</textarea><textarea name="after">{"title":"Old draft"}</textarea>
<button type="submit">Recheck</button></form>
<p id="edit-unchecked" hidden>UNCHECKED</p><p id="edit-request-error"></p>
<div id="edit-output"><div id="edit-result"><button data-draft-fix data-field="title" data-value="Old draft"
data-action="REPLACE" data-proposed="Fixed draft" hidden>Apply</button></div></div>
<script src="/edit-mode.js"></script>'''
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=os.environ.get('LG_CHROMIUM_PATH'))
    page=browser.new_page()
    requests=[]
    def respond(route):
        if route.request.method == 'POST':
            requests.append(route)
        elif route.request.url.endswith('edit-mode.js'):
            route.fulfill(content_type='text/javascript',body=script)
        else:
            route.fulfill(content_type='text/html',body=page_html)
    page.route(base+'/**', respond)
    page.goto(base+'/console/check/edit')
    page.get_by_text('Apply').click()
    draft=page.locator('[name=after]')
    assert 'Fixed draft' in draft.input_value()
    assert page.locator('#edit-unchecked').is_visible()
    assert not page.locator('#edit-result').count()
    submit=page.get_by_text('Recheck',exact=True)
    with page.expect_request(lambda r:r.method=='POST'):
        submit.click()
    page.wait_for_function("document.querySelector('button[type=submit]').disabled")
    page.eval_on_selector('form','form => form.requestSubmit()')
    assert len(requests)==1
    assert '_csrf=synthetic' in requests[-1].request.post_data
    draft.fill('{"title":"Newer draft"}')
    requests[-1].fulfill(content_type='text/html',body='<div id=edit-output><section id=edit-result>Stale</section></div>')
    page.wait_for_function("!document.querySelector('button[type=submit]').disabled")
    assert not page.locator('#edit-result').count()
    assert page.locator('#edit-unchecked').is_visible()
    for status in [429,503]:
        with page.expect_request(lambda r:r.method=='POST'):
            submit.click()
        requests[-1].fulfill(status=status,body='')
        page.get_by_text('Request failed').wait_for()
        assert draft.input_value()=='{"title":"Newer draft"}'
        assert page.locator('#edit-unchecked').is_visible()
    with page.expect_request(lambda r:r.method=='POST'):
        submit.click()
    requests[-1].fulfill(content_type='text/html',body='<div id=edit-output><section id=edit-result>Current</section></div>')
    page.get_by_text('Current').wait_for()
    assert page.locator('#edit-unchecked').is_hidden()
    page.clock.install()
    with page.expect_request(lambda r:r.method=='POST'):
        submit.click()
    page.clock.fast_forward(31000)
    page.get_by_text('Request failed').wait_for()
    assert draft.input_value()=='{"title":"Newer draft"}'
    assert page.locator('#edit-unchecked').is_visible()
    assert page.evaluate('localStorage.length === 0 && sessionStorage.length === 0')
    requests[-1].abort()
    browser.close()
print('Passed: explicit fix, unchecked state, single POST, CSRF, stale response, 429/503, timeout and successful recheck.')
