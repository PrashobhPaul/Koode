# Taking Koode to a new country

Koode is built so that a new market is **data and translations, not code**.
This page is the checklist, in the order it is done, with what each step
touches. Japan is used as the worked example throughout.

## 1. The market row — `domain/Markets.kt`

Everything that differs by country is one row of `Market`:

| Field | What it decides | Japan |
|---|---|---|
| `units` | km or miles, km/h or mph, km/L or mpg | metric |
| `clock` | 12- or 24-hour, unless the phone or the traveller says otherwise | 24-hour |
| `dateOrder` | 5 Oct 2026 · Oct 5, 2026 · 2026/10/05 | YMD |
| `emergencyNumber`, `policeNumber` | the *Call …* button on the SOS screen | 119, 110 |
| `tolls` | whether plazas can be noticed, and what a pass is called in reports | ETC (no plaza data yet) |
| `bookingRefLabel` | what the ticket field is called | Reservation number |
| `fuelWord` | what the pump sells | Gasoline |
| `legal` | which privacy law the Privacy & data page cites | APPI |
| `languageTag` | the language most travellers read | ja-JP |
| `drivingSide` | pictures and wording that depend on it | left |

Currency comes from the ISO tables (`MoneyFormat.forCountry`), number
separators and symbol position from the phone's locale (`Measures.money`).
Countries without a row are worked out by rule (`Markets.forCountry`):
Europe/EEA under GDPR with a 24-hour clock, everyone else metric, 24-hour,
112, local law. **Nothing falls back to India except India.**

`MarketsTest` has one test per market. Add the row, add the test.

## 2. The words — `res/values-<lang>/strings.xml`

Every piece of UI text is a string resource keyed by a hash of its English
(`t_<slug>_<hash>`), so a translation never has to touch Kotlin. To add
Japanese:

1. Copy `res/values/strings.xml` to `res/values-ja/strings.xml`.
2. Translate the values. Keep the keys. Keep `\'` for apostrophes and
   leave `…`, `·` and `→` as they are.
3. Android falls back to English for any key you leave out, so a partial
   file is safe to ship while the rest is translated.

Names that come from the catalogue (how you travel, the settings choices,
expense categories) are resolved through `ui/Names.kt`, which maps each
catalogue key to a string resource; they translate in the same file.

### What is *not* in `strings.xml` yet

- **The journey story and the PDF report** (`domain/report/Prose.kt`,
  `JourneyStory.kt`, `Reports.kt`) are written by a narrative engine in
  English. Its seam is `JourneyStory.Input.measures` for units and
  `tollPassName` for wording; a second language is a second `Prose` object
  chosen by `Market.languageTag`, not a translation of templates, because
  the sentences are composed, not filled in.
- **Notifications** (`domain/JourneyUpdates.kt`, `EventNarrator.kt`) and
  the **web viewer** (`web/app.js`, `EVENT_LABELS` and the headline text)
  carry their English inline. They are the next extraction.
- **Pictures carry meaning on purpose**: every mode, every wellbeing item
  and every privacy point has an illustration (`domain/Pictures.kt`,
  `web/art/`), so the app reads at a glance before the words are read.

## 3. The manual and the rules

Japan reads manuals. The app has three places that must be true for the
market, all driven by the row above and by `docs/`:

- `docs/PRIVACY.md` and `docs/TERMS.md` are linked from the first screen and
  from Settings → Privacy & data. Translate them as `PRIVACY.ja.md`,
  `TERMS.ja.md`, and point `PrivacyNotice.POLICY_URL`/`TERMS_URL` at them by
  `languageTag`.
- The first-screen notice (`domain/PrivacyNotice.kt`) is four points with
  pictures; its `VERSION` rises when its substance changes, which asks every
  traveller to read it again.
- Toll noticing stays off until `TollSystem.hasPlazaData` is true for the
  scheme; the data pipeline is `supabase/schema.sql` (`tp_toll_refresh_*`),
  which today pulls OpenStreetMap `barrier=toll_booth` nodes for India. A new
  country is a new bounding box there.

## 4. The web viewer and the marketing page

- `web/app.js` reads `meta.units` from the journey, so a follower sees the
  traveller's units whatever their own.
- `README.md` leads with pictures (modes, wellbeing, lines on the map) so it
  carries across languages; the "Works where you live" table is generated
  from the same facts as `Markets.kt`.

## 5. Checklist for a launch

- [ ] `Markets.kt` row + `MarketsTest`
- [ ] `values-<lang>/strings.xml` (UI), `Names.kt` keys covered
- [ ] `PRIVACY.<lang>.md`, `TERMS.<lang>.md`, URLs switched by language
- [ ] Emergency number and police number verified against the official source
- [ ] Toll scheme: data or off
- [ ] Prose: English accepted, or a `Prose` for the language
- [ ] Web viewer strings
- [ ] Store listing and screenshots in the language (`scratchpad/mkt` renders them)
