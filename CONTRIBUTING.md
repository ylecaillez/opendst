# Contributing to OpenDST

Thank you for your interest in contributing to OpenDST! This document provides guidelines for contributing to the project.

## Prerequisites

- **Java 25** (required)
- **Maven 3.9+**

## Building the Project

```bash
# Build all modules
mvn clean install

# Run integration tests
mvn verify -pl opendst-maven-plugin
```

Note: The `opendst-examples` module is expected to fail by design (it demonstrates bug detection). Do not include it in build verification.

## Code Style

This project uses [Palantir Java Format](https://github.com/palantir/palantir-java-format) enforced via Spotless. Before submitting a pull request:

```bash
mvn spotless:apply
```

The build uses `-Werror`, so all compiler warnings must be resolved.

## Project Structure

| Module | Description |
|--------|-------------|
| `opendst-sdk` | Public API stubs and types (the only compile dependency users need) |
| `opendst-agent` | Simulation engine and bytecode rewriting agent |
| `opendst-maven-plugin` | Maven plugin for running DST tests |
| `opendst-examples` | Example applications demonstrating OpenDST |

## Submitting Changes

1. Fork the repository and create a feature branch from `main`.
2. Make your changes, ensuring code compiles without warnings and tests pass.
3. Run `mvn spotless:apply` to format your code.
4. Submit a pull request with a clear description of the changes.

## Reporting Issues

Please use [GitHub Issues](https://github.com/pingidentity/opendst/issues) to report bugs or request features.

## License

By contributing to OpenDST, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
