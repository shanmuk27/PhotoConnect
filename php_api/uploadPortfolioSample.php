<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$takerId = (int)($_POST['taker_id'] ?? 0);
if ($takerId <= 0) {
    respond(false, 'Missing taker_id', [], 422);
}

$caption = trim((string)($_POST['caption'] ?? ''));
if (mb_strlen($caption) > 255) {
    respond(false, 'Caption is too long', [], 422);
}

if (empty($_FILES['image']) || $_FILES['image']['error'] !== UPLOAD_ERR_OK) {
    respond(false, 'No image file uploaded', [], 422);
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
authorizeTaker($db, $auth, $takerId);
rateLimit('portfolio_upload_user:' . (int)$auth['user_id'], 'portfolio-upload', 30, 3600);
$check = $db->prepare('SELECT id FROM takers WHERE id=? AND is_active=1 LIMIT 1');
$check->execute([$takerId]);
if (!$check->fetch()) {
    respond(false, 'Taker not found', [], 404);
}

$src = match ($mimeType) {
    'image/jpeg' => imagecreatefromjpeg($file['tmp_name']),
    'image/png'  => imagecreatefrompng($file['tmp_name']),
    'image/webp' => imagecreatefromwebp($file['tmp_name']),
    default      => false,
};
if (!$src) {
    respond(false, 'Could not read image data', [], 422);
}

$srcW = imagesx($src);
$srcH = imagesy($src);
$ratio = min(1600 / max(1, $srcW), 1600 / max(1, $srcH), 1.0);
$dstW = max(1, (int)round($srcW * $ratio));
$dstH = max(1, (int)round($srcH * $ratio));
$out = imagecreatetruecolor($dstW, $dstH);
imagecopyresampled($out, $src, 0, 0, 0, 0, $dstW, $dstH, $srcW, $srcH);
imagedestroy($src);

$hash = bin2hex(random_bytes(8));
$baseDir = getProjectRootPath();
$uploadDir = $baseDir . '/PhotoConnectImages/photos/original/portfolio_samples/' . $takerId;
if (!is_dir($uploadDir) && !mkdir($uploadDir, 0755, true)) {
    imagedestroy($out);
    respond(false, 'Cannot create storage directory', [], 500);
}

$filePath = $uploadDir . '/sample_' . $hash . '.jpg';
if (!imagejpeg($out, $filePath, 86)) {
    imagedestroy($out);
    respond(false, 'Could not save image', [], 500);
}
imagedestroy($out);

$relativeImagePath = 'PhotoConnectImages/photos/original/portfolio_samples/' . $takerId . '/sample_' . $hash . '.jpg';
$imageUrl = buildServedImageUrl($relativeImagePath);

try {
    $stmt = $db->prepare(
        'INSERT INTO portfolio_samples (taker_id, image_url, caption)
         VALUES (?, ?, ?)'
    );
    $stmt->execute([$takerId, $relativeImagePath, $caption !== '' ? $caption : null]);
    $sampleId = (int)$db->lastInsertId();
} catch (PDOException $e) {
    pc_delete_image_and_cache($filePath);
    respond(false, $e->getMessage(), [], 500);
}

respond(true, 'Portfolio post uploaded', [
    'id' => $sampleId,
    'taker_id' => $takerId,
    'image_url' => $imageUrl,
    'caption' => $caption !== '' ? $caption : null,
]);
