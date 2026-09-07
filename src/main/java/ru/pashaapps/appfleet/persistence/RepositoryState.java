package ru.pashaapps.appfleet.persistence;

import ru.pashaapps.appfleet.domain.Architecture;
import ru.pashaapps.appfleet.domain.PackageType;
import ru.pashaapps.appfleet.domain.RepositoryId;

import java.time.Instant;
import java.util.Set;

/** Persisted only after reliable discovery or a successful operation. */
public record RepositoryState(int schemaVersion, String owner, String repository, String canonicalUrl,
                              String selectedPackageType, String selectedArchitecture, Set<String> selectionTokens,
                              String installedVersion, Long installedAssetId, String installedPackageType,
                              String installLocation, String executable, Set<String> processNames,
                              String releaseEtag, Instant lastCheckedAt, String lastCheckResult) {
    public RepositoryState {
        if (schemaVersion != 1) throw new IllegalArgumentException("Неподдерживаемая версия записи репозитория");
        RepositoryId id = new RepositoryId(owner, repository);
        RepositoryId canonical = RepositoryId.fromGithubUrl(canonicalUrl);
        if (!id.normalizedKey().equals(canonical.normalizedKey())) {
            throw new IllegalArgumentException("Каноническая ссылка не соответствует владельцу и репозиторию");
        }
        validateEnum(selectedPackageType, PackageType.class, "тип выбранного пакета");
        validateEnum(selectedArchitecture, Architecture.class, "архитектура выбранного пакета");
        validateEnum(installedPackageType, PackageType.class, "тип установленного пакета");
        if ((installLocation == null) != (executable == null)) {
            throw new IllegalArgumentException("Неполные сведения об установленном приложении");
        }
        selectionTokens = selectionTokens == null ? Set.of() : Set.copyOf(selectionTokens);
        processNames = processNames == null ? Set.of() : Set.copyOf(processNames);
    }

    private static <E extends Enum<E>> void validateEnum(String value, Class<E> enumType, String field) {
        if (value == null) return;
        try {
            Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Некорректно сохранено поле: " + field, invalid);
        }
    }
}
