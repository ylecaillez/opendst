# Contributing to OpenDST

Welcome! OpenDST is a foundational tool for building reliable distributed systems through deterministic simulation testing. We follow a "Safety First" engineering philosophy inspired by the TigerBeetle and Antithesis models.

## Core Principles

1.  **Strict Determinism**: Any code added to the simulator or agent must be strictly deterministic. No unintercepted use of `System.currentTimeMillis()`, `new Random()`, or direct I/O.
2.  **Safety Assertions**: We use dense internal assertions to detect programmer errors early. Aim for at least two assertions per non-trivial function.
3.  **Zero-Allocation (Post-Init)**: Avoid dynamic memory allocation after the simulation has started where possible.
4.  **Modern Java**: We leverage the latest Java features (Virtual Threads, ClassFile API, Records, Sealed Classes).

## Getting Started

1.  **Environment**: You need JDK 25 or later and Maven.
2.  **Building**: Run `./mvnw clean install` to build the core modules and the Maven plugin.
3.  **Testing**:
    - Core logic is tested via unit tests in `opendst-agent`.
    - Integration tests are located in `opendst-maven-plugin/src/it/`. Run them with `mvn invoker:run`.

## How to Contribute

- **Bug Reports**: Use GitHub Issues. Provide a re-producible seed if possible.
- **Pull Requests**:
    - Each PR should focus on a single improvement.
    - Ensure all tests pass (`mvn clean install` followed by `mvn invoker:run`).
    - Adhere to the code style (enforced by Spotless).
    - Update `KNOWN_GAPS.md` if your change adds or identifies a gap in determinism.

## Audit and Safety

If you are adding instrumentation for a new JDK API:
1.  Check `InstrumentationAuditTest.java` to see how we audit intercepted methods.
2.  Document the interception using the `@Intercepts` annotation.
3.  Add a test case in `it-assertions` to verify the new API's deterministic behavior.
