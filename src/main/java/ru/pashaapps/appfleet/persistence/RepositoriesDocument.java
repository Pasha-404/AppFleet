package ru.pashaapps.appfleet.persistence;

import java.util.List;

public record RepositoriesDocument(int schemaVersion, List<RepositoryState> repositories) {
    public RepositoriesDocument {
        if (schemaVersion != 1) throw new IllegalArgumentException("Неподдерживаемая версия списка репозиториев");
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
    }
    public static RepositoriesDocument empty() { return new RepositoriesDocument(1, List.of()); }
}
