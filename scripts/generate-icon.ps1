[CmdletBinding()]
param(
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\assets\AppFleet.ico'),
    [string]$PreviewPath = (Join-Path $PSScriptRoot '..\build\AppFleet-icon-preview.png')
)

Add-Type -AssemblyName System.Drawing

function New-RoundedPath {
    param([float]$X, [float]$Y, [float]$Width, [float]$Height, [float]$Radius)

    $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $diameter = $Radius * 2
    $path.AddArc($X, $Y, $diameter, $diameter, 180, 90)
    $path.AddArc($X + $Width - $diameter, $Y, $diameter, $diameter, 270, 90)
    $path.AddArc($X + $Width - $diameter, $Y + $Height - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($X, $Y + $Height - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
}

function New-IconPng {
    param([int]$Size)

    $scale = $Size / 256.0
    $bitmap = [System.Drawing.Bitmap]::new($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.Clear([System.Drawing.Color]::Transparent)

    $bounds = [System.Drawing.RectangleF]::new(20 * $scale, 20 * $scale, 216 * $scale, 216 * $scale)
    $background = New-RoundedPath -X $bounds.X -Y $bounds.Y -Width $bounds.Width -Height $bounds.Height -Radius (52 * $scale)
    $gradient = [System.Drawing.Drawing2D.LinearGradientBrush]::new($bounds, [System.Drawing.ColorTranslator]::FromHtml('#3B82F6'), [System.Drawing.ColorTranslator]::FromHtml('#1D4ED8'), 45.0)
    $graphics.FillPath($gradient, $background)

    $white = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
    $tileOpacities = @(255, 230, 204)
    $tileYs = @(65, 113, 161)
    for ($index = 0; $index -lt $tileYs.Count; $index++) {
        $tile = New-RoundedPath -X (52 * $scale) -Y ($tileYs[$index] * $scale) -Width (42 * $scale) -Height (42 * $scale) -Radius (11 * $scale)
        $brush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb($tileOpacities[$index], 255, 255, 255))
        $graphics.FillPath($brush, $tile)
        $brush.Dispose()
        $tile.Dispose()
    }

    $arrow = [System.Drawing.Pen]::new([System.Drawing.Color]::White, 19 * $scale)
    $arrow.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $arrow.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $graphics.DrawLine($arrow, 111 * $scale, 169 * $scale, 181 * $scale, 99 * $scale)
    $graphics.DrawLines($arrow, [System.Drawing.PointF[]]@(
            [System.Drawing.PointF]::new(148 * $scale, 98 * $scale),
            [System.Drawing.PointF]::new(185 * $scale, 98 * $scale),
            [System.Drawing.PointF]::new(185 * $scale, 135 * $scale)))

    $stream = [System.IO.MemoryStream]::new()
    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
    $result = $stream.ToArray()
    $stream.Dispose(); $arrow.Dispose(); $white.Dispose(); $gradient.Dispose(); $background.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
    Write-Output -NoEnumerate $result
}

$sizes = @(16, 24, 32, 48, 64, 128, 256)
$images = @()
foreach ($size in $sizes) { $images += ,(New-IconPng -Size $size) }
$outputFile = [System.IO.FileInfo]$OutputPath
$outputFile.Directory.Create()
$stream = [System.IO.File]::Create($outputFile.FullName)
$writer = [System.IO.BinaryWriter]::new($stream)
$writer.Write([uint16]0)
$writer.Write([uint16]1)
$writer.Write([uint16]$images.Count)
$offset = 6 + (16 * $images.Count)
for ($index = 0; $index -lt $images.Count; $index++) {
    $dimension = $sizes[$index]
    $directoryDimension = if ($dimension -eq 256) { 0 } else { $dimension }
    $writer.Write([byte]$directoryDimension)
    $writer.Write([byte]$directoryDimension)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]32)
    $writer.Write([uint32]$images[$index].Length)
    $writer.Write([uint32]$offset)
    $offset += $images[$index].Length
}
foreach ($image in $images) { $writer.Write($image) }
$writer.Dispose(); $stream.Dispose()

$previewFile = [System.IO.FileInfo]$PreviewPath
$previewFile.Directory.Create()
[System.IO.File]::WriteAllBytes($previewFile.FullName, $images[-1])
Write-Output "Generated $($outputFile.FullName) with $($images.Count) icon resolutions."
