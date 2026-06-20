<?php
require_once 'config.php';

@set_time_limit(120);
@ini_set('memory_limit', '512M');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$takerId = (int)($_POST['taker_id'] ?? 0);
$postId = (int)($_POST['post_id'] ?? 0);
$caption = trim((string)($_POST['caption'] ?? ''));
$keepRaw = trim((string)($_POST['keep_image_ids'] ?? ''));

if ($takerId <= 0 || $postId <= 0) {
    respond(false, 'Missing taker_id or post_id', [], 422);
}
if (mb_strlen($caption) > 2000) {
    respond(false, 'Caption too long', [], 422);
}

$keepIds = [];
if ($keepRaw !== '') {
    foreach (preg_split('/[,\s]+/', $keepRaw) ?: [] as $id) {
        $id = (int)$id;
        if ($id > 0) {
            $keepIds[$id] = $id;
        }
    }
}
$keepIds = array_values($keepIds);

$names = $_FILES['images']['name'] ?? [];
$tmpNames = $_FILES['images']['tmp_name'] ?? [];
$errors = $_FILES['images']['error'] ?? [];
$sizes = $_FILES['images']['size'] ?? [];
$newCount = is_array($names) ? count(array_filter($names, fn($name) => (string)$name !== '')) : 0;

if ($newCount > 8) {
    respond(false, 'You can upload up to 8 images per post', [], 422);
}

$auth = requireAuthenticatedUser();
$db = getDB();
authorizeTaker($db, $auth, $takerId);
rateLimit('post_update_user:' . (int)$auth['user_id'], 'post-update', 60, 3600);

