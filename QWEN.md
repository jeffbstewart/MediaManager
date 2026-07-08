## Project specifics

- **Language / build system:** Server: Kotlin + Gradle (JDK 25), with protocol buffer messages Client: Angular 22 + npm, not yet supported by the build harness
- **Dependencies are pre-cached.** Builds run offline. If you genuinely
  need a new dependency, you cannot add it yourself - say so and stop; a
  human must run the dependency airlock. Do not restructure the build to
  work around a missing library.
- **Layout:** read CLAUDE.md for details
