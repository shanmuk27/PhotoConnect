<?php
require_once 'config.php';
if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(false, 'Method not allowed', [], 405);

$b = json_decode(file_get_contents('php://input'), true);

$auth = null;
try {
    $auth = requireAuthenticatedUser(false); // pass false for "require" so it doesn't die if no token
} catch (Exception $e) {}

$isAuth = $auth !== null && isset($auth['user_id']);

$required = ['full_name', 'phone', 'email', 'pincode', 'city', 'state'];
if (!$isAuth) {
    $required[] = 'password';
}

foreach ($required as $f) {
    if (empty($b[$f])) respond(false, "Missing: $f", [], 422);
}

$serviceTypes = parseServiceTypes($b);

$fullName = trim($b['full_name']);
$phone = normalize_phone_digits_string($b['phone']);
$email = filter_var($b['email'], FILTER_SANITIZE_EMAIL);
$password = isset($b['password']) ? (string)$b['password'] : '';
$pincode = preg_replace('/\D/', '', (string)$b['pincode']);
$city = trim((string)$b['city']);
$state = trim((string)$b['state']);
$area = $city;
$yearsExperience = max(0, min(60, (int)($b['years_experience'] ?? 0)));
$languages = trim((string)($b['languages'] ?? ''));
$instagramUrl = trim((string)($b['instagram_url'] ?? ''));
$youtubeUrl = trim((string)($b['youtube_url'] ?? ''));
$portfolioUrl = trim((string)($b['portfolio_url'] ?? ''));
$socialLinkAdditional1 = trim((string)($b['social_link_additional1'] ?? ''));
$socialLinkAdditional2 = trim((string)($b['social_link_additional2'] ?? ''));
$phoneVerificationToken = trim((string)($b['phone_verification_token'] ?? ''));
$emailVerificationToken = trim((string)($b['email_verification_token'] ?? ''));
$passwordHash = $password !== '' ? password_hash($password, PASSWORD_BCRYPT) : null;

if (strlen($phone) !== 10) respond(false, 'Invalid phone number', [], 422);
if (!filter_var($email, FILTER_VALIDATE_EMAIL)) respond(false, 'Invalid email', [], 422);

if (!$isAuth) {
    if (!pc_verify_verification_token($phoneVerificationToken, 'phone', $phone)) respond(false, 'Phone verification failed', [], 422);
    if (!pc_verify_verification_token($emailVerificationToken, 'email', $email)) respond(false, 'Email verification failed', [], 422);
    if (strlen($password) < 6) respond(false, 'Password must be at least 6 characters', [], 422);
}

if (strlen($pincode) !== 6) respond(false, 'Pincode must be 6 digits', [], 422);
rateLimit('register-taker-ip:' . clientIpIdentifier(), 'register-taker-ip', 6, 1800);
rateLimit('register-taker-phone:' . $phone, 'register-taker-phone', 3, 1800);
rateLimit('register-taker-email:' . strtolower($email), 'register-taker-email', 3, 1800);

foreach ([
    'instagram_url' => $instagramUrl,
    'youtube_url' => $youtubeUrl,
    'portfolio_url' => $portfolioUrl,
    'social_link_additional1' => $socialLinkAdditional1,
    'social_link_additional2' => $socialLinkAdditional2,
] as $field => $value) {
    if ($value !== '' && !filter_var($value, FILTER_VALIDATE_URL)) {
        respond(false, "Invalid $field", [], 422);
    }
}

try {
    $db = getDB();
    $db->beginTransaction();

    $userId = 0;
    
    if ($isAuth) {
        $userId = (int)$auth['user_id'];

        $ownerChk = $db->prepare('SELECT id FROM users WHERE (phone=? OR email=?) AND id<>? LIMIT 1');
        $ownerChk->execute([$phone, $email, $userId]);
        if ($ownerChk->fetch()) {
            $db->rollBack();
            respond(false, 'Phone or email already registered with another account', [], 409);
        }

        $db->prepare(
            "UPDATE users
             SET phone = CASE WHEN phone IS NULL OR phone='' THEN ? ELSE phone END,
                 email = CASE WHEN email IS NULL OR email='' THEN ? ELSE email END
             WHERE id = ?"
        )->execute([$phone, $email, $userId]);
    } else {
        // Check if user exists by phone or email
        $userChk = $db->prepare('SELECT id FROM users WHERE phone=? OR email=? LIMIT 1');
        $userChk->execute([$phone, $email]);
        $user = $userChk->fetch();
        
        if ($user) {
            $userId = $user['id'];
            if ($passwordHash) {
                $db->prepare("UPDATE users SET password_hash=? WHERE id=?")->execute([$passwordHash, $userId]);
            }
        } else {
            $userInsert = $db->prepare('INSERT INTO users(email, phone, password_hash) VALUES(?,?,?)');
            $userInsert->execute([$email, $phone, $passwordHash]);
            $userId = (int)$db->lastInsertId();
        }
    }

    $existingTakerStmt = $db->prepare('SELECT id FROM takers WHERE user_id=? LIMIT 1');
    $existingTakerStmt->execute([$userId]);
    if ($existingTakerStmt->fetch()) {
        $db->rollBack();
        respond(false, 'User already has a creator profile', [], 409);
    }

    $stmt = $db->prepare(
        'INSERT INTO takers(
            user_id, full_name, pincode, area, city, state, service_type,
            years_experience, languages, instagram_url, youtube_url, portfolio_url,
            social_link_additional1, social_link_additional2
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)'
    );
    $stmt->execute([
        $userId,
        $fullName,
        $pincode,
        $area,
        $city,
        $state,
        $serviceTypes[0] ?? 'other',
        $yearsExperience,
        $languages,
        $instagramUrl,
        $youtubeUrl,
        $portfolioUrl,
        $socialLinkAdditional1,
        $socialLinkAdditional2
    ]);
    $takerId = (int)$db->lastInsertId();
    replaceTakerServiceTypes($db, $takerId, $serviceTypes);

    // Auto-create a client profile so the taker can place bookings
    $clientChk = $db->prepare('SELECT id FROM clients WHERE user_id = ? LIMIT 1');
    $clientChk->execute([$userId]);
    $existingClient = $clientChk->fetch();
    $clientId = 0;
    
    if ($existingClient) {
        $clientId = (int)$existingClient['id'];
        $db->prepare('UPDATE clients SET linked_taker_id = ? WHERE id = ?')->execute([$takerId, $clientId]);
    } else {
        $db->prepare('INSERT INTO clients(user_id, name, linked_taker_id) VALUES(?,?,?)')->execute([$userId, $fullName, $takerId]);
        $clientId = (int)$db->lastInsertId();
    }

    $db->commit();
    respond(true, 'Registration successful', ['id' => $takerId, 'user_id' => $userId, 'client_id' => $clientId], 201);
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    respond(false, $e->getMessage(), [], 500);
}
