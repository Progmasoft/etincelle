<!-- SPDX-FileCopyrightText: 2026 Progmasoft <support@progmasoft.com> -->
<!-- SPDX-License-Identifier: MIT -->

# Development

Etincelle needs JDK 21 (Temurin); the Gradle wrapper fetches the rest.

```sh
./gradlew check jacocoTestReport   # tests, Spotless and coverage
./gradlew javadoc
```

CI runs the checks with coverage and builds the Javadoc.
