<?php
require_once 'config.php';
require_once 'trustHelpers.php';

@set_time_limit(120);
@ini_set('memory_limit', '512M');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$takerId = (int)($_POST['taker_id'] ?? 0);
if ($takerId <= 0) {
    respond(false, 'Missing taker_id', [], 422);
}

$caption = trim((string)($_POST['caption'] ?? ''));
if (mb_strlen($caption) > 2000) {
    respond(false, 'Caption is too long', [], 422);
}
$clientUploadId = preg_replace('/[^a-zA-Z0-9_-]/', '', (string)($_POST['client_upload_id'] ?? ''));
$clientUploadId = $clientUploadId !== '' ? substr($clientUploadId, 0, 80) : null;

if (empty($_FILES['images'])) {
    respond(false, 'No images uploaded', [], 422);
}

$names = $_FILES['images']['name'] ?? [];
$tmpNames = $_FILES['images']['tmp_name'] ?? [];
$errors = $_FILES['images']['error'] ?? [];
$sizes = $_FILES['images']['size'] ?? [];

if (!is_array($names) || count($names) === 0) {
    respond(false, 'No images uploaded', [], 422);
}
if (count($names) > 8) {
    respond(false, 'You can upload up to 8 images per post', [], 422);
}

$imageCount = count($names);

$auth = requireAuthenticatedUser();
$bearerToken = getBearerToken() ?? '';
$db = getDB();
rateLimit('post_upload_user:' . (int)$auth['user_id'], 'post-upload', 20, 3600);
ensureTrustSchema($db);
pc_ensure_taker_post_upload_id_schema($db);
authorizeTaker($db, $auth, $takerId);
$check = $db->prepare(
    'SELECT id, full_name, area, city, state, service_type, profile_image_url, profile_thumb_url
     FROM takers
     WHERE id=? AND is_active=1
     LIMIT 1'
);
$check->execute([$takerId]);
$taker = $check->fetch();
if (!$taker) {
    respond(false, 'Taker not found', [], 404);
}

if ($clientUploadId !== null) {
    $existingPost = pc_find_existing_client_upload($db, $takerId, $clientUploadId);
    if ($existingPost !== null) {
        respond(true, 'Post already uploaded', ['post' => $existingPost], 200);
    }
}

$attestationImages = pc_verify_photo_attestation_for_upload(
    $bearerToken,
    $takerId,
    $imageCount,
    $_POST['photo_attestation'] ?? null,
    $_POST['photo_attestation_sig'] ?? null
);

$filesToPersist = [];
$tmpFilesForAttestation = [];
$finfo = new finfo(FILEINFO_MIME_TYPE);
for ($i = 0; $i < count($names); $i++) {
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
    $width = (int)$sizeInfo[0];
    $height = (int)$sizeInfo[1];
    if ($width < 1 || $height < 1) {
        respond(false, 'Invalid image dimensions', [], 422);
    }
    if (($width * $height) > 50000000) {
        respond(false, 'Image is too large. Please choose a smaller image.', [], 422);
    }
    $portfolioEvidence = pc_extract_portfolio_exif($tmpFile, $mimeType);
    if (empty($portfolioEvidence['has_camera_exif'])) {
        respond(false, 'Only original camera photos can be posted. Image ' . ($i + 1) . ' failed server verification.', [], 422);
    }
    $tmpFilesForAttestation[] = $tmpFile;
    $filesToPersist[] = [
        'tmp_name' => $tmpFile,
        'mime_type' => $mimeType,
        'width' => $width,
        'height' => $height,
        'portfolio_evidence' => $portfolioEvidence,
    ];
}

pc_assert_upload_hashes_match_attestation($attestationImages, $tmpFilesForAttestation);

$savedPaths = [];
$uploadDir = null;
$postId = 0;
$imageRows = [];

