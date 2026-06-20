<?php
require_once __DIR__ . '/config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$input = file_get_contents('php://input');
$data = json_decode($input, true) ?: [];
$email = pc_normalize_email_for_otp((string)($data['email'] ?? ''));
$purpose = trim((string)($data['purpose'] ?? 'registration'));

if ($email === '' || !filter_var($email, FILTER_VALIDATE_EMAIL)) {
    respond(false, 'Enter a valid email address', [], 400);
}

try {
    rateLimit('email_otp:' . $email, 'send_email_otp', 3, 600);
    rateLimit('email_otp_ip:' . clientIpIdentifier(), 'send_email_otp', 20, 600);

    if ($purpose === 'endorsement') {
        $auth = requireAuthenticatedUser();
        $db = getDB();
        $stmt = $db->prepare('SELECT email FROM users WHERE id=? LIMIT 1');
        $stmt->execute([(int)$auth['user_id']]);
        $accountEmail = pc_normalize_email_for_otp((string)($stmt->fetchColumn() ?: ''));
        if ($accountEmail === '' || !hash_equals($accountEmail, $email)) {
            respond(false, 'Use your account email for endorsement verification', [], 403);
        }
    }

    if ($purpose === 'reset') {
        $db = getDB();
        $check = $db->prepare('SELECT id FROM users WHERE email=? LIMIT 1');
        $check->execute([$email]);
        if (!$check->fetch()) {
            respond(false, 'No account found for this email address', [], 404);
        }
    }

    $otp = pc_generate_email_otp($email);
    $subject = 'Your PhotoConnect verification code';
    $purposeLine = $purpose === 'endorsement'
        ? 'Use this code to confirm your recommendation.'
        : ($purpose === 'update_email'
            ? 'Use this code to verify your new email address.'
            : 'Use this code to continue registration:');
    $text = "Your PhotoConnect verification code is {$otp}. {$purposeLine} It expires in " . (int)(OTP_WINDOW_SECONDS / 60) . " minutes.";
    $html = '<div style="font-family:Arial,sans-serif;line-height:1.5;color:#151515">'
        . '<h2 style="margin:0 0 12px">Verify your PhotoConnect email</h2>'
        . '<p>' . htmlspecialchars($purposeLine, ENT_QUOTES, 'UTF-8') . '</p>'
        . '<div style="font-size:30px;font-weight:700;letter-spacing:6px;margin:18px 0">' . htmlspecialchars($otp, ENT_QUOTES, 'UTF-8') . '</div>'
        . '<p>This code expires in ' . (int)(OTP_WINDOW_SECONDS / 60) . ' minutes.</p>'
        . '<p>If you did not request this, you can ignore this email.</p>'
        . '</div>';

    if (!pc_send_email($email, $subject, $html, $text)) {
        pc_log_runtime_error("Email OTP send failed for {$email}");
        respond(false, 'Could not send verification email. Please try again later.', [], 500);
    }

    respond(true, 'Verification email sent', [
        'sent' => true,
        'expires_in' => OTP_WINDOW_SECONDS,
    ]);
} catch (PDOException $e) {
    pc_log_runtime_error('Send email OTP error: ' . $e->getMessage());
    respond(false, 'Internal server error', [], 500);
}
