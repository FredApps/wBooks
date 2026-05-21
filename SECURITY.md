# Security Policy

## Reporting a vulnerability

If you discover a security vulnerability in wBooks, please report it responsibly by emailing the maintainers privately rather than opening a public issue.

**Do not** open a GitHub issue for security vulnerabilities.

### Reporting guidelines

- Describe the vulnerability clearly and provide steps to reproduce
- Include your assessment of the severity if possible
- Allow time for a fix before any public disclosure
- Avoid disclosing the issue publicly until a patch is available

## Scope

Security issues in wBooks itself (the code in this repository) are in scope. This includes:
- Authentication and authorization bugs
- Data handling and privacy issues
- Cryptographic flaws
- Input validation issues
- Dependency vulnerabilities

Issues in dependencies are generally handled by upstream maintainers. If a dependency has a known vulnerability, it will be updated in a timely manner.

## What to expect

- Acknowledgment of your report within 7 days
- Updates on progress as we work on a fix
- A security advisory and patch release when the fix is ready
- Public credit for the vulnerability report (unless you prefer anonymity)

## Security best practices

**For users:**
- Keep wBooks and your watch OS updated
- Only download books from trusted sources
- Review app permissions before installing updates
- Use the LAN upload server only on trusted networks

**For developers:**
- Review the code before building from source
- Verify the GPLv3 license terms
- Report any security concerns responsibly
- Keep dependencies updated

## Privacy

wBooks is designed with privacy in mind:
- No user tracking or analytics (without explicit opt-in)
- All reading data stored locally on your watch
- Crash reporting disabled by default
- No account or login required
- No cloud sync of personal data

See the README for details on optional crash reporting configuration.
