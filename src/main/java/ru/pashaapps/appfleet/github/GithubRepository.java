package ru.pashaapps.appfleet.github;

import ru.pashaapps.appfleet.domain.RepositoryId;

public record GithubRepository(RepositoryId id, String displayName, String description, boolean isPrivate, boolean archived) { }

