# Contributing to CIB seven OpenTelemetry Plugin

Thank you for considering contributing to this project! Every contribution is appreciated.

## How to Contribute

### Reporting Bugs

- Open an issue on [GitHub Issues](https://github.com/cibseven-community-hub/cibseven-opentelemetry-plugin/issues)
- Include steps to reproduce, expected vs actual behaviour, and your environment (Java version, CIB seven version, Spring Boot version)

### Suggesting Features

- Open an issue with the `enhancement` label
- Describe the use case and why the feature would be valuable

### Submitting Code

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make your changes following the [code style guidelines](#code-style)
4. Add or update tests as appropriate
5. Run `mvn clean verify` to ensure all tests pass
6. Commit with a clear message describing the change
7. Open a Pull Request against `main`

## Code Style

This project follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html):

- **Indentation:** 2 spaces (no tabs)
- **Column limit:** 100 characters
- **Braces:** Always required, even for single-line bodies
- **Imports:** No wildcard imports; static imports first
- **Naming:** `UpperCamelCase` for classes, `lowerCamelCase` for methods/fields, `UPPER_SNAKE_CASE` for constants
- **Javadoc:** Required on all public classes and methods

## Building

```bash
# Compile and run tests
mvn clean verify

# Package without tests (for quick iteration)
mvn clean package -DskipTests
```

## Requirements

- Java 17+
- Maven 3.8+

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
