package ru.pashaapps.appfleet.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** A canonical public github.com owner/repository pair. */
public record RepositoryId(String owner, String repository) {
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_.-]+", Pattern.CASE_INSENSITIVE);

    public RepositoryId {
        if (!PART.matcher(owner).matches() || !PART.matcher(repository).matches()) {
            throw new IllegalArgumentException("Некорректное имя владельца или репозитория GitHub");
        }
    }

    public static RepositoryId fromGithubUrl(String rawUrl) {
        try {
            URI uri = new URI(Objects.requireNonNull(rawUrl, "Ссылка не задана").trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Разрешены только HTTPS-ссылки на github.com");
            }
            List<String> parts = List.of(uri.getPath().split("/"));
            if (parts.size() < 3 || parts.get(1).isBlank() || parts.get(2).isBlank()) {
                throw new IllegalArgumentException("Ожидается ссылка на репозиторий GitHub");
            }
            if (parts.size() > 3 && !"releases".equalsIgnoreCase(parts.get(3))) {
                throw new IllegalArgumentException("Разрешена ссылка на репозиторий или его Releases");
            }
            if (parts.size() > 4 && !("tag".equalsIgnoreCase(parts.get(4)) && parts.size() == 6 && !parts.get(5).isBlank())) {
                throw new IllegalArgumentException("Некорректная ссылка на GitHub Releases");
            }
            return new RepositoryId(parts.get(1), parts.get(2));
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("Некорректная ссылка GitHub", invalid);
        }
    }

    public String canonicalUrl() { return "https://github.com/" + owner + "/" + repository; }
    public String slug() { return owner + "/" + repository; }
    public String normalizedKey() { return slug().toLowerCase(Locale.ROOT); }
}

