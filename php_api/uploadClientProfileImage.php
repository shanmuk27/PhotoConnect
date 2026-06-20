<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

function ensureClientProfileImageSchema(PDO $db): void
{
    if (!tableHasColumn($db, 'clients', 'profile_image_url')) {
        $db->exec('ALTER TABLE clients ADD COLUMN profile_image_url VARCHAR(500) DEFAULT NULL AFTER linked_taker_id');
    }
    if (!tableHasColumn($db, 'clients', 'profile_thumb_url')) {
        $db->exec('ALTER TABLE clients ADD COLUMN profile_thumb_url VARCHAR(512) DEFAULT NULL AFTER profile_image_url');
    }
}

function resizeClientProfileImage(GdImage $src, int $srcW, int $srcH, int $maxW, int $maxH, bool $crop = false): GdImage
{
    if ($crop) {
        $ratio = max($maxW / $srcW, $maxH / $srcH);
        $newW = (int)round($srcW * $ratio);
        $newH = (int)round($srcH * $ratio);
        $tmp = imagecreatetruecolor($newW, $newH);
        imagecopyresampled($tmp, $src, 0, 0, 0, 0, $newW, $newH, $srcW, $srcH);

        $out = imagecreatetruecolor($maxW, $maxH);
        imagecopy($out, $tmp, 0, 0, (int)(($newW - $maxW) / 2), (int)(($newH - $maxH) / 2), $maxW, $maxH);
        imagedestroy($tmp);
        return $out;
    }

    $ratio = min($maxW / $srcW, $maxH / $srcH, 1.0);
    $dstW = max(1, (int)round($srcW * $ratio));
    $dstH = max(1, (int)round($srcH * $ratio));
    $out = imagecreatetruecolor($dstW, $dstH);
    imagecopyresampled($out, $src, 0, 0, 0, 0, $dstW, $dstH, $srcW, $srcH);
    return $out;
}

$clientId = (int)($_POST['client_id'] ?? 0);
if ($clientId <= 0) {
    respond(false, 'Missing client_id', [], 422);
}

if (empty($_FILES['image']) || $_FILES['image']['error'] !== UPLOAD_ERR_OK) {
    $code = $_FILES['image']['error'] ?? UPLOAD_ERR_NO_FILE;
    $messages = [
        UPLOAD_ERR_INI_SIZE => 'File exceeds server limit',
        UPLOAD_ERR_FORM_SIZE => 'File exceeds form limit',
        UPLOAD_ERR_NO_FILE => 'No image file uploaded',
    ];
    respond(false, $messages[$code] ?? 'Upload error', [], 422);
}

$file = $_FILES['image'];
if ($file['size'] > 8 * 1024 * 1024) {
    respond(false, 'Image must be under 8 MB', [], 422);
}

$finfo = new finfo(FILEINFO_MIME_TYPE);
$mimeType = $finfo->file($file['tmp_name']);
if (!in_array($mimeType, ['image/jpeg', 'image/png', 'image/webp'], true)) {
    respond(false, 'Only JPEG, PNG or WebP images are allowed', [], 422);
}
$sizeInfo = @getimagesize($file['tmp_name']);
if (!is_array($sizeInfo) || empty($sizeInfo[0]) || empty($sizeInfo[1])) {
    respond(false, 'Could not read image dimensions', [], 422);
}
if (((int)$sizeInfo[0] * (int)$sizeInfo[1]) > 50000000) {
    respond(false, 'Image is too large. Please choose a smaller image.', [], 422);
}

$auth = requireAuthenticatedUser();
$db = getDB();
ensureClientProfileImageSchema($db);
authorizeClientProfile($db, $auth, $clientId);
rateLimit('client_profile_image_user:' . (int)$auth['user_id'], 'client-profile-image-upload', 30, 3600);

$stmt = $db->prepare('SELECT id FROM clients WHERE id=? AND is_active=1 LIMIT 1');
$stmt->execute([$clientId]);
if (!$stmt->fetch()) {
    respond(false, 'Client not found', [], 404);
}

$src = match ($mimeType) {
    'image/jpeg' => imagecreatefromjpeg($file['tmp_name']),
    'image/png' => imagecreatefrompng($file['tmp_name']),
    'image/webp' => imagecreatefromwebp($file['tmp_name']),
    default => false,
};
if (!$src) {
    respond(false, 'Could not read image data', [], 422);
}

$srcW = imagesx($src);
$srcH = imagesy($src);
$hash = bin2hex(random_bytes(8));
$baseDir = getProjectRootPath();
$uploadDir = $baseDir . '/PhotoConnectImages/photos/original/profile_images/clients/' . $clientId;
$cacheDir = $baseDir . '/PhotoConnectImages/photos/cache/profile_images/clients/' . $clientId;
foreach ([$uploadDir, $cacheDir] as $dir) {
    if (!is_dir($dir) && !mkdir($dir, 0755, true)) {
        respond(false, 'Cannot create storage directory', [], 500);
    }
}

$origFile = $uploadDir . '/original_' . $hash . '.jpg';
$thumbFile = $cacheDir . '/thumb_' . $hash . '.jpg';
$mediumFile = $cacheDir . '/medium_' . $hash . '.jpg';

$orig = resizeClientProfileImage($src, $srcW, $srcH, 1600, 1600);
imagejpeg($orig, $origFile, 88);
imagedestroy($orig);

$medium = resizeClientProfileImage($src, $srcW, $srcH, 480, 480);
imagejpeg($medium, $mediumFile, 75);
imagedestroy($medium);

$thumb = resizeClientProfileImage($src, $srcW, $srcH, 120, 120, true);
imagejpeg($thumb, $thumbFile, 80);
imagedestroy($thumb);
imagedestroy($src);

$origPath = 'PhotoConnectImages/photos/original/profile_images/clients/' . $clientId . '/original_' . $hash . '.jpg';
$thumbPath = 'PhotoConnectImages/photos/cache/profile_images/clients/' . $clientId . '/thumb_' . $hash . '.jpg';
$mediumPath = 'PhotoConnectImages/photos/cache/profile_images/clients/' . $clientId . '/medium_' . $hash . '.jpg';

try {
    $db->beginTransaction();
    $old = $db->prepare('SELECT profile_image_url, profile_thumb_url FROM clients WHERE id=? LIMIT 1 FOR UPDATE');
    $old->execute([$clientId]);
    $oldUrls = $old->fetch() ?: [];

    $upd = $db->prepare('UPDATE clients SET profile_image_url=?, profile_thumb_url=? WHERE id=?');
    $upd->execute([$origPath, $thumbPath, $clientId]);
    $db->commit();
} catch (PDOException $e) {
    if ($db->inTransaction()) {
        $db->rollBack();
    }
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
    if ($stalePath && !in_array($stalePath, [$origFile, $thumbFile, $mediumFile], true)) {
        pc_delete_image_and_cache($stalePath);
    }
}

respond(true, 'Profile image uploaded', [
    'url' => buildServedImageUrl($origPath),
    'thumb_url' => buildServedImageUrl($thumbPath, 'thumb'),
    'med_url' => buildServedImageUrl($mediumPath, 'medium'),
    'scope' => 'public',
]);
