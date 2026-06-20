<?php
require_once 'config.php';
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$b = json_decode(file_get_contents('php://input'), true);
if (!is_array($b)) {
    respond(false, 'Invalid JSON', [], 422);
}

$phoneDigits = preg_replace('/\D/', '', trim((string)($b['phone'] ?? '')));
$phoneDigits = normalize_phone_digits_string($phoneDigits);
$emailRaw = trim((string)($b['email'] ?? ''));
$emailOk = filter_var($emailRaw, FILTER_VALIDATE_EMAIL) ?: '';

if (strlen($phoneDigits) !== 10) {
    respond(false, 'Enter a valid 10-digit phone', [], 422);
}
rateLimit('identity-check-ip:' . clientIpIdentifier(), 'identity-check-ip', 20, 300);

try {
    $db = getDB();

    $q = $db->prepare('SELECT 1 FROM users WHERE phone = ? LIMIT 1');
    $q->execute([$phoneDigits]);
    $phoneRegistered = (bool) $q->fetch();

    $emailRegistered = false;
    if ($emailOk !== '') {
        $q = $db->prepare('SELECT 1 FROM users WHERE email = ? LIMIT 1');
        $q->execute([$emailOk]);
        $emailRegistered = (bool) $q->fetch();
    }

    respond(true, 'OK', [
        'phone_registered' => $phoneRegistered,
        'email_registered' => $emailRegistered,
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
