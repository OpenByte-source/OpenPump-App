# 0003. JUnit 5, adopted incrementally

Date: 2026-09-22 · Status: accepted

## Context
About 35,700 assertions live in a custom harness (SelfTest, Migrate, WiringCheck, Scenarios). Contributors expect JUnit and IDE integration.

## Decision
New tests are JUnit 5. The original harnesses run unchanged as Gradle tasks inside `./gradlew test` and migrate into JUnit suites module by module.

## Consequences
No big-bang rewrite of tests; both styles coexist for a while. Nothing is lost in the move.
