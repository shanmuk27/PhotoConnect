<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'DELETE') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$takerId = (int)($body['taker_id'] ?? 0);
$postId = (int)($body['post_id'] ?? 0);

if ($takerId <= 0 || $postId <= 0) {
    respond(false, 'Missing taker_id or post_id', [], 422);
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    authorizeTaker($db, $auth, $takerId);
    $db->beginTransaction();

    $postStmt = $db->prepare(
        'SELECT p.id
         FROM taker_posts p
         INNER JOIN takers t ON t.id = p.taker_id
         WHERE p.id = ? AND p.taker_id = ? AND t.is_active = 1
         LIMIT 1
         FOR UPDATE'
    );
    $postStmt->execute([$postId, $takerId]);
    if (!$postStmt->fetch()) {
        $db->rollBack();
        respond(false, 'Post not found', [], 404);
    }

    $imageStmt = $db->prepare(
        'SELECT image_url
         FROM taker_post_images
         WHERE post_id = ?'
    );
    $imageStmt->execute([$postId]);
    $imageUrls = $imageStmt->fetchAll(PDO::FETCH_COLUMN);

    $deleteStmt = $db->prepare('DELETE FROM taker_posts WHERE id = ? AND taker_id = ?');
    $deleteStmt->execute([$postId, $takerId]);

    $db->commit();

    foreach ($imageUrls as $imageUrl) {
        $filePath = projectFilePathFromUrl((string)$imageUrl);
        if ($filePath) {
            pc_delete_image_and_cache($filePath);
        }
    }
    
    $baseDir = getProjectRootPath();
    $dirs = [
        $baseDir . '/PhotoConnectImages/photos/original/taker_posts/' . $takerId . '/' . $postId,
        $baseDir . '/PhotoConnectImages/photos/cache/taker_posts/' . $takerId . '/' . $postId,
    ];
    foreach ($dirs as $dir) {
        if (is_dir($dir)) {
            @rmdir($dir);
        }
    }
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    respond(false, $e->getMessage(), [], 500);
}

respond(true, 'Post deleted', ['deleted' => true]);
