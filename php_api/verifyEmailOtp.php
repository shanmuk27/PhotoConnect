<?php
require_once __DIR__ . '/config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$input = file_get_contents('php://input');
$data = json_decode($input, true) ?: [];
$email = pc_normalize_email_for_otp((string)($data['email'] ?? ''));
$otp = trim((string)($data['otp'] ?? ''));

if ($email === '' || !filter_var($email, FILTER_VALIDATE_EMAIL) || $otp === '') {
    respond(false, 'Email and OTP are required', [], 400);
}

try {
    rateLimit('email_otp_verify:' . $email, 'verify_email_otp', 8, 300);
    rateLimit('email_otp_verify_ip:' . clientIpIdentifier(), 'verify_email_otp', 40, 300);

    if (pc_verify_email_otp($email, $otp)) {
        respond(true, 'Email verified successfully', [
            'verified' => true,
            'verification_token' => pc_create_verification_token('email', $email),
            'expires_in' => VERIFICATION_TOKEN_EXPIRE,
        ]);
    }

    respond(false, 'Invalid or expired OTP', [], 400);
} catch (PDOException $e) {
    pc_log_runtime_error('Verify email OTP error: ' . $e->getMessage());
    respond(false, 'Internal server error', [], 500);
}
