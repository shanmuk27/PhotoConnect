<?php
require_once 'config.php';
if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(false, 'Method not allowed', [], 405);

$b = json_decode(file_get_contents('php://input'), true);
$takerId = (int)($b['taker_id'] ?? 0);
if ($takerId <= 0) respond(false, 'Missing taker_id', [], 422);

$required = ['full_name', 'email', 'pincode', 'city', 'state'];
foreach ($required as $f) {
    if (empty($b[$f])) respond(false, "Missing: $f", [], 422);
}

$serviceTypes = parseServiceTypes($b);
$fullName = trim((string)$b['full_name']);
$email = filter_var($b['email'], FILTER_SANITIZE_EMAIL);
$phoneProvided = array_key_exists('phone', $b);
$phone = $phoneProvided ? normalize_phone_digits_string((string)($b['phone'] ?? '')) : '';
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
$profileImageUrl = trim((string)($b['profile_image_url'] ?? ''));
$emailVerificationToken = trim((string)($b['email_verification_token'] ?? ''));
$phoneVerificationToken = trim((string)($b['phone_verification_token'] ?? ''));

if (!filter_var($email, FILTER_VALIDATE_EMAIL)) respond(false, 'Invalid email', [], 422);
if ($phoneProvided && strlen($phone) !== 10) respond(false, 'Enter a valid 10-digit phone number', [], 422);
if (strlen($pincode) !== 6) respond(false, 'Pincode must be 6 digits', [], 422);
foreach ([
    'instagram_url' => $instagramUrl,
    'youtube_url' => $youtubeUrl,
    'portfolio_url' => $portfolioUrl,
    'social_link_additional1' => $socialLinkAdditional1,
    'social_link_additional2' => $socialLinkAdditional2,
    'profile_image_url' => $profileImageUrl,
] as $field => $value) {
    if ($value !== '' && !filter_var($value, FILTER_VALIDATE_URL)) {
        respond(false, "Invalid $field", [], 422);
    }
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    authorizeTaker($db, $auth, $takerId);
    $db->beginTransaction();

    $lookup = $db->prepare('SELECT id, user_id FROM takers WHERE id=? AND is_active=1 LIMIT 1 FOR UPDATE');
    $lookup->execute([$takerId]);
    $taker = $lookup->fetch();
    if (!$taker) {
        $db->rollBack();
        respond(false, 'Taker not found', [], 404);
    }
    $userId = (int)($taker['user_id'] ?? 0);
    if ($userId <= 0) {
        $db->rollBack();
        respond(false, 'Linked user not found for this taker', [], 404);
    }

    $userLookup = $db->prepare('SELECT email, phone FROM users WHERE id=? LIMIT 1 FOR UPDATE');
    $userLookup->execute([$userId]);
    $user = $userLookup->fetch();
    if (!$user) {
        $db->rollBack();
        respond(false, 'Linked user not found for this taker', [], 404);
    }
    $currentEmail = pc_normalize_email_for_otp((string)($user['email'] ?? ''));
    $currentPhone = normalize_phone_digits_string((string)($user['phone'] ?? ''));
    if (!$phoneProvided) {
        $phone = $currentPhone;
    }
    $emailChanged = !hash_equals($currentEmail, pc_normalize_email_for_otp($email));
    $phoneChanged = $phoneProvided && !hash_equals($currentPhone, $phone);

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

    if ($phoneProvided) {
        $phoneCheck = $db->prepare('SELECT id FROM users WHERE phone=? AND id<>? LIMIT 1');
        $phoneCheck->execute([$phone, $userId]);
        if ($phoneCheck->fetch()) {
            $db->rollBack();
            respond(false, 'Phone already registered', [], 409);
        }
        $db->prepare('UPDATE users SET email=?, phone=? WHERE id=?')->execute([$email, $phone, $userId]);
    } else {
        $db->prepare('UPDATE users SET email=? WHERE id=?')->execute([$email, $userId]);
    }

    $fields = [
        'full_name=?',
        'pincode=?',
        'area=?',
        'city=?',
        'state=?',
        'service_type=?',
        'years_experience=?',
        'languages=?',
        'instagram_url=?',
        'youtube_url=?',
        'portfolio_url=?',
        'social_link_additional1=?',
        'social_link_additional2=?',
        'profile_image_url=COALESCE(?, profile_image_url)',
    ];
    $params = [
        $fullName,
        $pincode,
        $area,
        $city,
        $state,
        $serviceTypes[0],
        $yearsExperience,
        $languages !== '' ? $languages : null,
        $instagramUrl !== '' ? $instagramUrl : null,
        $youtubeUrl !== '' ? $youtubeUrl : null,
        $portfolioUrl !== '' ? $portfolioUrl : null,
        $socialLinkAdditional1 !== '' ? $socialLinkAdditional1 : null,
        $socialLinkAdditional2 !== '' ? $socialLinkAdditional2 : null,
        $profileImageUrl !== '' ? $profileImageUrl : null,
    ];
    if (tableHasColumn($db, 'takers', 'email')) {
        $fields[] = 'email=?';
        $params[] = $email;
    }
    $params[] = $takerId;
    $update = $db->prepare('UPDATE takers SET ' . implode(', ', $fields) . ' WHERE id=?');
    $update->execute($params);
    replaceTakerServiceTypes($db, $takerId, $serviceTypes);

    $clientFields = ['name=?'];
    $clientParams = [$fullName];
    if (tableHasColumn($db, 'clients', 'email')) {
        $clientFields[] = 'email=?';
        $clientParams[] = $email;
    }
    if ($phoneProvided && tableHasColumn($db, 'clients', 'phone')) {
        $clientFields[] = 'phone=?';
        $clientParams[] = $phone;
    }
    $clientParams[] = $takerId;
    $clientUpdate = $db->prepare('UPDATE clients SET ' . implode(', ', $clientFields) . ' WHERE linked_taker_id=?');
    $clientUpdate->execute($clientParams);

    $db->commit();
    respond(true, 'Profile updated', ['id' => $takerId]);
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    respond(false, $e->getMessage(), [], 500);
}
