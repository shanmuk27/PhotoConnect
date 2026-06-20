<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$notificationId = (int)($body['notification_id'] ?? 0);
$markAll = filter_var($body['mark_all'] ?? false, FILTER_VALIDATE_BOOLEAN);

if (!$markAll && $notificationId <= 0) {
    respond(false, 'Missing notification_id', [], 422);
}

try {
    $db = getDB();
    ensureNotificationSchema($db);
    $auth = requireAuthenticatedUser();
    $requestedRole = trim((string)($body['recipient_role'] ?? $body['recipientRole'] ?? ''));
    $recipientRole = in_array($requestedRole, ['client', 'taker'], true)
        ? $requestedRole
        : (in_array($auth['role'], ['client', 'taker'], true) ? $auth['role'] : 'client');
    $recipientId = resolveProfileIdForRole($db, $auth, $recipientRole);
    if ($recipientId === null) {
        respond(false, 'Notification profile not found', [], 404);
    }

    if ($markAll) {
        $stmt = $db->prepare(
            'UPDATE notifications
             SET is_read = 1, read_at = COALESCE(read_at, NOW())
             WHERE recipient_role = ? AND recipient_id = ? AND is_read = 0'
        );
        $stmt->execute([$recipientRole, $recipientId]);

        respond(true, 'Notifications updated', [
            'notification_id' => 0,
            'is_read' => true,
            'updated_count' => $stmt->rowCount(),
            'mark_all' => true,
        ]);
    }

    $stmt = $db->prepare(
        'UPDATE notifications
         SET is_read = 1, read_at = COALESCE(read_at, NOW())
         WHERE id = ? AND recipient_role = ? AND recipient_id = ?'
    );
    $stmt->execute([$notificationId, $recipientRole, $recipientId]);

    if ($stmt->rowCount() === 0) {
        $existsStmt = $db->prepare(
            'SELECT id
             FROM notifications
             WHERE id = ? AND recipient_role = ? AND recipient_id = ?
             LIMIT 1'
        );
        $existsStmt->execute([$notificationId, $recipientRole, $recipientId]);
        if (!$existsStmt->fetch()) {
            respond(false, 'Notification not found', [], 404);
        }
    }

    respond(true, 'Notification updated', [
        'notification_id' => $notificationId,
        'is_read' => true,
        'updated_count' => $stmt->rowCount(),
        'mark_all' => false,
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
