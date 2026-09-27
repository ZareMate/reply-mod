# Reply Mod

NeoForge 1.21.1 / 21.1.249 server-side mod adding:

- `/r <message>`
- `/reply <message>`

Whenever a player successfully uses `/msg`, `/tell`, or `/w` on another online player, both players become each other's reply target.

Example:

```text
Alice: /msg Bob hello
Bob:   /r hi
Alice: /r hello again
```

The reply target is kept in memory for the server session.

## Build

Requires Java 21.

```bash
gradle build
```

The resulting JAR is in:

```text
build/libs/replymod-1.0.0.jar
```

NeoForge 1.21.1 uses Java 21 and this project targets NeoForge 21.1.249.
