# Third-party notices

The Pit Boss BLE identity discovery and relay probe is an independent Kotlin implementation informed by public protocol observations in [dknowles2/pytboss](https://github.com/dknowles2/pytboss) and the [Home Assistant Pit Boss integration](https://github.com/dknowles2/ha-pitboss). No pytboss package or source file is included in the PitTech app.

The relay client uses [OkHttp 5.5.0](https://github.com/square/okhttp) by Square, Inc. (Copyright 2019 Square, Inc.), licensed under Apache-2.0. The protocol reference pytboss project also uses Apache-2.0. The full Apache-2.0 license text is included at [LICENSES/APACHE-2.0.txt](LICENSES/APACHE-2.0.txt).

The experimental GrillirG cloud monitor is an independent Kotlin implementation informed by the public API observations in [kingchddg901/ha-prime-polaris](https://github.com/kingchddg901/ha-prime-polaris/blob/main/docs/api.md) and PitTech's own vendor-APK investigation. No source from that integration is included in the app. JVM tests use JSON-java; that test dependency is not packaged in the Android app.
