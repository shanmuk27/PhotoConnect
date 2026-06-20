<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/trustHelpers.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true);
if (!is_array($body)) {
    respond(false, 'Invalid JSON', [], 422);
}

$takerId = (int)($body['taker_id'] ?? 0);
$clientId = (int)($body['client_id'] ?? 0);
$endorse = filter_var($body['endorse'] ?? true, FILTER_VALIDATE_BOOLEAN);
$emailOtp = trim((string)($body['email_otp'] ?? ''));

if ($takerId <= 0 || $clientId <= 0) {
    respond(false, 'Missing taker_id or client_id', [], 422);
}

try {
    $db = getDB();
    ensureTrustSchema($db);
    $auth = requireAuthenticatedUser();
    authorizeClientProfile($db, $auth, $clientId);

    $bookingStmt = $db->prepare(
        "SELECT id
         FROM bookings
         WHERE client_id=? AND taker_id=? AND status='Completed'
         ORDER BY booking_date DESC, id DESC
         LIMIT 1"
    );
    $bookingStmt->execute([$clientId, $takerId]);
    $booking = $bookingStmt->fetch();
    if (!$booking) {
        respond(false, 'You can endorse only after a completed booking with this taker', [], 409);
    }

    if ($endorse) {
        $emailStmt = $db->prepare('SELECT email FROM users WHERE id=? LIMIT 1');
        $emailStmt->execute([(int)$auth['user_id']]);
        $accountEmail = (string)($emailStmt->fetchColumn() ?: '');
        rateLimit('endorsement_otp:' . pc_normalize_email_for_otp($accountEmail), 'endorsement_otp_verify', 8, 300);
        rateLimit('endorsement_otp_ip:' . clientIpIdentifier(), 'endorsement_otp_verify', 40, 300);
        if ($accountEmail === '' || !pc_verify_email_otp($accountEmail, $emailOtp)) {
            respond(false, 'Verify the email OTP before endorsing this creator', [], 422);
        }
        $stmt = $db->prepare(
            "INSERT INTO taker_endorsements(taker_id, client_id, booking_id)
             VALUES(?,?,?)
             ON DUPLICATE KEY UPDATE booking_id=VALUES(booking_id)"
        );
        $stmt->execute([$takerId, $clientId, (int)$booking['id']]);
    } else {
        respond(false, 'Endorsements cannot be removed after they are submitted', [], 409);
    }

    respond(true, $endorse ? 'Endorsed' : 'Endorsement removed', [
        'taker_trust' => pc_taker_trust_summary($db, $takerId, 'client', $clientId),
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
