<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$b = json_decode(file_get_contents('php://input'), true);
$identity = trim((string)($b['identity'] ?? $b['phone'] ?? ''));
$password = (string)($b['password'] ?? '');

[$loginEmail, $loginPhone10] = parse_login_identity($identity);

if ($identity === '') {
    respond(false, 'Enter your phone number or email address', ['field' => 'identity'], 422);
}
if ($loginEmail === null && ($loginPhone10 === null || strlen($loginPhone10) !== 10)) {
    respond(false, 'Enter a valid phone number or email address', ['field' => 'identity'], 422);
}
if ($password === '') {
    respond(false, 'Enter your password', ['field' => 'password'], 422);
}

$rateIdentity = strtolower($loginEmail ?? $loginPhone10 ?? clientIpIdentifier());
rateLimit('login:' . $rateIdentity, 'login-identity', 8, 300);
rateLimit('login-ip:' . clientIpIdentifier(), 'login-ip', 20, 300);

try {
    $db = getDB();
    
    if ($loginEmail !== null) {
        $stmt = $db->prepare("SELECT id, email, phone, password_hash FROM users WHERE email = ? LIMIT 1");
        $stmt->execute([$loginEmail]);
    } else {
        $stmt = $db->prepare("SELECT id, email, phone, password_hash FROM users WHERE phone = ? LIMIT 1");
        $stmt->execute([$loginPhone10]);
    }
    
    $userRow = $stmt->fetch();
    
    if (!$userRow) {
        $fieldName = $loginEmail !== null ? 'email' : 'phone';
        $message = $loginEmail !== null
            ? 'No account found for this email address'
            : 'No account found for this phone number';
        respond(false, $message, ['field' => $fieldName], 404);
    }

    if (empty($userRow['password_hash']) || !password_verify($password, $userRow['password_hash'])) {
        respond(false, 'Incorrect password', ['field' => 'password'], 401);
    }
    
    $userId = $userRow['id'];
    if (pc_is_user_blocked($db, (int)$userId)) {
        pc_audit_log('blocked_login_attempt', (int)$userId, null, 'user', (int)$userId);
        respond(false, 'This account has been blocked. Please contact support.', ['field' => 'identity'], 403);
    }
    
    // Fetch profiles
    $takerStmt = $db->prepare("SELECT t.*, u.email, u.phone FROM takers t LEFT JOIN users u ON u.id = t.user_id WHERE t.user_id = ? AND t.is_active = 1");
    $takerStmt->execute([$userId]);
    $takers = $takerStmt->fetchAll();
    $takers = hydrateServiceTypes($db, $takers); 
    
    $clientStmt = $db->prepare("SELECT * FROM clients WHERE user_id = ? AND is_active = 1");
    $clientStmt->execute([$userId]);
    $clients = $clientStmt->fetchAll();

    // Auto-create client profile if missing (to fix booking bug for legacy takers)
    if (empty($clients) && !empty($takers)) {
        $firstTaker = $takers[0];
        $db->prepare('INSERT INTO clients(user_id, name, linked_taker_id) VALUES(?,?,?)')
           ->execute([$userId, $firstTaker['full_name'], $firstTaker['id']]);
        
        // Refetch clients
        $clientStmt->execute([$userId]);
        $clients = $clientStmt->fetchAll();
    }

    foreach ($clients as &$client) {
        if (array_key_exists('profile_image_url', $client)) {
            $client['profile_image_url'] = normalizeDeliveredImageUrl($client['profile_image_url'] ?? null);
        }
        if (array_key_exists('profile_thumb_url', $client)) {
            $client['profile_thumb_url'] = normalizeDeliveredImageUrl($client['profile_thumb_url'] ?? null, 'thumb');
        }
    }
    unset($client);
    $takers = pc_sanitize_profile_payloads($takers);
    $clients = pc_sanitize_profile_payloads($clients);

    $displayName = '';
    if (!empty($takers)) {
        $displayName = (string)($takers[0]['full_name'] ?? '');
    }
    if ($displayName === '' && !empty($clients)) {
        $displayName = (string)($clients[0]['name'] ?? '');
    }
    
    $tokenRole = !empty($takers) ? 'taker' : (!empty($clients) ? 'client' : 'client');
    $jwt = createAccessToken((int)$userId, $tokenRole);
    $refreshToken = issueRefreshToken();
    storeRefreshToken($db, $tokenRole, (int)$userId, $refreshToken);
    pc_audit_log('login_success', (int)$userId, $tokenRole, 'user', (int)$userId);
    
    respond(true, 'Login successful', [
        'access_token' => $jwt,
        'refresh_token' => $refreshToken,
        'user' => [
            'id' => $userId,
            'email' => $userRow['email'],
            'phone' => $userRow['phone'],
            'name' => $displayName
        ],
        'profiles' => [
            'takers' => $takers,
            'clients' => $clients
        ],
        'requires_more_info' => (empty($takers) && empty($clients))
    ]);

} catch (PDOException $e) {
    pc_log_runtime_error('Login Error: ' . $e->getMessage());
    respond(false, 'Database error during authentication', [], 500);
}
