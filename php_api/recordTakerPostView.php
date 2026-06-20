<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$postId = (int)($body['post_id'] ?? 0);
$viewerRole = trim((string)($body['viewer_role'] ?? ''));
$viewerId = (int)($body['viewer_id'] ?? 0);

if ($postId <= 0 || $viewerId <= 0 || !in_array($viewerRole, ['client', 'taker'], true)) {
    respond(false, 'Missing or invalid view payload', [], 422);
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    requireActor($db, $auth, $viewerRole, $viewerId);
    $db->beginTransaction();

    $postStmt = $db->prepare(
        'SELECT p.id, p.taker_id, p.view_count
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
    if ($viewerRole === 'taker' && (int)$post['taker_id'] === $viewerId) {
        $db->rollBack();
        respond(false, 'Owner views are not counted', [], 422);
    }

    $insert = $db->prepare(
        'INSERT IGNORE INTO taker_post_views (post_id, viewer_role, viewer_id)
         VALUES (?, ?, ?)'
    );
    $insert->execute([$postId, $viewerRole, $viewerId]);
    if ($insert->rowCount() > 0) {
        $db->prepare('UPDATE taker_posts SET view_count = view_count + 1 WHERE id = ?')
            ->execute([$postId]);
    }

    $countStmt = $db->prepare('SELECT view_count FROM taker_posts WHERE id = ? LIMIT 1');
    $countStmt->execute([$postId]);
    $viewCount = (int)($countStmt->fetchColumn() ?: 0);

    $db->commit();
    respond(true, 'View recorded', [
        'post_id' => $postId,
        'view_count' => $viewCount,
    ]);
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    respond(false, $e->getMessage(), [], 500);
}
