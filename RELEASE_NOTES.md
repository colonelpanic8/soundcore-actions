Control wind reduction from EVA.

- Soundcore Actions is now an EVA extension. EVA discovers it automatically and can check wind reduction or turn it on, off, or toggle it on Liberty 5 Pro without opening Soundcore.
- EVA reports what the earbuds confirmed. When nothing could be sent, the earbuds rejected the change, or a change went unconfirmed, it says which, and a repeated request is never applied twice.
- A new invisible wind reduction activity (`SET_WIND_REDUCTION` with a `state` extra) serves apps that can only launch activities, including EVA's catalog Soundcore package.
- The wind reduction broadcast accepts an optional `state` extra (`on`, `off`, or `toggle`) for automation apps.
- A wind command started right after Soundcore launches waits briefly for the earbud connection instead of failing at once.
