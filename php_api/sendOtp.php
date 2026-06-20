<?php
require_once __DIR__ . '/config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$input = file_get_contents('php://input');
$data = json_decode($input, true) ?: [];
$phone = trim((string)($data['phone'] ?? ''));
$purpose = trim((string)($data['purpose'] ?? 'registration'));

if (empty($phone) || !preg_match('/^[0-9]{10}$/', $phone)) {
    respond(false, 'Invalid phone number format', [], 400);
}

function enforceMobileOtpCooldown(PDO $db, string $phone, string $purpose): void
{
    $cooldown = (int)OTP_SEND_COOLDOWN_SECONDS;
    if ($cooldown <= 0) {
        return;
    }

    $identifier = 'mobile_otp:' . $purpose . ':' . $phone;
    $action = 'send_otp_cooldown';
    $now = time();

    $db->beginTransaction();
    try {
        $select = $db->prepare(
            'SELECT last_attempt
             FROM rate_limits
             WHERE identifier=? AND action=?
             LIMIT 1
             FOR UPDATE'
        );
        $select->execute([$identifier, $action]);
        $row = $select->fetch();

        if ($row) {
            $lastAttempt = strtotime((string)$row['last_attempt']) ?: 0;
            $elapsed = max(0, $now - $lastAttempt);
            if ($elapsed < $cooldown) {
                $retryAfter = $cooldown - $elapsed;
                $db->commit();
                respond(false, 'Please wait ' . $retryAfter . ' seconds before requesting another mobile OTP.', [
                    'retry_after' => $retryAfter,
                    'cooldown_seconds' => $cooldown,
                    'expires_in' => OTP_WINDOW_SECONDS,
                ], 429);
            }

            $update = $db->prepare(
                'UPDATE rate_limits
                 SET attempts=1, first_attempt=NOW(), last_attempt=NOW()
                 WHERE identifier=? AND action=?'
            );
            $update->execute([$identifier, $action]);
            $db->commit();
            return;
        }

        $insert = $db->prepare(
            'INSERT INTO rate_limits(identifier, action, attempts, first_attempt, last_attempt)
             VALUES(?, ?, 1, NOW(), NOW())'
        );
        $insert->execute([$identifier, $action]);
        $db->commit();
    } catch (Throwable $e) {
        if ($db->inTransaction()) {
            $db->rollBack();
        }
        throw $e;
    }
}

try {
    if ($purpose === 'reset') {
        $db = getDB();
        $check = $db->prepare('SELECT id FROM users WHERE phone=? LIMIT 1');
        $check->execute([$phone]);
        if (!$check->fetch()) {
            respond(false, 'No account found for this phone number', [], 404);
        }
    } else {
        $db = getDB();
    }

    enforceMobileOtpCooldown($db, $phone, $purpose);
    rateLimit('otp_phone:' . $phone, 'send_otp', 3, 600);
    rateLimit('otp_ip:' . clientIpIdentifier(), 'send_otp', 20, 600);

    $otp = pc_generate_stateless_otp($phone);

    // TODO: Integrate actual SMS gateway here.
    // For now, we will log it to the runtime log so the admin can see it in development
    pc_log_runtime_error("OTP for $phone is $otp (Simulated SMS)");

    respond(true, 'OTP sent successfully', [
        'sent' => true,
        'expires_in' => OTP_WINDOW_SECONDS,
        'cooldown_seconds' => OTP_SEND_COOLDOWN_SECONDS,
        'retry_after' => OTP_SEND_COOLDOWN_SECONDS,
    ]);
} catch (Throwable $e) {
    pc_log_runtime_error('Send OTP error: ' . $e->getMessage());
    respond(false, 'Internal server error', [], 500);
}
