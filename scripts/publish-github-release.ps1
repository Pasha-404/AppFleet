[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')]
    [string]$Repository,
    [Parameter(Mandatory)]
    [ValidatePattern('^v\d+\.\d+\.\d+(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$')]
    [string]$Tag,
    [Parameter(Mandatory)]
    [ValidateScript({ Test-Path $_ -PathType Container })]
    [string]$ReleaseDirectory
)

$token = $env:GITHUB_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) { throw 'GITHUB_TOKEN is required only for publishing the GitHub Release.' }

$version = $Tag.Substring(1)
$directory = (Resolve-Path $ReleaseDirectory).Path
$installer = Join-Path $directory "AppFleet-Setup-$version-x64.exe"
$checksum = "$installer.sha256"
$manifest = Join-Path $directory 'appfleet-manifest.json'
$assets = @($installer, $checksum, $manifest)
foreach ($asset in $assets) { if (-not (Test-Path $asset -PathType Leaf)) { throw "Required release asset is missing: $asset" } }

$headers = @{
    Accept = 'application/vnd.github+json'
    Authorization = "Bearer $token"
    'X-GitHub-Api-Version' = '2022-11-28'
    'User-Agent' = 'AppFleet-release-publisher'
}
$body = @"
## AppFleet $version

Первый стабильный выпуск AppFleet для Windows x64.

### Установка

1. Скачайте `AppFleet-Setup-$version-x64.exe`.
2. При необходимости сверьте SHA-256 с одноимённым файлом `.sha256`.
3. Запустите установщик. Java отдельно не требуется.

### Assets

- `AppFleet-Setup-$version-x64.exe` — Inno Setup installer.
- `AppFleet-Setup-$version-x64.exe.sha256` — контрольная сумма установщика.
- `appfleet-manifest.json` — метаданные релиза для самообновления AppFleet.

Установщик пока не подписан Authenticode: сертификат не передавался в сборку. AppFleet проверяет опубликованный SHA-256 перед самообновлением.
"@
$payload = [ordered]@{
    tag_name = $Tag
    target_commitish = $env:GITHUB_SHA
    name = "AppFleet $version"
    body = $body.Trim()
    draft = $true
    prerelease = $false
    generate_release_notes = $false
} | ConvertTo-Json -Compress

$releases = @(Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/releases?per_page=100" -Headers $headers)
$published = @($releases | Where-Object { $_.tag_name -eq $Tag -and -not $_.draft })
if ($published.Count -gt 0) { throw "Release $Tag already exists and is published." }
$release = @($releases | Where-Object { $_.tag_name -eq $Tag -and $_.draft } | Sort-Object created_at -Descending | Select-Object -First 1)
if ($release.Count -eq 0) {
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/releases" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $payload
} else {
    $release = $release[0]
}
try {
    $uploadUrl = $release.upload_url.Split('{')[0]
    $existingAssets = @($release.assets | ForEach-Object name)
    foreach ($asset in $assets) {
        $assetName = Split-Path $asset -Leaf
        if ($existingAssets -contains $assetName) { continue }
        $name = [System.Uri]::EscapeDataString($assetName)
        Invoke-WebRequest -Uri "${uploadUrl}?name=$name" -Method Post -Headers $headers -ContentType 'application/octet-stream' -InFile $asset -UseBasicParsing | Out-Null
    }
    $published = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/releases/$($release.id)" -Method Patch -Headers $headers -ContentType 'application/json; charset=utf-8' -Body '{"draft":false}'
    Write-Output "Published $($published.html_url) with $($assets.Count) assets."
} catch {
    throw "Release $Tag remains a draft because not every asset was uploaded: $($_.Exception.Message)"
}
