<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$takerId = (int)($body['taker_id'] ?? 0);
$actorRole = trim((string)($body['actor_role'] ?? ''));
$actorId = (int)($body['actor_id'] ?? 0);
$favorite = filter_var($body['favorite'] ?? true, FILTER_VALIDATE_BOOLEAN);

if ($takerId <= 0 || $actorId <= 0 || !in_array($actorRole, ['client', 'taker'], true)) {
    respond(false, 'Missing or invalid favorite payload', [], 422);
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    requireActor($db, $auth, $actorRole, $actorId);
    rateLimit('favorite_user:' . (int)$auth['user_id'], 'favorite-toggle', 120, 300);
    if (!tableExists($db, 'taker_favorites')) {
        respond(true, 'Favorites are not enabled on this server yet', [
            'taker_id' => $takerId,
            'is_favorite' => false,
            'favorite_count' => 0,
        ]);
    }
    $db->beginTransaction();

    $takerStmt = $db->prepare('SELECT id FROM takers WHERE id = ? AND is_active = 1 LIMIT 1 FOR UPDATE');
    $takerStmt->execute([$takerId]);
    if (!$takerStmt->fetch()) {
        $db->rollBack();
        respond(false, 'Taker not found', [], 404);
    }
    if ($actorRole === 'taker' && $actorId === $takerId) {
        $db->rollBack();
        respond(false, 'You cannot favorite your own profile', [], 422);
    }

    if ($favorite) {
        $stmt = $db->prepare(
            'INSERT IGNORE INTO taker_favorites (taker_id, actor_role, actor_id)
             VALUES (?, ?, ?)'
        );
        $stmt->execute([$takerId, $actorRole, $actorId]);
    } else {
        $stmt = $db->prepare(
            'DELETE FROM taker_favorites
             WHERE taker_id = ? AND actor_role = ? AND actor_id = ?'
        );
        $stmt->execute([$takerId, $actorRole, $actorId]);
    }

    $countStmt = $db->prepare('SELECT COUNT(*) FROM taker_favorites WHERE taker_id = ?');
    $countStmt->execute([$takerId]);
    $favoriteCount = (int)$countStmt->fetchColumn();

    $db->commit();
    respond(true, 'Favorite updated', [
        'taker_id' => $takerId,
        'is_favorite' => $favorite,
        'favorite_count' => $favoriteCount,
    ]);
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    respond(false, $e->getMessage(), [], 500);
}
