<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$postId = (int)($body['post_id'] ?? 0);
$actorRole = trim((string)($body['actor_role'] ?? ''));
$actorId = (int)($body['actor_id'] ?? 0);
$like = filter_var($body['like'] ?? true, FILTER_VALIDATE_BOOLEAN);

if ($postId <= 0 || $actorId <= 0 || !in_array($actorRole, ['client', 'taker'], true)) {
    respond(false, 'Missing or invalid like payload', [], 422);
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    requireActor($db, $auth, $actorRole, $actorId);
    $db->beginTransaction();

    $postStmt = $db->prepare(
        'SELECT p.id, p.taker_id, p.like_count
         FROM taker_posts p
         INNER JOIN takers t ON t.id = p.taker_id
         WHERE p.id = ? AND t.is_active = 1
         LIMIT 1
         FOR UPDATE'
    );
    $postStmt->execute([$postId]);
    $post = $postStmt->fetch();
    if (!$post) {
        $db->rollBack();
        respond(false, 'Post not found', [], 404);
    }
    if ($actorRole === 'taker' && (int)$post['taker_id'] === $actorId) {
        $db->rollBack();
        respond(false, 'You cannot like your own post', [], 422);
    }

    if ($like) {
        $insert = $db->prepare(
            'INSERT IGNORE INTO taker_post_likes (post_id, actor_role, actor_id)
             VALUES (?, ?, ?)'
        );
        $insert->execute([$postId, $actorRole, $actorId]);
        if ($insert->rowCount() > 0) {
            $db->prepare('UPDATE taker_posts SET like_count = like_count + 1 WHERE id = ?')
                ->execute([$postId]);
        }
    } else {
        $delete = $db->prepare(
            'DELETE FROM taker_post_likes
             WHERE post_id = ? AND actor_role = ? AND actor_id = ?'
        );
        $delete->execute([$postId, $actorRole, $actorId]);
        if ($delete->rowCount() > 0) {
            $db->prepare('UPDATE taker_posts SET like_count = GREATEST(like_count - 1, 0) WHERE id = ?')
                ->execute([$postId]);
        }
    }

    $countStmt = $db->prepare('SELECT like_count FROM taker_posts WHERE id = ? LIMIT 1');
    $countStmt->execute([$postId]);
    $likeCount = (int)($countStmt->fetchColumn() ?: 0);

    $db->commit();
    respond(true, 'Like updated', [
        'post_id' => $postId,
        'like_count' => $likeCount,
        'viewer_has_liked' => $like,
    ]);
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    respond(false, $e->getMessage(), [], 500);
}
