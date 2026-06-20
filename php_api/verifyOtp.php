<?php
require_once __DIR__ . '/config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$input = file_get_contents('php://input');
$data = json_decode($input, true) ?: [];
$phone = normalize_phone_digits_string((string)($data['phone'] ?? ''));
$otp = trim((string)($data['otp'] ?? ''));

if (strlen($phone) !== 10 || empty($otp)) {
    respond(false, 'Phone and OTP are required', [], 400);
}

try {
    rateLimit('otp_verify_phone:' . $phone, 'verify_otp', 8, 300);
    rateLimit('otp_verify_ip:' . clientIpIdentifier(), 'verify_otp', 40, 300);

    if (pc_verify_stateless_otp($phone, $otp)) {
        respond(true, 'Phone verified successfully', [
            'verified' => true,
            'verification_token' => pc_create_verification_token('phone', $phone),
            'expires_in' => VERIFICATION_TOKEN_EXPIRE,
        ]);
    } else {
        respond(false, 'Invalid OTP', [], 400);
    }
} catch (PDOException $e) {
    pc_log_runtime_error('Verify OTP error: ' . $e->getMessage());
    respond(false, 'Internal server error', [], 500);
}
