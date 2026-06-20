<?php
/**
 * uploadProfileImage.php
 *
 * Accepts: multipart/form-data
 *   taker_id   int      required
 *   scope      string   "public" | "profile-only"  (default: public)
 *   image      file     required  (jpeg/png/webp, max 8MB)
 *
 * Returns: { success, message, data: { url, thumb_url, scope } }
 *
 * Storage layout:
 *   uploads/profile_images/<taker_id>/original_<hash>.jpg   ← full-res JPEG
 *   cache/profile_images/<taker_id>/thumb_<hash>.jpg        ← 120×120 thumbnail
 *   cache/profile_images/<taker_id>/medium_<hash>.jpg       ← 480px wide preview
 */

require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

// ── Validate inputs ───────────────────────────────────────────
$takerId = (int)($_POST['taker_id'] ?? 0);
if ($takerId <= 0) respond(false, 'Missing taker_id', [], 422);

$scope = in_array($_POST['scope'] ?? '', ['public', 'profile-only'])
    ? $_POST['scope']
    : 'public';

if (empty($_FILES['image']) || $_FILES['image']['error'] !== UPLOAD_ERR_OK) {
    $errMap = [
        UPLOAD_ERR_INI_SIZE  => 'File exceeds server limit',
        UPLOAD_ERR_FORM_SIZE => 'File exceeds form limit',
        UPLOAD_ERR_NO_FILE   => 'No image file uploaded',
    ];
    $code = $_FILES['image']['error'] ?? UPLOAD_ERR_NO_FILE;
    respond(false, $errMap[$code] ?? 'Upload error', [], 422);
}

$file     = $_FILES['image'];
$maxBytes = 8 * 1024 * 1024; // 8 MB
if ($file['size'] > $maxBytes) {
    respond(false, 'Image must be under 8 MB', [], 422);
}

// Verify MIME by reading actual bytes — not trusting client header
$finfo    = new finfo(FILEINFO_MIME_TYPE);
$mimeType = $finfo->file($file['tmp_name']);
$allowed  = ['image/jpeg', 'image/png', 'image/webp'];
if (!in_array($mimeType, $allowed, true)) {
    respond(false, 'Only JPEG, PNG or WebP images are allowed', [], 422);
}
$sizeInfo = @getimagesize($file['tmp_name']);
if (!is_array($sizeInfo) || empty($sizeInfo[0]) || empty($sizeInfo[1])) {
    respond(false, 'Could not read image dimensions', [], 422);
}
if (((int)$sizeInfo[0] * (int)$sizeInfo[1]) > 50000000) {
    respond(false, 'Image is too large. Please choose a smaller image.', [], 422);
}

// ── Verify taker exists ───────────────────────────────────────
$auth = requireAuthenticatedUser();
$db = getDB();
authorizeTaker($db, $auth, $takerId);
rateLimit('profile_image_user:' . (int)$auth['user_id'], 'profile-image-upload', 30, 3600);
$stmt = $db->prepare('SELECT id FROM takers WHERE id=? AND is_active=1 LIMIT 1');
$stmt->execute([$takerId]);
if (!$stmt->fetch()) {
    respond(false, 'Taker not found', [], 404);
}

// ── Build directory paths ─────────────────────────────────────
$baseDir   = getProjectRootPath();
$uploadDir = $baseDir . '/PhotoConnectImages/photos/original/profile_images/' . $takerId;
$cacheDir  = $baseDir . '/PhotoConnectImages/photos/cache/profile_images/'   . $takerId;

foreach ([$uploadDir, $cacheDir] as $dir) {
    if (!is_dir($dir) && !mkdir($dir, 0755, true)) {
        respond(false, 'Cannot create storage directory', [], 500);
    }
}

// ── Generate unique filename ──────────────────────────────────
$hash     = bin2hex(random_bytes(8));
$origFile = $uploadDir . '/original_' . $hash . '.jpg';
$thumbFile = $cacheDir  . '/thumb_'    . $hash . '.jpg';   // 120×120
$medFile   = $cacheDir  . '/medium_'   . $hash . '.jpg';   // 480px wide

// ── Load source image using GD ────────────────────────────────
$src = match ($mimeType) {
    'image/jpeg' => imagecreatefromjpeg($file['tmp_name']),
    'image/png'  => imagecreatefrompng($file['tmp_name']),
    'image/webp' => imagecreatefromwebp($file['tmp_name']),
    default      => false,
};
if (!$src) respond(false, 'Could not read image data', [], 422);

$srcW = imagesx($src);
$srcH = imagesy($src);

