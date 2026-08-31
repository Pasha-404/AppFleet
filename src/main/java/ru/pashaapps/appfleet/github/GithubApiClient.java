package ru.pashaapps.appfleet.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.pashaapps.appfleet.domain.GithubRelease;
import ru.pashaapps.appfleet.domain.ReleaseAsset;
import ru.pashaapps.appfleet.domain.RepositoryId;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Comparator;

/** Official GitHub REST API client; no GitHub HTML is parsed. */
public final class GithubApiClient {
    public static final URI DEFAULT_BASE_URI = URI.create("https://api.github.com/");
    private static final int TRANSIENT_REQUEST_ATTEMPTS = 3;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofMillis(250);
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final String userAgent;

    public GithubApiClient(HttpClient client, ObjectMapper mapper, URI baseUri, String userAgent) {
        this.client = client;
        this.mapper = mapper;
        this.baseUri = baseUri;
        this.userAgent = userAgent;
    }
    public GithubApiClient(HttpClient client, ObjectMapper mapper, String version) {
        this(client, mapper, DEFAULT_BASE_URI, "AppFleet/" + version);
    }

    public GithubResponse<GithubRepository> getRepository(RepositoryId repository, String etag) {
        GithubResponse<JsonNode> response = get("repos/" + repository.owner() + "/" + repository.repository(), etag);
        if (response.notModified()) return GithubResponse.notModified(response.etag());
        JsonNode root = response.body();
        return GithubResponse.success(new GithubRepository(repository, root.path("full_name").asText(repository.slug()), root.path("description").asText(""), root.path("private").asBoolean(), root.path("archived").asBoolean()), response.etag());
    }

    public GithubResponse<List<GithubRelease>> listReleases(RepositoryId repository, String etag) {
        GithubResponse<JsonNode> response = get("repos/" + repository.owner() + "/" + repository.repository() + "/releases?per_page=100", etag);
        if (response.notModified()) return GithubResponse.notModified(response.etag());
        if (!response.body().isArray()) throw new GithubApiException("GitHub вернул некорректный список релизов", 200, null);
        List<GithubRelease> releases = new ArrayList<>();
        for (JsonNode release : response.body()) releases.add(toRelease(release));
        return GithubResponse.success(List.copyOf(releases), response.etag());
    }

    public GithubRelease getReleaseByTag(RepositoryId repository, String tag) {
        return toRelease(get("repos/" + repository.owner() + "/" + repository.repository() + "/releases/tags/" + encodePathPart(tag), null).body());
    }

    public Optional<GithubRelease> latestStableRelease(RepositoryId repository, String etag) {
        GithubResponse<List<GithubRelease>> releases = listReleases(repository, etag);
        if (releases.notModified()) return Optional.empty();
        return releases.body().stream().filter(GithubRelease::isStable)
                .max(Comparator.comparing(GithubRelease::publishedAt));
    }

    private GithubResponse<JsonNode> get(String path, String etag) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .GET().timeout(REQUEST_TIMEOUT).header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", userAgent);
        if (etag != null && !etag.isBlank()) request.header("If-None-Match", etag);
        IOException lastNetworkFailure = null;
        for (int attempt = 1; attempt <= TRANSIENT_REQUEST_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                String responseEtag = response.headers().firstValue("ETag").orElse(null);
                if (response.statusCode() == 304) return GithubResponse.notModified(responseEtag == null ? etag : responseEtag);
                if (response.statusCode() >= 500 && attempt < TRANSIENT_REQUEST_ATTEMPTS) {
                    pauseBeforeRetry(attempt);
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw apiFailure(response);
                return GithubResponse.success(mapper.readTree(response.body()), responseEtag);
            } catch (IOException failure) {
                lastNetworkFailure = failure;
                if (attempt < TRANSIENT_REQUEST_ATTEMPTS) {
                    pauseBeforeRetry(attempt);
                    continue;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new GithubApiException("Запрос к GitHub был прерван", interrupted);
            }
        }
        throw new GithubApiException("Не удалось подключиться к GitHub после " + TRANSIENT_REQUEST_ATTEMPTS + " попыток", lastNetworkFailure);
    }

    private static void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(INITIAL_RETRY_DELAY.multipliedBy(1L << (attempt - 1)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GithubApiException("Ожидание повторного запроса к GitHub было прервано", interrupted);
        }
    }

    private GithubApiException apiFailure(HttpResponse<String> response) {
        Instant retryAt = response.headers().firstValue("X-RateLimit-Reset").flatMap(value -> {
            try { return Optional.of(Instant.ofEpochSecond(Long.parseLong(value))); } catch (NumberFormatException ignored) { return Optional.empty(); }
        }).orElse(null);
        String message = switch (response.statusCode()) {
            case 404 -> "Репозиторий не существует или не является публичным";
            case 403, 429 -> retryAt == null ? "GitHub временно ограничил запросы" : "GitHub временно ограничил запросы до " + retryAt;
            case 500, 502, 503, 504 -> "GitHub временно недоступен";
            default -> "GitHub вернул ошибку HTTP " + response.statusCode();
        };
        return new GithubApiException(message, response.statusCode(), retryAt);
    }

    private static GithubRelease toRelease(JsonNode node) {
        List<ReleaseAsset> assets = new ArrayList<>();
        for (JsonNode asset : node.path("assets")) {
            assets.add(new ReleaseAsset(asset.path("id").asLong(), asset.path("name").asText(), asset.path("size").asLong(), URI.create(asset.path("browser_download_url").asText()), asset.path("content_type").asText("")));
        }
        String timestamp = node.path("published_at").asText(null);
        if (timestamp == null || timestamp.isBlank()) timestamp = node.path("created_at").asText();
        return new GithubRelease(node.path("id").asLong(), node.path("tag_name").asText(), node.path("name").asText(""), node.path("body").asText(""), node.path("draft").asBoolean(), node.path("prerelease").asBoolean(), Instant.parse(timestamp), URI.create(node.path("html_url").asText()), assets);
    }

    private static String encodePathPart(String part) { return part.replace("/", "%2F").replace(" ", "%20"); }
}
