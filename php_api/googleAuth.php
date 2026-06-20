<?php
require_once __DIR__ . '/config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$input = file_get_contents('php://input');
$data = json_decode($input, true) ?: [];
$idToken = trim((string)($data['id_token'] ?? ''));

if (empty($idToken)) {
    respond(false, 'Google ID token is required', [], 400);
}

// Verify the Google ID Token by calling Google's tokeninfo endpoint
$ch = curl_init('https://oauth2.googleapis.com/tokeninfo?id_token=' . urlencode($idToken));
curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
$response = curl_exec($ch);
$httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
curl_close($ch);

if ($httpCode !== 200 || !$response) {
    respond(false, 'Invalid Google ID Token', [], 401);
}

$googleData = json_decode($response, true);
if (empty($googleData['sub']) || empty($googleData['email'])) {
    respond(false, 'Invalid Google ID Token payload', [], 401);
}

$expectedAudience = trim((string)GOOGLE_SERVER_CLIENT_ID);
if ($expectedAudience !== '' && !hash_equals($expectedAudience, (string)($googleData['aud'] ?? ''))) {
    respond(false, 'Google sign-in is not configured for this app', [], 401);
}

$emailVerified = $googleData['email_verified'] ?? false;
if (!($emailVerified === true || $emailVerified === 'true' || $emailVerified === '1' || $emailVerified === 1)) {
    respond(false, 'Google email is not verified', [], 401);
}

$googleId = $googleData['sub'];
$email = $googleData['email'];
$name = $googleData['name'] ?? 'Google User';

try {
    $db = getDB();
    
    // Check if user exists by google_id OR email
    $stmt = $db->prepare("SELECT id, google_id, email, phone FROM users WHERE google_id = ? OR email = ? LIMIT 1");
    $stmt->execute([$googleId, $email]);
    $user = $stmt->fetch();
    
    $userId = 0;
    
    if (!$user) {
        // Create new user explicitly providing NULL for phone to bypass strict mode
        $insert = $db->prepare("INSERT INTO users (google_id, email) VALUES (?, ?)");
        $insert->execute([$googleId, $email]);
        $userId = $db->lastInsertId();
    } else {
        $userId = $user['id'];
        // Update google_id if it was missing (e.g. they registered via email first)
        if (empty($user['google_id'])) {
            $update = $db->prepare("UPDATE users SET google_id = ? WHERE id = ?");
            $update->execute([$googleId, $userId]);
        }
    }
    if (pc_is_user_blocked($db, (int)$userId)) {
        pc_audit_log('blocked_google_login_attempt', (int)$userId, null, 'user', (int)$userId);
        respond(false, 'This account has been blocked. Please contact support.', [], 403);
    }
    
    // Fetch all profiles for this user
    $takerStmt = $db->prepare("SELECT * FROM takers WHERE user_id = ? AND is_active = 1");
    $takerStmt->execute([$userId]);
    $takers = $takerStmt->fetchAll();
    $takers = hydrateServiceTypes($db, $takers); // Uses existing config.php helper
    
    $clientStmt = $db->prepare("SELECT * FROM clients WHERE user_id = ? AND is_active = 1");
    $clientStmt->execute([$userId]);
    $clients = $clientStmt->fetchAll();
    $takers = pc_sanitize_profile_payloads($takers);
    $clients = pc_sanitize_profile_payloads($clients);
    
    // Generate PhotoConnect JWT Access Token
    // We will issue the token for the user_id. In the payload we define the active profile later.
    // For now, let's just generate a base user token.
    $tokenRole = !empty($takers) ? 'taker' : (!empty($clients) ? 'client' : 'client');
    $jwt = createAccessToken((int)$userId, $tokenRole);
    $refreshToken = issueRefreshToken();
    storeRefreshToken($db, $tokenRole, (int)$userId, $refreshToken);
    pc_audit_log('google_login_success', (int)$userId, $tokenRole, 'user', (int)$userId);
    
    respond(true, 'Google login successful', [
        'access_token' => $jwt,
        'refresh_token' => $refreshToken,
        'user' => [
            'id' => $userId,
            'email' => $email,
            'name' => $name, // From google
            'phone' => $user ? $user['phone'] : null
        ],
        'profiles' => [
            'takers' => $takers,
            'clients' => $clients
        ],
        'requires_more_info' => (empty($takers) && empty($clients)) // If they have no profiles, they must create one
    ]);

} catch (PDOException $e) {
    pc_log_runtime_error('Google Auth DB Error: ' . $e->getMessage());
    respond(false, 'Database error during authentication', [], 500);
}