try {
    $db->beginTransaction();

    $postStmt = $db->prepare(
        'INSERT INTO taker_posts (taker_id, caption' . ($clientUploadId !== null ? ', client_upload_id' : '') . ')
         VALUES (?, ?' . ($clientUploadId !== null ? ', ?' : '') . ')'
    );
    $postParams = [$takerId, $caption !== '' ? $caption : null];
    if ($clientUploadId !== null) {
        $postParams[] = $clientUploadId;
    }
    $postStmt->execute($postParams);
    $postId = (int)$db->lastInsertId();

    $baseDir = getProjectRootPath();
    $uploadDir = $baseDir . '/PhotoConnectImages/photos/original/taker_posts/' . $takerId . '/' . $postId;
    if (!is_dir($uploadDir) && !mkdir($uploadDir, 0755, true)) {
        throw new RuntimeException('Cannot create storage directory');
    }

    $imageStmt = $db->prepare(
        'INSERT INTO taker_post_images (post_id, image_url, sort_order)
         VALUES (?, ?, ?)'
    );

    $imageRows = [];
    foreach ($filesToPersist as $index => $file) {
        $hash = bin2hex(random_bytes(6));
        $filePath = $uploadDir . '/image_' . ($index + 1) . '_' . $hash . '.jpg';
        pc_write_post_image_as_jpeg(
            $file['tmp_name'],
            $file['mime_type'],
            $file['width'],
            $file['height'],
            $filePath,
            1600
        );

        $relativeImagePath = 'PhotoConnectImages/photos/original/taker_posts/' . $takerId . '/' . $postId . '/' . basename($filePath);
        $imageStmt->execute([$postId, $relativeImagePath, $index]);
        $imageId = (int)$db->lastInsertId();
        pc_record_portfolio_evidence($db, $takerId, $imageId, $file['portfolio_evidence']);

        $savedPaths[] = $filePath;
        $imageRows[] = [
            'id' => $imageId,
            'post_id' => $postId,
            'image_url' => buildServedImageUrl($relativeImagePath),
            'sort_order' => $index,
        ];
    }
    pc_refresh_taker_portfolio_verification($db, $takerId);

    $db->commit();

    try {
        if (tableExists($db, 'taker_favorites')) {
            $favoritesStmt = $db->prepare(
                'SELECT actor_role, actor_id
                 FROM taker_favorites
                 WHERE taker_id = ?'
            );
            $favoritesStmt->execute([$takerId]);
            $favoriteActors = $favoritesStmt->fetchAll();
            $locationLabel = trim(implode(', ', array_filter([
                trim((string)($taker['city'] ?? '')),
                trim((string)($taker['state'] ?? '')),
            ])));
            foreach ($favoriteActors as $actor) {
                createNotification(
                    $db,
                    (string)$actor['actor_role'],
                    (int)$actor['actor_id'],
                    'favorite_taker_post',
                    'New post from a saved creator',
                    trim((string)($taker['full_name'] ?? 'A creator') . ' posted something new.'),
                    [
                        'post_id' => $postId,
                        'taker_id' => $takerId,
                        'taker_name' => (string)($taker['full_name'] ?? ''),
                        'service_type' => (string)($taker['service_type'] ?? ''),
                        'city' => $locationLabel,
                        'profile_image_url' => normalizeDeliveredImageUrl($taker['profile_image_url'] ?? null),
                        'profile_thumb_url' => normalizeDeliveredImageUrl($taker['profile_thumb_url'] ?? null, 'thumb'),
                    ]
                );
            }
        }
    } catch (Throwable $notifyError) {
        pc_log_runtime_error('Post upload notification failed: ' . $notifyError->getMessage());
    }
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
    if ($clientUploadId !== null && stripos($e->getMessage(), 'Duplicate') !== false) {
        $existingPost = pc_find_existing_client_upload($db, $takerId, $clientUploadId);
        if ($existingPost !== null) {
            respond(true, 'Post already uploaded', ['post' => $existingPost], 200);
        }
    }
    respond(false, $e->getMessage(), [], 500);
}

respond(true, 'Post uploaded', [
    'post' => [
        'id' => $postId,
        'taker_id' => $takerId,
        'caption' => $caption !== '' ? $caption : null,
        'like_count' => 0,
        'view_count' => 0,
        'viewer_has_liked' => false,
        'images' => $imageRows,
    ],
]);

