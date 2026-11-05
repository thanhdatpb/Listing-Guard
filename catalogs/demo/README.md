# DEMO trademark catalog (fictional)

Ten **invented** marks so that the trademark panel of the console editor can be tried on a laptop and shown in a demo
video. **Nothing in it is real**: not a trademark, not a register, not any company's data. An accidental resemblance to a
real mark is possible (the words were made up, not searched), so never present this data as real and never add a real
mark to it. Its source ids are `DEMO-001` to `DEMO-010`, its file is `DEMO-fictional-marks.jsonl`, and its manifest entry is
`demo-fictional-2026-11` (the manifest format allows only lowercase labels).

**It is never used by default.** `catalogs/manifest.yaml` does not list it, the application and the compose file point at
`catalogs/`, not at `catalogs/demo/`, and trademark checks stay off until you import a catalog on purpose and set
`TRADEMARK_CATALOG_VERSION`. `DemoCatalogTest` checks this, the labels, and every result in the table below.

## What is in it, and what the checker does with it

The manifest keeps only registered and pending marks in Nice classes 25 and 35, like the example entries of
`catalogs/manifest.yaml`, so two of the ten records are dropped on purpose (they show the filters).

| Type this in a title | Result in the trademark panel | Why |
|---|---|---|
| `Zibblefrax Style Ceramic Mug` | HIGH, `catalog_exact` | the mark itself |
| `Quonkleberry Jam Jar Label`, `Plimbusto Poster`, `Grumbleflux Mug`, `Snazzlefrop Tee` | HIGH, `catalog_exact` | each is a mark of the catalog |
| `Zibblefrox Hoodie` | MEDIUM, `catalog_fuzzy` | one letter away from `Zibblefrax` |
| `Wobblefrump Lantern Goods Candle`, `Mumblefrost Narwhal Plush` | HIGH, `catalog_phrase` | the whole multi-word mark |
| `Handmade Wobblefrump Lantern` | nothing | only part of the phrase |
| `Ancient Dribblefrop Tote Bag` | nothing | the mark is `CANCELLED`, dropped by the status filter |
| `Frumblecrast Desk Lamp` | nothing | Nice class 9, dropped by the class filter |

To show why a score never stands alone, fill every field of an Etsy listing (the "Other listing fields" section too), put
`Zibblefrax` in the title, and press Check: the readiness panel says Ready and the trademark panel says HIGH, side by side.

## Run it locally (Windows PowerShell, Docker Desktop)

These commands were run end to end once: build, import, sign-in, `plan set`, and a check of `Zibblefrax Style Ceramic Mug`
(readiness Ready next to trademark HIGH). Run them from the repository root, in one PowerShell window.

```powershell
Set-Location "D:\Listing Guard"

# 1. Settings for this PowerShell window only. Nothing is written to .env.
$env:API_PROFILES = "dev"                            # dev profile: sign-in links go to the log, no e-mail is sent
$env:LISTING_GUARD_CONSOLE_ENABLED = "true"
$env:LISTING_GUARD_CONSOLE_COOKIE_SECURE = "false"   # plain http://localhost; only the dev profile accepts this
$env:PLANS_ENABLED = "true"
# If port 8080 or 5432 is taken, also set $env:API_PORT = "18080" and $env:POSTGRES_PORT = "15432"
# (then use that port instead of 8080 below).

# 2. Build the image (a few minutes the first time) and start the database.
docker compose build api
docker compose up -d postgres

# 3. Import the DEMO catalog and keep the version the job prints.
$import = docker compose run --rm -T -e CATALOGS_DIR=/catalogs/demo trademark-import --catalog=demo-fictional-2026-11 | Out-String
$env:TRADEMARK_CATALOG_VERSION = [regex]::Match($import, 'Trademark catalog version (\S+) \(').Groups[1].Value
$env:TRADEMARK_CATALOG_VERSION          # prints demo-fictional-2026-11-<12 hex digits>; empty means the import failed

# 4. Start the API with the console, plans and the demo catalog.
docker compose up -d api
do { Start-Sleep -Seconds 3; try { $up = (Invoke-RestMethod "http://localhost:8080/health").status -eq "UP" } catch { $up = $false } } until ($up)

# 5. Sign in. Open the login page, enter an e-mail address (any address; nothing is sent) and submit it.
Start-Process "http://localhost:8080/login"
#    Then open the sign-in link that the API wrote to its log, and press "Sign in" on that page:
$link = ([regex]::Matches((docker compose logs api | Out-String), 'Development sign-in link: (\S+)') | Select-Object -Last 1).Groups[1].Value
Start-Process $link
#    You are on the dashboard now, on the FREE plan. Open http://localhost:8080/console/check: the cost is 1 unit and the
#    trademark panel says "Trademark not checked" (the FREE plan has no trademark check).

# 6. Move that user to the SELLER plan (it takes effect at once; reload the page, no restart).
$email = "demo@example.test"      # the address you signed in with, in lower case
$client = (docker compose exec -T postgres psql -U listingguard -d listingguard -tAc "SELECT c.name FROM account a JOIN client c ON c.id = a.client_id WHERE a.email = '$email'").Trim()
docker compose run --rm -T admin plan set $client SELLER
#    Reload http://localhost:8080/console/check: the cost is now up to 2 units and the trademark panel works.
```

When you are done:

```powershell
docker compose down        # stops everything and keeps the database
docker compose down -v     # stops everything and deletes the database, so the next run starts clean
Remove-Item Env:API_PROFILES, Env:LISTING_GUARD_CONSOLE_ENABLED, Env:LISTING_GUARD_CONSOLE_COOKIE_SECURE, Env:PLANS_ENABLED, Env:TRADEMARK_CATALOG_VERSION
```

Notes:

- The version printed in step 3 is the label plus a digest of the file, the filters and the trademark ruleset, so it changes
  if any of them changes. The importer refuses the file if its sha256 differs from the manifest (the `.gitattributes` rule
  keeps the file's line endings LF on Windows for this reason).
- Importing again with unchanged files finds the same version and changes nothing.
- The catalog is append-only. To start over, `docker compose down -v`.
- Never serve this catalog where other people use the API: it would call invented words a trademark risk.
