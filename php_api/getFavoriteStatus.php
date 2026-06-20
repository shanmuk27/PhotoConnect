<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    respond(false, 'Method not allowed', [], 405);
}

$takerId = (int)($_GET['takerId'] ?? 0);
$actorRole = trim((string)($_GET['actorRole'] ?? ''));
$actorId = (int)($_GET['actorId'] ?? 0);

if ($takerId <= 0 || $actorId <= 0 || !in_array($actorRole, ['client', 'taker'], true)) {
    respond(false, 'Missing or invalid favorite query', [], 422);
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    requireActor($db, $auth, $actorRole, $actorId);
    if (!tableExists($db, 'taker_favorites')) {
        respond(true, 'Favorites are not enabled on this server yet', [
            'taker_id' => $takerId,
            'is_favorite' => false,
            'favorite_count' => 0,
        ]);
    }
    $stmt = $db->prepare(
        'SELECT EXISTS(
            SELECT 1 FROM taker_favorites
            WHERE taker_id = ? AND actor_role = ? AND actor_id = ?
        )'
    );
    $stmt->execute([$takerId, $actorRole, $actorId]);
    $isFavorite = ((int)$stmt->fetchColumn()) === 1;

    $countStmt = $db->prepare('SELECT COUNT(*) FROM taker_favorites WHERE taker_id = ?');
    $countStmt->execute([$takerId]);
    $favoriteCount = (int)$countStmt->fetchColumn();

    respond(true, 'OK', [
        'taker_id' => $takerId,
        'is_favorite' => $isFavorite,
        'favorite_count' => $favoriteCount,
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