function pc_ensure_taker_post_upload_id_schema(PDO $db): void
{
    if (!tableHasColumn($db, 'taker_posts', 'client_upload_id')) {
        $db->exec("ALTER TABLE taker_posts ADD COLUMN client_upload_id VARCHAR(80) DEFAULT NULL AFTER caption");
    }
    try {
        $stmt = $db->prepare(
            'SELECT COUNT(*)
             FROM information_schema.statistics
             WHERE table_schema=? AND table_name=? AND index_name=?'
        );
        $stmt->execute([DB_NAME, 'taker_posts', 'uniq_taker_posts_client_upload']);
        if ((int)$stmt->fetchColumn() === 0) {
            $db->exec('CREATE UNIQUE INDEX uniq_taker_posts_client_upload ON taker_posts(taker_id, client_upload_id)');
        }
    } catch (Throwable $e) {
        pc_log_runtime_error('Could not ensure taker post upload id index: ' . $e->getMessage());
    }
}

function pc_find_existing_client_upload(PDO $db, int $takerId, string $clientUploadId): ?array
{
    $stmt = $db->prepare(
        'SELECT id, taker_id, caption
         FROM taker_posts
         WHERE taker_id=? AND client_upload_id=?
         LIMIT 1'
    );
    $stmt->execute([$takerId, $clientUploadId]);
    $post = $stmt->fetch();
    if (!$post) {
        return null;
    }

    $imageStmt = $db->prepare(
        'SELECT id, post_id, image_url, sort_order
         FROM taker_post_images
         WHERE post_id=?
         ORDER BY sort_order ASC, id ASC'
    );
    $imageStmt->execute([(int)$post['id']]);
    $images = array_map(static function ($row) {
        return [
            'id' => (int)$row['id'],
            'post_id' => (int)$row['post_id'],
            'image_url' => buildServedImageUrl($row['image_url']),
            'sort_order' => (int)$row['sort_order'],
        ];
    }, $imageStmt->fetchAll());

    return [
        'id' => (int)$post['id'],
        'taker_id' => (int)$post['taker_id'],
        'caption' => $post['caption'] ?? null,
        'like_count' => 0,
        'view_count' => 0,
        'viewer_has_liked' => false,
        'images' => $images,
    ];
}

function pc_write_post_image_as_jpeg(
    string $tmpFile,
    string $mimeType,
    int $srcW,
    int $srcH,
    string $filePath,
    int $maxEdge = 1600
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

    $estimatedBytes = (int)($srcW * $srcH * 5 + $dstW * $dstH * 5 + 32 * 1024 * 1024);
    pc_try_raise_memory_limit($estimatedBytes);

    $src = match ($mimeType) {
        'image/jpeg' => @imagecreatefromjpeg($tmpFile),
        'image/png'  => @imagecreatefrompng($tmpFile),
        'image/webp' => @imagecreatefromwebp($tmpFile),
        default      => false,
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

function pc_try_raise_memory_limit(int $requiredBytes): void
{
    $current = pc_memory_limit_to_bytes((string)ini_get('memory_limit'));
    if ($current > 0 && $requiredBytes <= $current) {
        return;
    }
    $target = max($requiredBytes, 512 * 1024 * 1024);
    @ini_set('memory_limit', pc_bytes_to_shorthand($target));
}

function pc_memory_limit_to_bytes(string $value): int
{
    $value = trim($value);
    if ($value === '' || $value === '-1') {
        return -1;
    }
    $unit = strtolower(substr($value, -1));
    $num = (float)$value;
    return match ($unit) {
        'g' => (int)round($num * 1024 * 1024 * 1024),
        'm' => (int)round($num * 1024 * 1024),
        'k' => (int)round($num * 1024),
        default => (int)round($num),
    };
}

function pc_bytes_to_shorthand(int $bytes): string
{
    if ($bytes >= 1024 * 1024 * 1024) {
        return (string)ceil($bytes / (1024 * 1024 * 1024)) . 'G';
    }
    return (string)ceil($bytes / (1024 * 1024)) . 'M';
}
