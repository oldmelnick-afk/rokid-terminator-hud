Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$photoPath = Join-Path $PSScriptRoot 'moscow-background-generated.png'
$hudPath = Join-Path $root 'verification\v0.7-no-face.png'
$outputPath = Join-Path $PSScriptRoot 'terminator-hud-moscow-concept.png'
$previewPath = Join-Path $root 'screenshot-glasses-green-preview.png'
$hudR = 96
$hudG = 255
$hudB = 145

$photo = [System.Drawing.Bitmap]::FromFile($photoPath)
$device = [System.Drawing.Bitmap]::FromFile($hudPath)
$output = [System.Drawing.Bitmap]::new(480, 668)
$overlay = [System.Drawing.Bitmap]::new(480, 640)
$greenPreview = [System.Drawing.Bitmap]::new(480, 640)

try {
    for ($y = 0; $y -lt 640; $y++) {
        for ($x = 0; $x -lt 480; $x++) {
            $pixel = $device.GetPixel($x, $y)
            $value = [Math]::Max($pixel.R, [Math]::Max($pixel.G, $pixel.B))
            $greenPreview.SetPixel($x, $y,
                [System.Drawing.Color]::FromArgb(
                    [int]($hudR * $value / 255),
                    [int]($hudG * $value / 255),
                    [int]($hudB * $value / 255)))
            $excluded = (($y -ge 43 -and $y -le 68 -and $x -le 174) -or
                ($y -ge 69 -and $y -le 102 -and $x -ge 70 -and $x -le 410) -or
                ($y -ge 392 -and $y -le 607))
            if ($excluded) { continue }
            if ($value -gt 9) {
                $opacity = [Math]::Min(255, [int]($value * 0.88))
                $overlay.SetPixel($x, $y,
                    [System.Drawing.Color]::FromArgb($opacity, $hudR, $hudG, $hudB))
            }
        }
    }

    $g = [System.Drawing.Graphics]::FromImage($output)
    try {
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
        $g.DrawImage($photo, 0, 0, 480, 640)
        $veil = [System.Drawing.SolidBrush]::new(
            [System.Drawing.Color]::FromArgb(98, 0, 0, 0))
        $g.FillRectangle($veil, 0, 0, 480, 640)
        $veil.Dispose()
        $g.DrawImage($overlay, 0, 0)

        function Draw-HudText([string]$text, [float]$x, [float]$baseline,
            [float]$size, [bool]$bold = $false, [int]$alpha = 230,
            [bool]$center = $false) {
            $style = if ($bold) { [System.Drawing.FontStyle]::Bold } else {
                [System.Drawing.FontStyle]::Regular
            }
            $font = [System.Drawing.Font]::new('Consolas', $size, $style,
                [System.Drawing.GraphicsUnit]::Pixel)
            $brush = [System.Drawing.SolidBrush]::new(
                [System.Drawing.Color]::FromArgb($alpha, $hudR, $hudG, $hudB))
            try {
                $measure = $g.MeasureString($text, $font)
                if ($center) { $x -= $measure.Width / 2 }
                $g.DrawString($text, $font, $brush, $x, $baseline - $size * 0.91)
            } finally {
                $brush.Dispose()
                $font.Dispose()
            }
        }
        function Draw-HudLine([float]$x1, [float]$y1,
            [float]$x2, [float]$y2, [int]$alpha, [float]$width) {
            $pen = [System.Drawing.Pen]::new(
                [System.Drawing.Color]::FromArgb($alpha, $hudR, $hudG, $hudB), $width)
            try { $g.DrawLine($pen, $x1, $y1, $x2, $y2) }
            finally { $pen.Dispose() }
        }

        Draw-HudText 'TARGET 01' 18 61 14
        Draw-HudText 'PRIMARY TARGET' 240 90 17 $true 245 $true
        Draw-HudText '> JOHN CONNOR' 205 132 19 $true 245 $true

        $x = 237.0; $y = 211.0; $r = 51.0
        $fill = [System.Drawing.SolidBrush]::new(
            [System.Drawing.Color]::FromArgb(12, $hudR, $hudG, $hudB))
        $g.FillEllipse($fill, $x - $r, $y - $r, $r * 2, $r * 2)
        $fill.Dispose()
        $ring = [System.Drawing.Pen]::new(
            [System.Drawing.Color]::FromArgb(118, $hudR, $hudG, $hudB), 2.5)
        $g.DrawEllipse($ring, $x - $r, $y - $r, $r * 2, $r * 2)
        $ring.Dispose()
        $inner = [System.Drawing.Pen]::new(
            [System.Drawing.Color]::FromArgb(78, $hudR, $hudG, $hudB), 1.8)
        $g.DrawEllipse($inner, $x - $r * 0.7, $y - $r * 0.7,
            $r * 1.4, $r * 1.4)
        $inner.Dispose()
        Draw-HudLine ($x - $r * 0.7) $y ($x + $r * 0.7) $y 93 2
        Draw-HudLine $x ($y - $r * 0.7) $x ($y + $r * 0.7) 93 2
        Draw-HudLine ($x - $r - 14) $y ($x - $r) $y 128 2
        Draw-HudLine ($x + $r) $y ($x + $r + 14) $y 128 2
        Draw-HudLine $x ($y - $r - 14) $x ($y - $r) 128 2
        Draw-HudLine $x ($y + $r) $x ($y + $r + 14) 128 2

        Draw-HudLine 18 393 462 393 145 1
        Draw-HudText 'AGGRESSION' 18 425 23 $true
        Draw-HudText '26%' 381 425 27 $true
        Draw-HudText 'DIST ~2.4M' 18 453 16
        Draw-HudText 'HEIGHT 178 CM' 259 453 15
        Draw-HudText 'WEIGHT 76 KG' 18 477 16
        Draw-HudText 'BEARING 166' 259 477 14 $false 165
        Draw-HudLine 18 493 462 493 145 1
        Draw-HudText 'SEX M' 18 520 15
        Draw-HudText 'RACE HUMAN' 259 520 15
        Draw-HudText 'SECTOR POWER' 18 545 15
        Draw-HudText 'DOB 14 SEP' 259 545 15
        Draw-HudText 'ACTIVITY ENGINEER' 18 570 15
        Draw-HudText 'FILE P-01' 259 570 15
        Draw-HudText 'BIRTH YEAR 1986' 18 595 15
        Draw-HudText 'ADDR MSK-A17' 259 595 15
        Draw-HudLine 18 607 462 607 95 1

        $caption = [System.Drawing.SolidBrush]::new(
            [System.Drawing.Color]::FromArgb(255, 15, 15, 15))
        $g.FillRectangle($caption, 0, 640, 480, 28)
        $caption.Dispose()
        Draw-HudText 'CONCEPT VISUALIZATION - GENERATED PERSON' 18 659 12 $false 180
    } finally { $g.Dispose() }

    $output.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $greenPreview.Save($previewPath, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Output $outputPath
    Write-Output $previewPath
} finally {
    $greenPreview.Dispose()
    $overlay.Dispose()
    $output.Dispose()
    $device.Dispose()
    $photo.Dispose()
}
