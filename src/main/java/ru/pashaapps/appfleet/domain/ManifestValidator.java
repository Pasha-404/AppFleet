package ru.pashaapps.appfleet.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict schema-1 manifest validator. Invalid metadata falls back to asset analysis. */
public final class ManifestValidator {
    private static final Pattern TECHNICAL_NAME = Pattern.compile("[A-Za-z0-9_-]+$");
    private static final Set<String> INNO_ARGUMENTS = Set.of("/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS", "/RESTARTAPPLICATIONS");
    private final ObjectMapper mapper;

    public ManifestValidator(ObjectMapper mapper) { this.mapper = mapper; }

    public AppFleetManifest validate(byte[] contents, RepositoryId repository, GithubRelease release) {
        try {
            JsonNode root = mapper.readTree(contents);
            require(root.isObject(), "Манифест должен быть JSON-объектом");
            require(root.path("schemaVersion").asInt(-1) == 1, "Поддерживается только schemaVersion 1");
            UUID appId = UUID.fromString(requiredText(root, "appId"));
            String name = requiredText(root, "name");
            String technicalName = requiredText(root, "technicalName");
            require(TECHNICAL_NAME.matcher(technicalName).matches(), "Некорректное technicalName");
            String version = requiredText(root, "version");
            require(SemVersion.tryParse(version).isPresent(), "Версия манифеста должна быть SemVer");
            require(SemVersion.parse(version).equals(SemVersion.parse(release.tagName())), "Версия манифеста не соответствует tag релиза");
            String repositoryUrl = requiredText(root, "repositoryUrl");
            require(RepositoryId.fromGithubUrl(repositoryUrl).equals(repository), "repositoryUrl манифеста не соответствует релизу");
            require("windows".equalsIgnoreCase(requiredText(root, "platform")), "Манифест не предназначен для Windows");
            require("x64".equalsIgnoreCase(requiredText(root, "architecture")), "Поддерживается только x64");
            JsonNode installerNode = requiredObject(root, "installer");
            PackageType type = PackageType.fromManifest(requiredText(installerNode, "type").toLowerCase(Locale.ROOT));
            String assetName = requiredText(installerNode, "assetName");
            ReleaseAsset asset = release.assets().stream().filter(candidate -> candidate.name().equals(assetName)).findFirst()
                    .orElseThrow(() -> invalid("assetName манифеста отсутствует в текущем релизе"));
            require(asset.packageType() == (type == PackageType.INNO ? PackageType.EXE : type), "Тип файла не соответствует installer.type");
            String sha256AssetName = optionalText(installerNode, "sha256AssetName");
            if (type == PackageType.INNO) require(sha256AssetName != null, "Для Inno Setup требуется SHA-256 asset");
            if (sha256AssetName != null) require(release.assets().stream().anyMatch(candidate -> candidate.name().equals(sha256AssetName)), "SHA-256 asset отсутствует в текущем релизе");
            List<String> silentArgs = strings(installerNode.path("silentArgs"));
            if (type == PackageType.INNO) require(silentArgs.stream().allMatch(INNO_ARGUMENTS::contains), "Манифест содержит недопустимый аргумент Inno Setup");
            AppFleetManifest.Detection detection = null;
            if (root.has("detection")) {
                JsonNode detectionNode = requiredObject(root, "detection");
                detection = new AppFleetManifest.Detection(requiredText(detectionNode, "registryKey"), requiredText(detectionNode, "versionValue"), requiredText(detectionNode, "executableValue"));
            }
            List<String> processNames = strings(root.path("processNames"));
            String minimum = optionalText(root, "minimumAppFleetVersion");
            if (minimum != null) require(SemVersion.tryParse(minimum).isPresent(), "minimumAppFleetVersion должна быть SemVer");
            return new AppFleetManifest(1, appId, name, technicalName, SemVersion.parse(version).normalized(), repository.canonicalUrl(), "windows", "x64",
                    new AppFleetManifest.Installer(type, assetName, sha256AssetName, silentArgs), detection, processNames, minimum);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid("Не удалось прочитать appfleet-manifest.json", failure);
        }
    }

    private static JsonNode requiredObject(JsonNode node, String field) { JsonNode value = node.path(field); require(value.isObject(), "Отсутствует объект " + field); return value; }
    private static String requiredText(JsonNode node, String field) { String value = optionalText(node, field); require(value != null, "Отсутствует строка " + field); return value; }
    private static String optionalText(JsonNode node, String field) { JsonNode value = node.path(field); return value.isTextual() && !value.asText().isBlank() ? value.asText() : null; }
    private static List<String> strings(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        require(node.isArray(), "Ожидается массив строк");
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) { require(value.isTextual() && !value.asText().isBlank() && value.asText().indexOf('\u0000') < 0, "Недопустимый аргумент манифеста"); values.add(value.asText()); }
        return List.copyOf(values);
    }
    private static void require(boolean condition, String message) { if (!condition) throw invalid(message); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Некорректный appfleet-manifest.json: " + message); }
    private static IllegalArgumentException invalid(String message, Exception cause) { return new IllegalArgumentException("Некорректный appfleet-manifest.json: " + message, cause); }
}

