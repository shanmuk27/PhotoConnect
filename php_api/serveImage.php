<?php
/**
 * serveImage.php  — Smart image delivery with aggressive caching
 *
 * Query params:
 *   path    string   Relative path under /uploads/ or /cache/
 *   q       string   "thumb" | "medium" | "full"  (default: full)
 *
 * Strategy:
 *  - Serve cached variant if it exists → fast
 *  - On first request for a derived size, generate it from the original
 *  - Set long Cache-Control / ETag headers so repeat loads hit the
 *    browser cache or a CDN without touching PHP at all
 */

require_once 'config.php';

ini_set('display_errors', APP_DEBUG ? '1' : '0');
error_reporting(APP_DEBUG ? E_ALL : 0);

$rel  = ltrim($_GET['path'] ?? '', '/');
$qual = $_GET['q'] ?? 'full';

// Block path traversal
if (preg_match('/\.\./', $rel) || !isManagedImageRelativePath($rel)) {
    http_response_code(400);
    exit('Invalid path');
}

$baseDir = realpath(__DIR__ . '/..');
$absPath = $baseDir . '/' . $rel;

// ── Derive the right cached file ─────────────────────────────
if ($qual !== 'full' && preg_match('#^PhotoConnectImages/photos/original/#', $rel)) {
    $info    = pathinfo($absPath);
    $dirName = $info['dirname'];
    $base    = $info['basename'];

    // Support original_, sample_, and image_ prefixes for cache generation
    if (pc_starts_with($base, 'original_') || pc_starts_with($base, 'sample_') || pc_starts_with($base, 'image_')) {
        $prefixLength = 0;
        if (pc_starts_with($base, 'original_')) $prefixLength = strlen('original_');
        elseif (pc_starts_with($base, 'sample_')) $prefixLength = strlen('sample_');
        elseif (pc_starts_with($base, 'image_')) $prefixLength = strlen('image_');
        
        $hash    = substr($base, $prefixLength, -strlen('.jpg'));
        $variant = ($qual === 'thumb') ? 'thumb_' : 'medium_';
        $subDir  = str_replace('/original/', '/cache/', $dirName);
        $derived = $subDir . '/' . $variant . $hash . '.jpg';

        if (!is_file($derived)) {
            // Generate on-the-fly if cache file is missing
            @mkdir($subDir, 0755, true);
            if (is_file($absPath)) {
                $src  = imagecreatefromjpeg($absPath);
                if (!$src) {
                    http_response_code(422);
                    exit('Invalid image');
                }
                $srcW = imagesx($src);
                $srcH = imagesy($src);

                if ($qual === 'thumb') {
                    $ratio  = max(120 / $srcW, 120 / $srcH);
                    $newW   = (int)round($srcW * $ratio);
                    $newH   = (int)round($srcH * $ratio);
                    $tmp    = imagecreatetruecolor($newW, $newH);
                    imagecopyresampled($tmp, $src, 0, 0, 0, 0, $newW, $newH, $srcW, $srcH);
                    $out    = imagecreatetruecolor(120, 120);
                    imagecopy($out, $tmp, 0, 0, (int)(($newW - 120) / 2), (int)(($newH - 120) / 2), 120, 120);
                    imagedestroy($tmp);
                    imagejpeg($out, $derived, 80);
                    imagedestroy($out);
                } else {
                    $ratio = min(480 / $srcW, 480 / $srcH, 1.0);
                    $dstW  = max(1, (int)round($srcW * $ratio));
                    $dstH  = max(1, (int)round($srcH * $ratio));
                    $out   = imagecreatetruecolor($dstW, $dstH);
                    imagecopyresampled($out, $src, 0, 0, 0, 0, $dstW, $dstH, $srcW, $srcH);
                    imagejpeg($out, $derived, 75);
                    imagedestroy($out);
                }
                imagedestroy($src);
            }
        }
        if (is_file($derived)) {
            $absPath = $derived;
        }
    }
}

// ── Serve the file ────────────────────────────────────────────
if (!is_file($absPath)) {
    http_response_code(404);
    exit('Not found');
}

$etag    = '"' . md5_file($absPath) . '"';
$lastMod = filemtime($absPath);

// Conditional GET support
if (
    (isset($_SERVER['HTTP_IF_NONE_MATCH'])     && $_SERVER['HTTP_IF_NONE_MATCH'] === $etag) ||
    (isset($_SERVER['HTTP_IF_MODIFIED_SINCE']) && strtotime($_SERVER['HTTP_IF_MODIFIED_SINCE']) >= $lastMod)
) {
    http_response_code(304);
    exit;
}

// Long-lived cache: 30 days for thumbnails/medium, 7 days for originals
$ttl = ($qual !== 'full') ? 86400 * 30 : 86400 * 7;

header('Content-Type: image/jpeg');
header('Cache-Control: public, max-age=' . $ttl . ', stale-while-revalidate=86400');
header('ETag: ' . $etag);
header('Last-Modified: ' . gmdate('D, d M Y H:i:s', $lastMod) . ' GMT');
header('Content-Length: ' . filesize($absPath));
header('Vary: Accept-Encoding');

readfile($absPath);
