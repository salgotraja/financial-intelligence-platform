package dev.engnotes.authorizer.jwt;

import java.util.List;

/** A verified caller: the Cognito {@code sub} and the user-pool groups from {@code cognito:groups}. */
public record Principal(String sub, List<String> groups) {}