$filesToPersist = [];
if ($newCount > 0) {
    if (!is_array($names) || !is_array($tmpNames) || !is_array($errors) || !is_array($sizes)) {
        respond(false, 'Invalid upload payload', [], 422);
    }
    $finfo = new finfo(FILEINFO_MIME_TYPE);
    for ($i = 0; $i < count($names); $i++) {
        if ((string)($names[$i] ?? '') === '') {
            continue;
        }
        if (($errors[$i] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) {
            respond(false, 'One of the images failed to upload', [], 422);
        }
        if (($sizes[$i] ?? 0) > 8 * 1024 * 1024) {
            respond(false, 'Each image must be under 8 MB', [], 422);
        }
        $tmpFile = $tmpNames[$i] ?? '';
        $mimeType = $finfo->file($tmpFile);
        if (!in_array($mimeType, ['image/jpeg', 'image/png', 'image/webp'], true)) {
            respond(false, 'Only JPEG, PNG or WebP images are allowed', [], 422);
        }
        $sizeInfo = @getimagesize($tmpFile);
        if (!is_array($sizeInfo) || empty($sizeInfo[0]) || empty($sizeInfo[1])) {
            respond(false, 'Could not read image dimensions', [], 422);
        }
        if (((int)$sizeInfo[0] * (int)$sizeInfo[1]) > 50000000) {
            respond(false, 'Image is too large. Please choose a smaller image.', [], 422);
        }
        $filesToPersist[] = [
            'tmp_name' => $tmpFile,
            'mime_type' => $mimeType,
            'width' => (int)$sizeInfo[0],
            'height' => (int)$sizeInfo[1],
        ];
    }
}

$uploadDir = null;

try {
    $postStmt = $db->prepare(
        'SELECT p.id
         FROM taker_posts p
         INNER JOIN takers t ON t.id = p.taker_id
         WHERE p.id = ? AND p.taker_id = ? AND t.is_active = 1
         LIMIT 1'
    );
    $postStmt->execute([$postId, $takerId]);
    if (!$postStmt->fetch()) {
        respond(false, 'Post not found', [], 404);
    }

    $imageStmt = $db->prepare(
        'SELECT id, image_url
         FROM taker_post_images
         WHERE post_id = ?
         ORDER BY sort_order ASC, id ASC'
    );
    $imageStmt->execute([$postId]);
    $existingImages = $imageStmt->fetchAll();
    $existingById = [];
    foreach ($existingImages as $row) {
        $existingById[(int)$row['id']] = $row;
    }

    $keptIds = array_values(array_filter($keepIds, fn($id) => isset($existingById[$id])));
    $finalCount = count($keptIds) + count($filesToPersist);
    if ($finalCount < 1) {
        respond(false, 'Select at least one image', [], 422);
    }
    if ($finalCount > 8) {
        respond(false, 'You can upload up to 8 images per post', [], 422);
    }

    $db->beginTransaction();

    $updateSql = tableHasColumn($db, 'taker_posts', 'updated_at')
        ? 'UPDATE taker_posts SET caption = ?, updated_at = NOW() WHERE id = ? AND taker_id = ?'
        : 'UPDATE taker_posts SET caption = ? WHERE id = ? AND taker_id = ?';
    $db->prepare($updateSql)->execute([$caption !== '' ? $caption : null, $postId, $takerId]);

    $removedRows = array_filter(
        $existingImages,
        fn($row) => !in_array((int)$row['id'], $keptIds, true)
    );
    if (!empty($removedRows)) {
        $placeholders = implode(',', array_fill(0, count($removedRows), '?'));
        $deleteParams = array_map(fn($row) => (int)$row['id'], $removedRows);
        $deleteParams[] = $postId;
        $db->prepare("DELETE FROM taker_post_images WHERE id IN ($placeholders) AND post_id = ?")->execute($deleteParams);
    }

    $sort = 0;
    $sortStmt = $db->prepare('UPDATE taker_post_images SET sort_order = ? WHERE id = ? AND post_id = ?');
    foreach ($keptIds as $id) {
        $sortStmt->execute([$sort++, $id, $postId]);
    }

    $baseDir = getProjectRootPath();
    $uploadDir = $baseDir . '/PhotoConnectImages/photos/original/taker_posts/' . $takerId . '/' . $postId;
    if (!is_dir($uploadDir) && !mkdir($uploadDir, 0755, true)) {
        throw new RuntimeException('Cannot create storage directory');
    }

    $insertImage = $db->prepare(
        'INSERT INTO taker_post_images (post_id, image_url, sort_order)
         VALUES (?, ?, ?)'
    );
    $savedPaths = [];
    foreach ($filesToPersist as $file) {
        $hash = bin2hex(random_bytes(6));
        $filePath = $uploadDir . '/image_' . ($sort + 1) . '_' . $hash . '.jpg';
        pc_update_write_post_image_as_jpeg(
            $file['tmp_name'],
            $file['mime_type'],
            $file['width'],
            $file['height'],
            $filePath,
            1600
        );
        $relativePath = 'PhotoConnectImages/photos/original/taker_posts/' . $takerId . '/' . $postId . '/' . basename($filePath);
        $insertImage->execute([$postId, $relativePath, $sort++]);
        $savedPaths[] = $filePath;
    }

    $db->commit();

    foreach ($removedRows as $row) {
        $path = pc_image_db_path_to_file((string)($row['image_url'] ?? ''));
        if ($path !== null) {
            pc_delete_image_and_cache($path);
        }
    }

    respond(true, 'Post updated', ['id' => $postId]);
} catch (Throwable $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    if (!empty($savedPaths ?? [])) {
        foreach ($savedPaths as $path) {
            pc_delete_image_and_cache($path);
        }
    }
    if ($uploadDir !== null) {
        pc_remove_empty_directories_up_to($uploadDir, getProjectRootPath() . DIRECTORY_SEPARATOR . 'PhotoConnectImages' . DIRECTORY_SEPARATOR . 'photos');
    }
    respond(false, $e->getMessage(), [], 500);
}

function pc_image_db_path_to_file(string $storedPath): ?string
{
    $path = preg_replace('#^https?://[^/]+/#', '', $storedPath);
    $path = ltrim((string)$path, '/');
    if ($path === '' || strpos($path, 'PhotoConnectImages/photos/original/taker_posts/') !== 0) {
        return null;
    }
    return getProjectRootPath() . '/' . $path;
}

function pc_update_write_post_image_as_jpeg(
    string $tmpFile,
    string $mimeType,
    int $srcW,
    int $srcH,
    string $filePath,
    int $maxEdge = 1600,
): void {
    if (class_exists('Imagick')) {
        try {
            $image = new Imagick();
            $image->readImage($tmpFile);
            $image->setImageBackgroundColor('white');
            if (method_exists($image, 'setImageAlphaChannel') && defined('Imagick::ALPHACHANNEL_REMOVE')) {
                $image->setImageAlphaChannel(Imagick::ALPHACHANNEL_REMOVE);
            }
            $image = $image->mergeImageLayers(Imagick::LAYERMETHOD_FLATTEN);
            $image->thumbnailImage($maxEdge, $maxEdge, true, true);
            $image->setImageFormat('jpeg');
            $image->setImageCompressionQuality(86);
            if (!$image->writeImage($filePath)) {
                throw new RuntimeException('Could not save image');
            }
            $image->clear();
            $image->destroy();
            return;
        } catch (Throwable $e) {
            // Fall back to GD below.
        }
    }

    $ratio = min($maxEdge / max(1, $srcW), $maxEdge / max(1, $srcH), 1.0);
    $dstW = max(1, (int)round($srcW * $ratio));
    $dstH = max(1, (int)round($srcH * $ratio));
    $src = match ($mimeType) {
        'image/jpeg' => @imagecreatefromjpeg($tmpFile),
        'image/png' => @imagecreatefrompng($tmpFile),
        'image/webp' => @imagecreatefromwebp($tmpFile),
        default => false,
    };
    if (!$src) {
        throw new RuntimeException('Could not read image data');
    }
    $out = imagecreatetruecolor($dstW, $dstH);
    if (!$out) {
        imagedestroy($src);
        throw new RuntimeException('Could not allocate image memory');
    }
    $white = imagecolorallocate($out, 255, 255, 255);
    imagefill($out, 0, 0, $white);
    imagecopyresampled($out, $src, 0, 0, 0, 0, $dstW, $dstH, $srcW, $srcH);
    imagedestroy($src);
    if (!imagejpeg($out, $filePath, 86)) {
        imagedestroy($out);
        throw new RuntimeException('Could not save image');
    }
    imagedestroy($out);
}
