# TripPulse --- Notes, Passenger Context and SOS

## 1. Quick notes

Driver can record short context:

-   passenger joined
-   passenger left
-   medicine
-   vehicle issue
-   route change
-   extended break
-   accommodation
-   other

Automatic metadata:

-   time
-   location
-   trip state
-   accuracy

## 2. Structured passenger events

Examples:

``` text
PASSENGER_JOINED
PASSENGER_LEFT
```

Optional text:

> Rahul joined near Kurnool.

Avoid collecting unnecessary personal data.

## 3. Medicine

Medicine is sensitive.

Store as sensitive event.

Viewer can see a generic event:

> Medicine recorded

unless trip privacy settings permit details.

Never diagnose or infer health conditions.

## 4. SOS

Activation requires deliberate long press.

On activation:

1.  capture GPS
2.  create immutable SOS event locally
3.  prioritize synchronization
4.  notify authorized recipients when delivered
5.  continue high-priority current-state updates
6.  allow deliberate resolution

## 5. Offline SOS

If offline:

``` text
SOS saved locally.
Network unavailable.
Will transmit automatically when connected.
```

Never claim delivery until acknowledged.

## 6. Possible incident

Future sensor-based detection may produce:

`POSSIBLE_INCIDENT`

Never call it an accident until confirmed.

## 7. SOS resolution

Driver can:

`I'm Safe → confirm → SOS_RESOLVED`

Original SOS event remains in history.

## 8. Emergency contacts

Separate emergency recipients from ordinary viewers.

Emergency recipients receive only the minimum necessary information.

## 9. Notifications

Normal:

> A new trip update is available.

SOS:

> TripPulse SOS activated. Open TripPulse for the authorized live
> incident view.

Avoid sensitive details in notification text.

## 10. Retention

SOS/incident events may have longer retention than ordinary notes,
subject to product privacy policy.

## 11. No automatic surveillance

Do not use microphone, camera or continuous audio recording for this
feature.