// ── Helper: resize preserving aspect ─────────────────────────
function resizeImage(
    GdImage $src,
    int     $srcW,
    int     $srcH,
    int     $maxW,
    int     $maxH,
    bool    $crop = false
): GdImage {
    if ($crop) {
        // Center-crop to exact maxW × maxH
        $ratio  = max($maxW / $srcW, $maxH / $srcH);
        $newW   = (int)round($srcW * $ratio);
        $newH   = (int)round($srcH * $ratio);
        $offX   = (int)(($newW - $maxW) / 2);
        $offY   = (int)(($newH - $maxH) / 2);

        $tmp    = imagecreatetruecolor($newW, $newH);
        imagecopyresampled($tmp, $src, 0, 0, 0, 0, $newW, $newH, $srcW, $srcH);

        $out = imagecreatetruecolor($maxW, $maxH);
        imagecopy($out, $tmp, 0, 0, $offX, $offY, $maxW, $maxH);
        imagedestroy($tmp);
        return $out;
    }

    // Fit within maxW × maxH
    $ratio = min($maxW / $srcW, $maxH / $srcH, 1.0); // never upscale
    $dstW  = max(1, (int)round($srcW * $ratio));
    $dstH  = max(1, (int)round($srcH * $ratio));

    $out = imagecreatetruecolor($dstW, $dstH);
    imagecopyresampled($out, $src, 0, 0, 0, 0, $dstW, $dstH, $srcW, $srcH);
    return $out;
}

// ── Save original (resize if huge, ≤ 1600px wide) ────────────
$orig = resizeImage($src, $srcW, $srcH, 1600, 1600);
imagejpeg($orig, $origFile, 88);
imagedestroy($orig);

// ── Save medium preview (480px wide) ─────────────────────────
$med = resizeImage($src, $srcW, $srcH, 480, 480);
imagejpeg($med, $medFile, 75);
imagedestroy($med);

// ── Save thumbnail (120×120 center-cropped) ───────────────────
$thumb = resizeImage($src, $srcW, $srcH, 120, 120, crop: true);
imagejpeg($thumb, $thumbFile, 80);
imagedestroy($thumb);

imagedestroy($src);

// ── Build public URLs ─────────────────────────────────────────
$origPath  = 'PhotoConnectImages/photos/original/profile_images/' . $takerId . '/original_' . $hash . '.jpg';
$thumbPath = 'PhotoConnectImages/photos/cache/profile_images/'   . $takerId . '/thumb_'    . $hash . '.jpg';
$medPath   = 'PhotoConnectImages/photos/cache/profile_images/'   . $takerId . '/medium_'   . $hash . '.jpg';
$origUrl   = buildServedImageUrl($origPath);
$thumbUrl  = buildServedImageUrl($thumbPath, 'thumb');
$medUrl    = buildServedImageUrl($medPath, 'medium');

// ── Persist in DB ─────────────────────────────────────────────
try {
    $db->beginTransaction();

    $old = $db->prepare(
        'SELECT profile_image_url, profile_thumb_url
         FROM takers
         WHERE id=?
         LIMIT 1
         FOR UPDATE'
    );
    $old->execute([$takerId]);
    $oldUrls = $old->fetch() ?: [];

    $upd = $db->prepare(
        'UPDATE takers
         SET profile_image_url=?,
             profile_thumb_url=?,
             profile_image_scope=?
         WHERE id=?'
    );
    $upd->execute([$origPath, $thumbPath, $scope, $takerId]);

    $db->commit();
} catch (PDOException $e) {
    $db->rollBack();
    // Clean up files on DB failure
    pc_delete_image_and_cache($origFile);
    respond(false, $e->getMessage(), [], 500);
}

$oldOrigPath = projectFilePathFromUrl((string)($oldUrls['profile_image_url'] ?? ''));
$oldThumbPath = projectFilePathFromUrl((string)($oldUrls['profile_thumb_url'] ?? ''));
$oldMediumPath = null;
if ($oldThumbPath && pc_string_contains($oldThumbPath, DIRECTORY_SEPARATOR . 'thumb_')) {
    $oldMediumPath = str_replace(DIRECTORY_SEPARATOR . 'thumb_', DIRECTORY_SEPARATOR . 'medium_', $oldThumbPath);
}

foreach ([$oldOrigPath, $oldThumbPath, $oldMediumPath] as $stalePath) {
    if (!$stalePath) {
        continue;
    }
    if (in_array($stalePath, [$origFile, $thumbFile, $medFile], true)) {
        continue;
    }
    pc_delete_image_and_cache($stalePath);
}

respond(true, 'Profile image uploaded', [
    'url'       => $origUrl,
    'thumb_url' => $thumbUrl,
    'med_url'   => $medUrl,
    'scope'     => $scope,
]);
