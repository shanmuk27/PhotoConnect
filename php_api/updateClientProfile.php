<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true);
if (!is_array($body)) {
    respond(false, 'Invalid JSON', [], 422);
}

$clientId = (int)($body['client_id'] ?? 0);
$name = trim((string)($body['name'] ?? ''));
$email = filter_var((string)($body['email'] ?? ''), FILTER_SANITIZE_EMAIL);
$phone = normalize_phone_digits_string((string)($body['phone'] ?? ''));
$emailVerificationToken = trim((string)($body['email_verification_token'] ?? ''));
$phoneVerificationToken = trim((string)($body['phone_verification_token'] ?? ''));

if ($clientId <= 0) {
    respond(false, 'Missing client_id', [], 422);
}
if ($name === '') {
    respond(false, 'Name is required', [], 422);
}
if (!filter_var($email, FILTER_VALIDATE_EMAIL)) {
    respond(false, 'Invalid email', [], 422);
}
if (strlen($phone) !== 10) {
    respond(false, 'Enter a valid 10-digit phone number', [], 422);
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    authorizeClientProfile($db, $auth, $clientId);

    $db->beginTransaction();
    $lookup = $db->prepare('SELECT id, user_id FROM clients WHERE id=? AND is_active=1 LIMIT 1 FOR UPDATE');
    $lookup->execute([$clientId]);
    $client = $lookup->fetch();
    if (!$client) {
        $db->rollBack();
        respond(false, 'Client profile not found', [], 404);
    }
    $userId = (int)($client['user_id'] ?? 0);
    if ($userId <= 0) {
        $db->rollBack();
        respond(false, 'Linked user not found for this client', [], 404);
    }

    $userLookup = $db->prepare('SELECT email, phone FROM users WHERE id=? LIMIT 1 FOR UPDATE');
    $userLookup->execute([$userId]);
    $user = $userLookup->fetch();
    if (!$user) {
        $db->rollBack();
        respond(false, 'Linked user not found for this client', [], 404);
    }
    $currentEmail = pc_normalize_email_for_otp((string)($user['email'] ?? ''));
    $currentPhone = normalize_phone_digits_string((string)($user['phone'] ?? ''));
    $emailChanged = !hash_equals($currentEmail, pc_normalize_email_for_otp($email));
    $phoneChanged = !hash_equals($currentPhone, $phone);

    if ($emailChanged && !pc_verify_verification_token($emailVerificationToken, 'email', $email)) {
        $db->rollBack();
        respond(false, 'Verify the new email before saving', [], 422);
    }
    if ($phoneChanged && !pc_verify_verification_token($phoneVerificationToken, 'phone', $phone)) {
        $db->rollBack();
        respond(false, 'Verify the new phone number before saving', [], 422);
    }

    $emailCheck = $db->prepare('SELECT id FROM users WHERE email=? AND id<>? LIMIT 1');
    $emailCheck->execute([$email, $userId]);
    if ($emailCheck->fetch()) {
        $db->rollBack();
        respond(false, 'Email already registered', [], 409);
    }

    $phoneCheck = $db->prepare('SELECT id FROM users WHERE phone=? AND id<>? LIMIT 1');
    $phoneCheck->execute([$phone, $userId]);
    if ($phoneCheck->fetch()) {
        $db->rollBack();
        respond(false, 'Phone already registered', [], 409);
    }

    $db->prepare('UPDATE users SET email=?, phone=? WHERE id=?')->execute([$email, $phone, $userId]);

    $clientFields = ['name=?'];
    $clientParams = [$name];
    if (tableHasColumn($db, 'clients', 'email')) {
        $clientFields[] = 'email=?';
        $clientParams[] = $email;
    }
    if (tableHasColumn($db, 'clients', 'phone')) {
        $clientFields[] = 'phone=?';
        $clientParams[] = $phone;
    }
    $clientParams[] = $clientId;
    $db->prepare('UPDATE clients SET ' . implode(', ', $clientFields) . ' WHERE id=?')->execute($clientParams);

    $db->commit();
    respond(true, 'Profile updated', [
        'id' => $clientId,
        'name' => $name,
        'email' => $email,
        'phone' => $phone,
    ]);
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    respond(false, $e->getMessage(), [], 500);
}
