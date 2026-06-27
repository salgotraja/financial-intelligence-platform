# Contributing

Thanks for your interest in the Financial Intelligence Platform. This is a serverless,
event-driven reference project (EventBridge to Step Functions to Lambda to Bedrock to
DynamoDB/S3 to API Gateway) built on Java 25 and Spring Cloud Function. Contributions of all
sizes are welcome: bug fixes, tests, documentation, and new capabilities.

By participating you agree to keep discussion respectful and constructive.

## Prerequisites

- Java 25 (LTS). The toolchain is pinned in `.sdkmanrc`. With sdkman installed, run `sdk env`
  (or `sdk env install` the first time) from the repo root before building.
- The Maven wrapper (`./mvnw`) is included, so no separate Maven install is needed.
- For infrastructure work: Node.js with the AWS CDK CLI (`npm install -g aws-cdk`), and AWS
  credentials for a CDK-bootstrapped account. The stacks are written in Java; the CDK CLI is the
  only Node dependency.

## Build and test

Always run the Maven reactor from the repository root.

```bash
./mvnw clean package                                 # build and unit test all modules
./mvnw test                                           # unit tests only
./mvnw test -pl functions/ingestion-function          # a single module
./mvnw -Dtest=ClassName#method test -pl <module>      # a single test method
./mvnw verify                                          # full check including the format gate (CI gate)
```

Please add or update tests for any behavior you change, and make sure `./mvnw verify` is green
before opening a pull request.

## Code style

Formatting is enforced by Spotless using palantir-java-format with 4-space indentation. Do not use
google-java-format. Run the formatter before committing:

```bash
./mvnw spotless:apply        # auto-format
./mvnw spotless:check        # verify (also part of ./mvnw verify)
```

A few project conventions to follow:

- Request and response types are Java records.
- Lambda handlers are Spring Cloud Function beans selected by the `SPRING_CLOUD_FUNCTION_DEFINITION`
  environment variable.
- Keep modules cohesive and loosely coupled; prefer the simplest change that solves the problem.
- Avoid em-dashes and emojis in code, comments, and documentation.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org): `type(scope): short description`,
for example `fix(ingestion): url-encode the ticker path`. Keep the subject concise and in the
imperative mood. Reference an issue number when one applies.

## Pull requests

1. Create a focused branch for your change.
2. Make the change, add tests, and run `./mvnw verify` until it passes.
3. Run `./mvnw spotless:apply` so formatting is clean.
4. Open a pull request describing what changed and why, and link any related issue.

Smaller, focused pull requests are easier to review and land faster.

## Project layout

See [`README.md`](./README.md) for the high-level overview, and
[`docs/architecture.md`](./docs/architecture.md) and [`docs/spec.md`](./docs/spec.md) for the
architecture and specification.

## Reporting issues

Open a GitHub issue with clear steps to reproduce, the expected and actual behavior, and any
relevant logs. For security-sensitive reports, please contact the maintainer privately rather than
filing a public issue.

## License

By contributing, you agree that your contributions are licensed under the
[MIT License](./LICENSE).
