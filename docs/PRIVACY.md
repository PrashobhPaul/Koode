# Koode Privacy Policy

*Effective: August 2026*

Koode ("Always with you") is a personal journey companion that keeps the
people you choose informed about your journey, wellbeing and safe arrival.
Privacy is a design constraint, not a setting.

## What Koode collects, and where it lives

| Data | Where it is stored | Who can see it |
|---|---|---|
| Your name (profile) | Your phone only; attached to a journey's shared data while that journey is live | People you approved for that journey |
| Saved locations (Home, Office, …) | Your phone only | Nobody but you |
| Emergency contacts | Your phone only | Nobody but you |
| Live location, ETA, journey events | Koode's backend **only while a journey you started is live** | Only people who hold the Journey ID **and** were approved by you by name (or hold the password you shared) |
| The next 2 km of your planned road | Koode's backend, with your live location, **only while a journey you started is live** | The same people as your live location; it lets their map draw your vehicle along the road between updates |
| Nothing (a download only) | Koode downloads the public list of metro, ferry and railway stations for the country you are in (India's comes with the app) from its own web host (GitHub Pages), at most monthly | Nobody: the request names only the country's file, and carries no account, journey or location |
| Journey history, replays, expenses | Your phone only | Nobody but you |

## What Koode does NOT do

- **No accounts.** There is no sign-up, no email, no phone-number registration.
- **No ads, no analytics, no trackers.** The app contains no advertising or
  analytics SDKs of any kind.
- **No selling or sharing of data.** Nothing is ever shared with third
  parties. Map tiles come from OpenStreetMap and routing from OSRM; those
  requests contain the coordinates being viewed but never your identity.
- **No background tracking.** Location is read **only** during a journey you
  explicitly started, shown by a persistent notification, and stops when the
  journey ends.

## Automatic deletion

Every journey **self-destructs**: one hour after *you end the journey* —
never merely because you arrived, and never because the app decided — viewer
access is cut off and all of the journey's cloud data (location trail, events,
live state, viewer names) is permanently deleted from the backend. The hour
exists so that someone who was asleep or on a plane can still open the link
and see that you got there safely. What remains afterwards exists only on your own phone,
under your control, until you delete it in the app.


## Your rights, in every country

Koode gives every traveller the same two controls, wherever they live, because
the laws that matter here — the EU and UK GDPR, India's Digital Personal Data
Protection Act, the US state privacy laws (CCPA/CPRA and their siblings) and
Japan's Act on the Protection of Personal Information — all come down to the
same promises: you know what is collected and why, it is kept no longer than
needed, and you can take it or take it back.

| Right | How | Where |
|---|---|---|
| **Access / portability** | *Export my data* writes everything Koode holds — journeys, events, positions, breaks, expenses, saved places, vehicles, followed journeys and settings — as one JSON file you can keep or send anywhere. Credentials that let a phone write to a live journey are left out. | Settings → Privacy & data |
| **Erasure** | *Erase everything* ends any live journey (so the server deletes its copy on its own one-hour timer), forgets push registrations for journeys you follow, then clears every table, every preference and the profile photo. The app is as installed afterwards. | Settings → Privacy & data |
| **Consent** | The privacy notice is shown before anything is collected, and shown again whenever its substance changes. Continuing past it is your consent; the version you accepted is recorded on the phone only. | First screen |
| **Objection / restriction** | There is nothing to object to that you did not start: location is read only during a journey you began, and stops when it ends. | Journey screen |

Nothing above needs an email, a form or a reply from anyone: there is no
account, so there is nobody to ask.

## Retention

| Data | Kept |
|---|---|
| Shared journey data on the server | Until one hour after you end the journey, then deleted |
| Approved journey report on the server | Until the journey's expiry, then deleted with it |
| Everything on your phone | Until you delete a journey, or erase everything |
| Push registration for a journey you follow | Until you unfollow it, the journey expires, or you erase everything |

## Where the server is

Koode's backend is a single Postgres project. Journeys started by travellers
in the EU/EEA, the UK and Japan are stored there like everyone else's, under
the same one-hour deletion rule. A regional deployment (an EU-resident
project chosen by country) is planned; until then, this is the honest
statement of where the data sits.

## Wellbeing information is factual, not medical

Koode records when you *log* things — a meal, water, a rest stop — and shows
your circle "last logged X ago". Koode never assesses, diagnoses or claims to
know your physical state.

## Your controls

- Approve or deny every viewer by name, per journey.
- Remove a journey from your history at any time (permanent local deletion).
- Uninstalling the app removes all local data.

## Contact

Questions: open an issue at https://github.com/PrashobhPaul/TripPulse.
