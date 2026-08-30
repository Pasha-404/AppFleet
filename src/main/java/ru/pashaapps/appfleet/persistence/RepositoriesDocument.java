package ru.pashaapps.appfleet.persistence;

import java.util.List;

public record RepositoriesDocument(int schemaVersion, List<RepositoryState> repositories) {
    public RepositoriesDocument { repositories = repositories == null ? List.of() : List.copyOf(repositories); }
    public static RepositoriesDocument empty() { return new RepositoriesDocument(1, List.of()); }
}

