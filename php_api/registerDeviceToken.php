<?php
require_once __DIR__ . '/config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$token = trim((string)($body['token'] ?? ''));
$platform = strtolower(trim((string)($body['platform'] ?? 'android')));
$deviceName = trim((string)($body['device_name'] ?? ''));

if ($token === '' || strlen($token) < 20) {
    respond(false, 'Device token is required', [], 400);
}
if (!in_array($platform, ['android'], true)) {
    $platform = 'android';
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    ensureDeviceTokenSchema($db);

    $userId = (int)$auth['user_id'];
    $role = in_array($auth['role'], ['client', 'taker', 'auto', 'user'], true) ? $auth['role'] : 'client';
    $clientId = resolveProfileIdForRole($db, $auth, 'client');
    $takerId = resolveProfileIdForRole($db, $auth, 'taker');

    $stmt = $db->prepare(
        'INSERT INTO device_tokens (user_id, role, client_id, taker_id, token, platform, device_name, is_active, last_seen_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, 1, NOW())
         ON DUPLICATE KEY UPDATE
             user_id = VALUES(user_id),
             role = VALUES(role),
             client_id = VALUES(client_id),
             taker_id = VALUES(taker_id),
             platform = VALUES(platform),
             device_name = VALUES(device_name),
             is_active = 1,
             last_seen_at = NOW()'
    );
    $stmt->execute([
        $userId,
        $role,
        $clientId,
        $takerId,
        $token,
        $platform,
        $deviceName !== '' ? mb_substr($deviceName, 0, 160) : null,
    ]);

    respond(true, 'Device registered for notifications', [
        'id' => (int)$db->lastInsertId(),
    ]);
} catch (PDOException $e) {
    pc_log_runtime_error('Register device token DB error: ' . $e->getMessage());
    respond(false, 'Unable to register device for notifications', [], 500);
}
