<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$imageId = (int)($body['image_id'] ?? 0);
$actorRole = trim((string)($body['actor_role'] ?? ''));
$actorId = (int)($body['actor_id'] ?? 0);
$like = filter_var($body['like'] ?? true, FILTER_VALIDATE_BOOLEAN);

if ($imageId <= 0 || $actorId <= 0 || !in_array($actorRole, ['client', 'taker'], true)) {
    respond(false, 'Missing or invalid image like payload', [], 422);
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    requireActor($db, $auth, $actorRole, $actorId);
    $db->beginTransaction();

    $imageStmt = $db->prepare(
        'SELECT i.id, i.post_id, i.like_count, p.taker_id
         FROM taker_post_images i
         INNER JOIN taker_posts p ON p.id = i.post_id
         INNER JOIN takers t ON t.id = p.taker_id
         WHERE i.id = ? AND t.is_active = 1
         LIMIT 1
         FOR UPDATE'
    );
    $imageStmt->execute([$imageId]);
    $image = $imageStmt->fetch();
    if (!$image) {
        $db->rollBack();
        respond(false, 'Image not found', [], 404);
    }
    if ($actorRole === 'taker' && (int)$image['taker_id'] === $actorId) {
        $db->rollBack();
        respond(false, 'You cannot like your own image', [], 422);
    }

    if ($like) {
        $insert = $db->prepare(
            'INSERT IGNORE INTO taker_post_image_likes (image_id, actor_role, actor_id)
             VALUES (?, ?, ?)'
        );
        $insert->execute([$imageId, $actorRole, $actorId]);
        if ($insert->rowCount() > 0) {
            $db->prepare('UPDATE taker_post_images SET like_count = like_count + 1 WHERE id = ?')
                ->execute([$imageId]);
        }
    } else {
        $delete = $db->prepare(
            'DELETE FROM taker_post_image_likes
             WHERE image_id = ? AND actor_role = ? AND actor_id = ?'
        );
        $delete->execute([$imageId, $actorRole, $actorId]);
        if ($delete->rowCount() > 0) {
            $db->prepare('UPDATE taker_post_images SET like_count = GREATEST(like_count - 1, 0) WHERE id = ?')
                ->execute([$imageId]);
        }
    }

    $countStmt = $db->prepare('SELECT like_count, post_id FROM taker_post_images WHERE id = ? LIMIT 1');
    $countStmt->execute([$imageId]);
    $counts = $countStmt->fetch();
    $imageLikeCount = (int)($counts['like_count'] ?? 0);
    $postId = (int)($counts['post_id'] ?? 0);

    if ($postId > 0) {
        $db->prepare(
            'UPDATE taker_posts p
             SET p.like_count = (
                SELECT COALESCE(SUM(i.like_count), 0)
                FROM taker_post_images i
                WHERE i.post_id = p.id
             )
             WHERE p.id = ?'
        )->execute([$postId]);
    }

    $postCountStmt = $db->prepare('SELECT like_count FROM taker_posts WHERE id = ? LIMIT 1');
    $postCountStmt->execute([$postId]);
    $postLikeCount = (int)($postCountStmt->fetchColumn() ?: 0);

    $db->commit();
    respond(true, 'Image like updated', [
        'image_id' => $imageId,
        'post_id' => $postId,
        'image_like_count' => $imageLikeCount,
        'post_like_count' => $postLikeCount,
        'viewer_has_liked' => $like,
    ]);
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    respond(false, $e->getMessage(), [], 500);
}
