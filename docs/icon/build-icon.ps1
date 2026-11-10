param([string]$Source, [string]$Res, [string]$Web, [string]$Docs, [string]$Top = "#FBDCC6", [string]$Bottom = "#F5A877")
# Construit l'icône adaptative Android (fond, personnage, monochrome) et les icônes web depuis un PNG transparent 1024.
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @"
using System; using System.Drawing; using System.Drawing.Drawing2D; using System.Drawing.Imaging;
public static class Icon {
  static Bitmap Canvas(int px) { var b = new Bitmap(px, px, PixelFormat.Format32bppArgb); return b; }
  static Graphics G(Bitmap b) { var g = Graphics.FromImage(b); g.InterpolationMode = InterpolationMode.HighQualityBicubic;
    g.SmoothingMode = SmoothingMode.HighQuality; g.PixelOffsetMode = PixelOffsetMode.HighQuality; g.CompositingQuality = CompositingQuality.HighQuality; return g; }
  public static void Background(string path, int px, string topHex, string bottomHex) {
    var top = ColorTranslator.FromHtml(topHex); var bottom = ColorTranslator.FromHtml(bottomHex);
    using (var b = Canvas(px)) using (var g = G(b)) using (var br = new LinearGradientBrush(new Rectangle(0, 0, px, px + 1), top, bottom, 90f)) {
      g.FillRectangle(br, 0, 0, px, px); b.Save(path, ImageFormat.Png); }
  }
  // Calque de 108 dp : la tuile visible (72 dp) en occupe le centre ; tile = côté de la tuile en pixels.
  public static void Foreground(Bitmap src, string path, int px, bool layer) {
    using (var b = Canvas(px)) using (var g = G(b)) {
      float tile = layer ? px * 72f / 108f : px, off = (px - tile) / 2f;
      g.DrawImage(src, off, off, tile, tile); b.Save(path, ImageFormat.Png); }
  }
  // Tuile complète (web) : fond + personnage.
  public static void Full(Bitmap src, string path, int px, string topHex, string bottomHex) {
    var top = ColorTranslator.FromHtml(topHex); var bottom = ColorTranslator.FromHtml(bottomHex);
    using (var b = Canvas(px)) using (var g = G(b)) using (var br = new LinearGradientBrush(new Rectangle(0, 0, px, px + 1), top, bottom, 90f)) {
      g.FillRectangle(br, 0, 0, px, px); g.DrawImage(src, 0, 0, px, px); b.Save(path, ImageFormat.Png); }
  }
  // Monochrome : silhouette blanche ; ombre au sol et yeux (pixels sombres) retirés. Seul l'alpha compte pour Android.
  public static Bitmap Silhouette(Bitmap src) {
    var m = new Bitmap(src.Width, src.Height, PixelFormat.Format32bppArgb);
    for (int y = 0; y < src.Height; y++) for (int x = 0; x < src.Width; x++) {
      var c = src.GetPixel(x, y); int lum = (c.R * 299 + c.G * 587 + c.B * 114) / 1000;
      int a = c.A < 200 ? 0 : (lum < 70 ? 0 : 255);
      m.SetPixel(x, y, Color.FromArgb(a, 255, 255, 255));
    }
    return m;
  }
}
"@
$src = [System.Drawing.Bitmap]::FromFile($Source)
$top = $Top; $bottom = $Bottom
$mono = [Icon]::Silhouette($src)
$densities = @{ mdpi = 108; hdpi = 162; xhdpi = 216; xxhdpi = 324; xxxhdpi = 432 }
foreach ($d in $densities.Keys) {
  $px = $densities[$d]; $dir = Join-Path $Res "mipmap-$d"; New-Item -ItemType Directory -Force $dir | Out-Null
  [Icon]::Background((Join-Path $dir "ic_launcher_bg.png"), $px, $top, $bottom)
  [Icon]::Foreground($src, (Join-Path $dir "ic_launcher_fg.png"), $px, $true)
  [Icon]::Foreground($mono, (Join-Path $dir "ic_launcher_mono.png"), $px, $true)
}
New-Item -ItemType Directory -Force $Web | Out-Null
[Icon]::Full($src, (Join-Path $Web "favicon-32.png"), 32, $top, $bottom)
[Icon]::Full($src, (Join-Path $Web "apple-touch-icon.png"), 180, $top, $bottom)
[Icon]::Full($src, (Join-Path $Web "icon-192.png"), 192, $top, $bottom)
[Icon]::Full($src, (Join-Path $Web "icon-512.png"), 512, $top, $bottom)
New-Item -ItemType Directory -Force $Docs | Out-Null
[Icon]::Full($src, (Join-Path $Docs "claydot-icon-1024.png"), 1024, $top, $bottom)
$mono.Save((Join-Path $Docs "claydot-icon-monochrome.png"), [System.Drawing.Imaging.ImageFormat]::Png)
$src.Dispose(); $mono.Dispose()
"done"
