# TripPulse --- UI/UX Specification

## 1. Same-app modes

Home:

``` text
TripPulse

[ Start New Trip ]
[ Join Existing Trip ]
```

## 2. Driver screen

``` text
TRIP IN PROGRESS

Hyderabad → Thrissur

642 / 1100 km

Likely arrival
10:45–11:15 PM

● LIVE
Last update: 18 sec

Last break: 35 min ago

[ + Quick Note ]

[ SOS ]

[ Pause ] [ End ]
```

## 3. Viewer screen

``` text
TRIP STATUS
● LIVE

MAP

Progress
642 / 1100 km

Likely arrival
10:45–11:15 PM

Journey
Driving 2h 17m
Last break 35m ago

Wellbeing
Water 35m
Food 3h 10m
Toilet 35m
Rest 35m

Timeline
...
```

## 4. Freshness badges

-   green: LIVE
-   amber: RECENT
-   orange: STALE
-   red: OFFLINE
-   gray: UNKNOWN

Use text in addition to color for accessibility.

## 5. Timeline

Combine:

-   movement
-   stops
-   breaks
-   notes
-   passenger events
-   medicine events according to privacy
-   incidents
-   SOS
-   overnight
-   arrival

Sort by event time.

## 6. Long stop

After 2h:

``` text
🌙 Long stop

Stopped for 2h 04m

Driver status:
Awaiting confirmation
```

If confirmed:

``` text
🏨 Overnight stay
```

## 7. SOS

Viewer changes to incident mode:

``` text
🚨 SOS ACTIVATED
Time
Location
Live/stale state
Battery
Network
[ Open Live Location ]
```

## 8. Quick Note

Viewer timeline:

``` text
👤 Passenger joined
"Rahul joined near Kurnool"
```

## 9. Sensitive notes

Do not expose medicine details in notification previews.

Show only according to trip viewer permissions.

## 10. Map

Show:

-   current location
-   route
-   destination
-   stop markers
-   important event markers
-   last known timestamp

Avoid excessive markers.

## 11. Driver interaction

Never require typing or questionnaires while moving.

Use one-tap actions at safe checkpoints.

## 12. Accessibility

-   readable typography
-   minimum touch targets
-   screen reader labels
-   text alternatives to color
-   high contrast
-   clear state labels

## 13. Failure states

Every failure must be understandable:

``` text
No GPS
No internet
Sync pending
Stale location
Trip expired
Invalid credentials
Battery low
```

Never show a blank map without explaining state.
