<?php
require_once __DIR__ . '/config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$input = json_decode(file_get_contents('php://input'), true);
if (!is_array($input)) {
    respond(false, 'Invalid JSON', [], 422);
}

$identity = trim((string)($input['identity'] ?? ''));
$otp = trim((string)($input['otp'] ?? ''));
$newPassword = (string)($input['new_password'] ?? '');

if ($identity === '' || $otp === '' || $newPassword === '') {
    respond(false, 'Identity, OTP and new password are required', [], 422);
}
if (strlen($newPassword) < 6) {
    respond(false, 'Password must be at least 6 characters', [], 422);
}

[$email, $phone] = parse_login_identity($identity);
if ($email === null && $phone === null) {
    respond(false, 'Enter a valid phone number or email', [], 422);
}

try {
    rateLimit('password_reset_ip:' . clientIpIdentifier(), 'password_reset_ip', 20, 600);

    if ($email !== null) {
        $email = pc_normalize_email_for_otp($email);
        rateLimit('password_reset_email:' . $email, 'password_reset_email', 8, 600);
        if (!pc_verify_email_otp($email, $otp)) {
            respond(false, 'Invalid or expired OTP', [], 400);
        }
    } else {
        $phone = normalize_phone_digits_string((string)$phone);
        rateLimit('password_reset_phone:' . $phone, 'password_reset_phone', 8, 600);
        if (!pc_verify_stateless_otp($phone, $otp)) {
            respond(false, 'Invalid or expired OTP', [], 400);
        }
    }

    $db = getDB();
    $hash = password_hash($newPassword, PASSWORD_BCRYPT);
    $updated = 0;

    $db->beginTransaction();
    if ($email !== null) {
        $find = $db->prepare('SELECT id FROM users WHERE email = ? LIMIT 1 FOR UPDATE');
        $find->execute([$email]);
        $userId = (int)($find->fetchColumn() ?: 0);
        if ($userId <= 0) {
            $db->rollBack();
            respond(false, 'No account found for this phone or email', [], 404);
        }
        $stmt = $db->prepare(
            'UPDATE users SET password_hash = ? WHERE id = ?'
        );
        $stmt->execute([$hash, $userId]);
        $updated = 1;
        pc_revoke_user_sessions($db, $userId);
    } else {
        $find = $db->prepare('SELECT id FROM users WHERE phone = ? LIMIT 1 FOR UPDATE');
        $find->execute([$phone]);
        $userId = (int)($find->fetchColumn() ?: 0);
        if ($userId <= 0) {
            $db->rollBack();
            respond(false, 'No account found for this phone or email', [], 404);
        }
        $stmt = $db->prepare(
            'UPDATE users SET password_hash = ? WHERE id = ?'
        );
        $stmt->execute([$hash, $userId]);
        $updated = 1;
        pc_revoke_user_sessions($db, $userId);
    }

    if ($updated <= 0) {
        $db->rollBack();
        respond(false, 'No account found for this phone or email', [], 404);
    }

    $db->commit();
    pc_audit_log('password_reset', (int)$userId, null, 'user', (int)$userId);
    respond(true, 'Password reset successfully', [
        'updated_count' => $updated,
    ]);
} catch (PDOException $e) {
    if (isset($db) && $db instanceof PDO && $db->inTransaction()) {
        $db->rollBack();
    }
    pc_log_runtime_error('Reset password error: ' . $e->getMessage());
    respond(false, 'Internal server error', [], 500);
}
