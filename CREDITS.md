# Credits

Samsung Revive is built on [HTC Revive](https://github.com/h33ted/htc-revive) and reuses its
local HTTP server, request log and outbound TLS bridge.

Outbound TLS (the app's own HTTPS fetches on Android 2.x and 4.x) comes from HTC Revive's
wolfSSL bridge, shipped here prebuilt as `libs/armeabi/librevivetls.so` with its Java side in
`src/com/htcrevive/tls` (the package name is kept for the JNI symbols). That bridge is built on
two GPL-3.0 projects:

- **Wolfius**: https://github.com/gohoski/Wolfius (© gohoski), GPL-3.0.
- **wolfSSL**: https://github.com/wolfSSL/wolfssl, GPL-3.0.

The native sources and build configuration are in the HTC Revive repository. Because Samsung
Revive links wolfSSL, it is distributed as a whole under GPL-3.0-or-later. See LICENSE.

The root certificate store (`assets/roots.pem`) is Mozilla's CA bundle as distributed by the
curl project (https://curl.se/ca/cacert.pem), MPL-2.0.

`assets/accucities.tsv` maps AccuWeather city ids to names. It's extracted by
`tools/gen-accucities.py` from the offline city list in the Galaxy S4's stock weather app, so
that cities the phone already knows by id can be geocoded.

## Data sources

- **Weather**: [Open-Meteo](https://open-meteo.com/) forecast and geocoding APIs, data under
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Reverse geocoding for GPS
  locations uses BigDataCloud's free client endpoint.
- **Stocks**: Yahoo Finance's public chart and search endpoints (unofficial, keyless; may
  change or rate-limit at any time).
- **News**: whatever RSS/Atom feeds you configure (BBC and ORF by default); articles and images
  belong to their publishers.

Samsung, Galaxy, TouchWiz, AccuWeather, Yahoo and AP are trademarks of their respective owners.
Samsung Revive is an independent project and isn't affiliated with any of them.
