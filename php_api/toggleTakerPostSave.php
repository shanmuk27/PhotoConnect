<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$postId = (int)($body['post_id'] ?? 0);
$actorRole = trim((string)($body['actor_role'] ?? ''));
$actorId = (int)($body['actor_id'] ?? 0);
$save = filter_var($body['save'] ?? true, FILTER_VALIDATE_BOOLEAN);

if ($postId <= 0 || $actorId <= 0 || !in_array($actorRole, ['client', 'taker'], true)) {
    respond(false, 'Missing or invalid save payload', [], 422);
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    requireActor($db, $auth, $actorRole, $actorId);

    $postStmt = $db->prepare(
        'SELECT p.id, p.taker_id
         FROM taker_posts p
         INNER JOIN takers t ON t.id = p.taker_id
         WHERE p.id = ? AND t.is_active = 1
         LIMIT 1'
    );
    $postStmt->execute([$postId]);
    $post = $postStmt->fetch();
    if (!$post) {
        respond(false, 'Post not found', [], 404);
    }
    if ($actorRole === 'taker' && (int)$post['taker_id'] === $actorId) {
        respond(false, 'You cannot save your own post', [], 422);
    }

    if ($save) {
        $stmt = $db->prepare(
            'INSERT IGNORE INTO taker_post_saves (post_id, actor_role, actor_id)
             VALUES (?, ?, ?)'
        );
        $stmt->execute([$postId, $actorRole, $actorId]);
    } else {
        $stmt = $db->prepare(
            'DELETE FROM taker_post_saves
             WHERE post_id = ? AND actor_role = ? AND actor_id = ?'
        );
        $stmt->execute([$postId, $actorRole, $actorId]);
    }

    $countStmt = $db->prepare('SELECT COUNT(*) FROM taker_post_saves WHERE post_id = ?');
    $countStmt->execute([$postId]);
    $saveCount = (int)($countStmt->fetchColumn() ?: 0);

    respond(true, 'Save updated', [
        'post_id' => $postId,
        'save_count' => $saveCount,
        'viewer_has_saved' => $save,
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
