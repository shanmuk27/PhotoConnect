<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    respond(false, 'Method not allowed', [], 405);
}

$page = max(1, (int)($_GET['page'] ?? 1));
$limit = min(50, max(1, (int)($_GET['limit'] ?? 20)));
$offset = ($page - 1) * $limit;

try {
    $db = getDB();
    ensureNotificationSchema($db);
    $auth = requireAuthenticatedUser();
    $requestedRole = trim((string)($_GET['recipientRole'] ?? $_GET['recipient_role'] ?? ''));
    $recipientRole = in_array($requestedRole, ['client', 'taker'], true)
        ? $requestedRole
        : (in_array($auth['role'], ['client', 'taker'], true) ? $auth['role'] : 'client');
    $recipientId = resolveProfileIdForRole($db, $auth, $recipientRole);
    if ($recipientId === null) {
        respond(false, 'Notification profile not found', [], 404);
    }

    $countStmt = $db->prepare(
        'SELECT COUNT(*)
         FROM notifications
         WHERE recipient_role = ? AND recipient_id = ?'
    );
    $countStmt->execute([$recipientRole, $recipientId]);
    $total = (int)$countStmt->fetchColumn();

    $stmt = $db->prepare(
        "SELECT id, recipient_role, recipient_id, type, title, message, payload_json, is_read, read_at, created_at
         FROM notifications
         WHERE recipient_role = ? AND recipient_id = ?
         ORDER BY is_read ASC, created_at DESC, id DESC
         LIMIT $limit OFFSET $offset"
    );
    $stmt->execute([$recipientRole, $recipientId]);
    $items = $stmt->fetchAll();

    foreach ($items as &$item) {
        $item['is_read'] = (int)($item['is_read'] ?? 0) === 1;
        $payloadJson = trim((string)($item['payload_json'] ?? ''));
        $item['payload'] = $payloadJson !== '' ? (json_decode($payloadJson, true) ?: []) : [];
        unset($item['payload_json']);
    }
    unset($item);

    $unreadStmt = $db->prepare(
        'SELECT COUNT(*)
         FROM notifications
         WHERE recipient_role = ? AND recipient_id = ? AND is_read = 0'
    );
    $unreadStmt->execute([$recipientRole, $recipientId]);
    $unreadCount = (int)$unreadStmt->fetchColumn();

    respond(true, 'OK', [
        'notifications' => $items,
        'total' => $total,
        'unread_count' => $unreadCount,
        'page' => $page,
        'limit' => $limit,
        'total_pages' => (int)max(1, ceil($total / max(1, $limit))),
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
