# TripPulse --- ETA and Journey Intelligence Specification

## 1. Purpose

TripPulse ETA is a **human journey forecast**, not simply road travel
time.

## 2. ETA model

``` text
Realistic ETA =
route travel time
+ future break budget
+ fuel/charging budget
+ meal/refreshment budget
+ rest budget
+ uncertainty buffer
```

## 3. Minimum realism buffer

For sufficiently long trips, maintain a configurable minimum future
break/refreshment budget. Initial default: 60 minutes.

The model may use more when actual journey context predicts it.

Never remove realistic break time merely to make ETA look attractive.

## 4. Dynamic break budget

At any point calculate:

``` text
remaining driving
expected future breaks
expected duration of each
already completed breaks
typical driver pattern
fuel/charge needs
overnight likelihood
uncertainty
```

Every meaningful stop updates the forecast.

## 5. First-trip defaults

Until personal history exists, use conservative configurable planning
defaults.

Example configuration:

``` json
{
  "minimumLongTripBreakBudgetMinutes": 60,
  "shortRefreshmentMinutes": 15,
  "toiletWaterMinutes": 15,
  "mealMinutes": 30,
  "restMinutes": 30,
  "fuelMinutes": 15,
  "etaUncertaintyMinutes": 20
}
```

These are product planning parameters, not medical recommendations.

## 6. Range output

Prefer:

``` text
Likely arrival: 10:45–11:15 PM
Most likely: 11:00 PM
```

over false precision.

## 7. ETA explanation

Viewer can expand:

``` text
Road travel       6h 05m
Food                 30m
Refreshment          25m
Bio breaks           15m
Rest                 30m
Fuel                 15m
Uncertainty          20m
```

## 8. Overnight

If stationary \>2 hours, trigger accommodation prompt.

If confirmed:

``` text
trip mode = OVERNIGHT
```

ETA becomes dependent on expected restart.

If restart time is unknown:

``` text
Arrival: pending morning departure
```

Do not invent a morning departure.

## 9. Route deviation

Trigger after persistent meaningful deviation, not a single GPS jump.

Reasons may include:

-   detour
-   fuel
-   food
-   road closure
-   traffic diversion

## 10. Personalization

After enough trips, estimate:

-   average stop duration
-   break frequency
-   meal duration
-   fuel duration
-   typical overnight pattern

Do not learn from medical assumptions.

## 11. AI

Do not use an LLM for core ETA calculation.

Use deterministic calculations first.

AI can later summarize:

> "You have completed 60% of the journey. Two planned refreshment breaks
> remain in the current forecast."

The source facts must come from structured data.
