# Contributing to wBooks

wBooks is a personal open-source project. The codebase is open for review and learning, and contributions are welcome.

## Code of Conduct

Be respectful. Follow the standard open-source courtesy: good-faith discussion, no spam, no harassment.

## Reporting Issues

If you find a bug or want to suggest a feature, open an issue on GitHub. Include:
- What you were doing when the issue occurred
- Device model and Wear OS version
- Steps to reproduce (if applicable)
- Expected vs. actual behavior

## Submitting Pull Requests

1. Fork the repository and create a feature branch: `git checkout -b feature/your-feature-name`
2. Make your changes. Follow the code style of the existing codebase.
3. Write or update tests if you add new functionality or fix a bug.
4. Commit with a clear, descriptive message. Use imperative mood ("Add X" not "Added X").
5. Push to your fork and open a pull request against `main`.

### Code style

- **Kotlin**: Follow [Android Kotlin style guide](https://android.github.io/kotlin-guides/).
- **Naming**: Be explicit. Prefer clarity over brevity.
- **Comments**: Only add comments for non-obvious logic. The code itself should be self-documenting.
- **No trailing whitespace**. Use LF line endings (git handles CRLF on Windows automatically).

### What gets reviewed

- **Correctness**: Does the code work as intended?
- **Compatibility**: Does it work on all supported Wear OS versions (3.0+)?
- **Performance**: Does it avoid unnecessary allocations or blocking I/O?
- **Testing**: Are critical paths tested?
- **Scope**: Does it fit the app's design principles (touch-first, reflow-friendly)?

### What won't be accepted

- Features that only work with a bezel/crown (touch must work)
- Breaking changes to the reading mode input model
- Large UI redesigns without prior discussion
- Reverting experimental PDF support without community discussion

## Development Workflow

See [Development Setup](README.md#development-setup) in the README for environment configuration.

### Running tests

```bash
./gradlew test
```

Tests cover the parser modules (DocxParser, OdtParser, transfer protocols). Before submitting, ensure tests pass locally.

### Building and testing on device

```bash
./gradlew assembleDebug
```

Then install using the pairing instructions in the README.

For the phone companion app:

```bash
./gradlew companion:assembleDebug
```

Requires Android 7.0+ on the phone and a paired Wear OS watch with wBooks installed.

### Code review workflow

1. PR is opened → GitHub Actions runs `assembleDebug` + `test`
2. Reviewer checks correctness and style
3. Author addresses feedback
4. Merge to `main` when approved

## Questions?

Open an issue or start a discussion on GitHub. For security issues, email privately if appropriate.

Thank you for contributing!
