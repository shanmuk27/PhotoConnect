<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$role = strtolower(trim((string)($body['role'] ?? '')));
$userId = (int)($body['user_id'] ?? 0);
$refreshToken = trim((string)($body['refresh_token'] ?? ''));

if (!in_array($role, ['client', 'taker'], true) || $userId <= 0 || $refreshToken === '') {
    respond(false, 'Invalid refresh request', [], 422);
}

rateLimit('refresh:' . clientIpIdentifier(), 'refresh-ip', 30, 300);

try {
    $db = getDB();
    if (!verifyRefreshToken($db, $role, $userId, $refreshToken)) {
        respond(false, 'Refresh token is invalid or expired', [], 401);
    }

    $newAccessToken = createAccessToken($userId, $role);
    $newRefreshToken = issueRefreshToken();
    storeRefreshToken($db, $role, $userId, $newRefreshToken);

    respond(true, 'Token refreshed', [
        'role' => $role,
        'user_id' => $userId,
        'access_token' => $newAccessToken,
        'refresh_token' => $newRefreshToken,
        'expires_in' => ACCESS_TOKEN_EXPIRE,
        'refresh_expires_in' => REFRESH_TOKEN_EXPIRE,
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
