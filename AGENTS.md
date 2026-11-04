LogicBench — agent rules

Project: a Logisim-Evolution-style logic gate simulator in Java 25 (Swing + FlatLaf).
Goal: simple, flat, utilitarian UI. No rounded corners, gradients, shadows, or animations.
Match docs/reference/ screenshots.

## Stack
- Java 25 (Gradle toolchain), Gradle Kotlin DSL + wrapper, JUnit 5
- Later: FlatLaf, JSVG (SVG rendering), Gson (save/load)
- Do NOT add other dependencies without asking in the PR description.

## Architecture rules
- Packages under io.github.evilalee.gatelab: model, sim, ui, io
- `model` and `sim` must NOT import java.awt, javax.swing, or anything GUI. They must be testable headless.
- Grid is 10 units. All pins and components sit on the grid. Wires are orthogonal only.
- reference/js/ is read-only legacy code. Never edit it, never copy it line by line; port the logic idiomatically.

## Process
- Work only on the task given. No drive-by refactors, no unrequested features.
- Every PR: `./gradlew test` passes; new logic has tests.
- PR description must list: what changed, any behavior that differs from reference/js, open questions.
- If the spec is ambiguous, pick the simplest option and note it in the PR; don't guess silently.
- Keep classes small; comment the *why*, not the what.
