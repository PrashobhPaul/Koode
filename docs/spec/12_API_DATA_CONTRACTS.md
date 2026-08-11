# TripPulse --- API and Data Contracts

Firebase is the primary transport, but the domain contracts remain
provider-neutral.

## 1. Trip creation

Logical command:

``` json
{
  "origin": {
    "lat": 17.385,
    "lng": 78.486
  },
  "destination": {
    "name": "Thrissur",
    "lat": 10.5276,
    "lng": 76.2144
  },
  "plannedDeparture": "2026-08-11T06:00:00+05:30",
  "settings": {
    "minimumLongTripBreakBudgetMinutes": 60
  }
}
```

Response:

``` json
{
  "tripId": "TP-7K4M-X92Q",
  "secret": "one-time-display-secret",
  "expiresAt": "..."
}
```

## 2. Current state

``` json
{
  "tripId": "...",
  "status": "DRIVING",
  "connectivity": "ONLINE",
  "location": {
    "lat": 10.123,
    "lng": 76.123,
    "accuracyM": 8
  },
  "lastLocationAt": "...",
  "eta": {
    "low": "...",
    "high": "...",
    "mostLikely": "..."
  },
  "distanceRemainingKm": 341,
  "batteryPercent": 62
}
```

## 3. Break event

``` json
{
  "type": "BREAK_CHECKPOINT",
  "eventTime": "...",
  "payload": {
    "water": true,
    "food": true,
    "toilet": false,
    "rest": true,
    "fuel": false,
    "charge": false
  },
  "source": "DRIVER_CONFIRMED"
}
```

## 4. Note

``` json
{
  "type": "QUICK_NOTE",
  "eventTime": "...",
  "payload": {
    "category": "PASSENGER_JOINED",
    "text": "Rahul joined near Kurnool",
    "sensitive": false
  },
  "source": "DRIVER_MANUAL"
}
```

## 5. Medicine

``` json
{
  "type": "MEDICINE_NOTE",
  "eventTime": "...",
  "payload": {
    "text": "Regular medication",
    "sensitive": true
  }
}
```

## 6. SOS

``` json
{
  "type": "SOS_ACTIVATED",
  "eventTime": "...",
  "payload": {
    "reason": "DRIVER_ACTIVATED"
  },
  "location": {
    "lat": 10.123,
    "lng": 76.123,
    "accuracyM": 7
  }
}
```

## 7. Event ACK

``` json
{
  "eventId": "...",
  "accepted": true,
  "serverReceivedAt": "...",
  "serverVersion": 1234
}
```

## 8. Error model

``` json
{
  "code": "TRIP_EXPIRED",
  "message": "This trip is no longer active.",
  "retryable": false
}
```

Do not expose internal database details.
