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

function Get-FileDigest([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-LocalReleaseAssets {
    $installerHash = Get-FileDigest $installer
    $checksumText = (Get-Content -LiteralPath $checksum -Raw -Encoding UTF8).Trim()
    if ((Get-Item -LiteralPath $installer).Length -le 0) { throw 'Installer must not be empty.' }
    if ($checksumText -cne "$installerHash  $(Split-Path $installer -Leaf)") { throw 'Local SHA-256 asset does not match the local installer bytes.' }

    try { $manifestDocument = Get-Content -LiteralPath $manifest -Raw -Encoding UTF8 | ConvertFrom-Json -Depth 16 }
    catch { throw "Local appfleet manifest is not valid JSON: $($_.Exception.Message)" }
    if ($manifestDocument.schemaVersion -ne 1 -or $manifestDocument.version -cne $version -or $manifestDocument.installer.type -cne 'inno' -or
            $manifestDocument.installer.assetName -cne (Split-Path $installer -Leaf) -or $manifestDocument.installer.sha256AssetName -cne (Split-Path $checksum -Leaf)) {
        throw 'Local appfleet manifest does not describe this exact installer/checksum/version set.'
    }

    $result = @{}
    foreach ($asset in $assets) {
        $item = Get-Item -LiteralPath $asset
        if ($item.Length -le 0) { throw "Release asset is empty: $asset" }
        $result[$item.Name] = [PSCustomObject]@{ Path = $item.FullName; Name = $item.Name; Size = [int64]$item.Length; Sha256 = (Get-FileDigest $item.FullName) }
    }
    return $result
}

$headers = @{
    Accept = 'application/vnd.github+json'
    Authorization = "Bearer $token"
    'X-GitHub-Api-Version' = '2022-11-28'
    'User-Agent' = 'AppFleet-release-publisher'
}
$binaryHeaders = @{}
$headers.GetEnumerator() | ForEach-Object { $binaryHeaders[$_.Key] = $_.Value }
$binaryHeaders.Accept = 'application/octet-stream'
$localAssets = Get-LocalReleaseAssets
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

Перед самообновлением AppFleet проверяет опубликованный SHA-256 и, когда Windows предоставляет средство проверки, отображает фактический результат Authenticode. Отсутствие подписи не означает повреждение файла само по себе.
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

$releases = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/releases?per_page=100" -Headers $headers
$published = @($releases | Where-Object { $_.tag_name -eq $Tag -and -not $_.draft })
if ($published.Count -gt 0) { throw "Release $Tag already exists and is published." }
$draftCandidates = @($releases | Where-Object { $_.tag_name -eq $Tag -and $_.draft } | Sort-Object created_at -Descending | Select-Object -First 1)
if ($draftCandidates.Count -eq 0) {
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/releases" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $payload
} else {
    # @(... Select-Object -First 1) remains an Object[] in PowerShell. Keep
    # the selected release scalar: its .id must be an Int64 for the API helpers.
    $release = $draftCandidates[0]
}

function Assert-RemoteAssetMatches([object]$asset) {
    $expected = $localAssets[$asset.name]
    if ($null -eq $expected) { throw "Draft release contains an unexpected asset: $($asset.name). Delete or inspect the draft before retrying." }
    if ([int64]$asset.size -ne $expected.Size) { throw "Draft asset $($asset.name) has a different size. The draft must not be published with a mixed build." }
    $temporary = [System.IO.Path]::GetTempFileName()
    try {
        Invoke-WebRequest -Uri $asset.url -Headers $binaryHeaders -OutFile $temporary -UseBasicParsing | Out-Null
        if ((Get-FileDigest $temporary) -cne $expected.Sha256) { throw "Draft asset $($asset.name) has different contents. The draft must not be published with a mixed build." }
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    }
}

function Get-RemoteReleaseWithAssets([int64]$releaseId) {
    $draft = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/releases/$releaseId" -Headers $headers
    $remoteAssets = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/releases/$releaseId/assets?per_page=100" -Headers $headers
    # In PowerShell, wrapping a cmdlet invocation in @() can preserve a JSON
    # array as one nested Object[]. Flatten it so every asset remains an object.
    $draft.assets = @($remoteAssets | Write-Output)
    return $draft
}

function Assert-RemoteReleaseSet([object]$draft, [bool]$requireComplete) {
    $remoteAssets = @($draft.assets | Write-Output)
    $unexpected = @($remoteAssets | Where-Object { -not $localAssets.ContainsKey($_.name) })
    if ($unexpected.Count -gt 0) { throw "Draft release contains unexpected assets: $($unexpected.name -join ', '). Do not publish or overwrite this draft automatically." }
    $seen = @{}
    foreach ($asset in $remoteAssets) {
        if ($seen.ContainsKey($asset.name)) { throw "Draft release has duplicate asset name: $($asset.name)" }
        $seen[$asset.name] = $true
        Assert-RemoteAssetMatches $asset
    }
    if ($requireComplete -and ($seen.Count -ne $localAssets.Count -or @($localAssets.Keys | Where-Object { -not $seen.ContainsKey($_) }).Count -ne 0)) {
        throw 'Draft release is missing one or more required assets after upload.'
    }
}

$release = Get-RemoteReleaseWithAssets $release.id
Assert-RemoteReleaseSet $release $false
try {
    $uploadUrl = $release.upload_url.Split('{')[0]
    $existingAssets = @($release.assets | ForEach-Object name)
    foreach ($asset in $localAssets.Values) {
        if ($existingAssets -contains $asset.Name) { continue }
        $name = [System.Uri]::EscapeDataString($asset.Name)
        Invoke-WebRequest -Uri "${uploadUrl}?name=$name" -Method Post -Headers $headers -ContentType 'application/octet-stream' -InFile $asset.Path -UseBasicParsing | Out-Null
    }
    $release = Get-RemoteReleaseWithAssets $release.id
    Assert-RemoteReleaseSet $release $true
    $published = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/releases/$($release.id)" -Method Patch -Headers $headers -ContentType 'application/json; charset=utf-8' -Body '{"draft":false}'
    Write-Output "Published $($published.html_url) with $($localAssets.Count) verified assets."
} catch {
    throw "Release $Tag remains a draft because not every asset was uploaded: $($_.Exception.Message)"
}
