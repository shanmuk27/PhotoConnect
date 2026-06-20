<?php
require_once 'config.php';
if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(false,'Method not allowed',[],405);
$b = json_decode(file_get_contents('php://input'), true);

$auth = null;
try {
    $auth = requireAuthenticatedUser(false);
} catch (Exception $e) {}

$isAuth = $auth !== null && isset($auth['user_id']);

$name  = trim($b['name']  ?? '');
$phone = normalize_phone_digits_string($b['phone'] ?? '');
$email = trim($b['email'] ?? '');
$pass  = $b['password'] ?? '';
$phoneVerificationToken = trim((string)($b['phone_verification_token'] ?? ''));
$emailVerificationToken = trim((string)($b['email_verification_token'] ?? ''));

if (!$name || !$phone || !$email) respond(false,'Missing required fields',[],422);
if (!$isAuth && !$pass) respond(false,'Missing required fields',[],422);

if (strlen($phone) !== 10) respond(false,'Invalid phone number',[],422);
if (!filter_var($email, FILTER_VALIDATE_EMAIL)) respond(false,'Invalid email',[],422);

if (!$isAuth) {
    if (!pc_verify_verification_token($phoneVerificationToken, 'phone', $phone)) respond(false, 'Phone verification failed', [], 422);
    if (!pc_verify_verification_token($emailVerificationToken, 'email', $email)) respond(false, 'Email verification failed', [], 422);
    if (strlen($pass) < 6) respond(false,'Password must be at least 6 characters',[],422);
}

rateLimit('register-client-ip:' . clientIpIdentifier(), 'register-client-ip', 6, 1800);
rateLimit('register-client-phone:' . $phone, 'register-client-phone', 3, 1800);
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
        $userChk = $db->prepare('SELECT id FROM users WHERE phone=? OR email=? LIMIT 1');
        $userChk->execute([$phone, $email]);
        $user = $userChk->fetch();
        
        if ($user) {
            $userId = $user['id'];
            $db->prepare("UPDATE users SET password_hash=? WHERE id=?")->execute([password_hash($pass,PASSWORD_BCRYPT), $userId]);
        } else {
            $userInsert = $db->prepare('INSERT INTO users(email, phone, password_hash) VALUES(?,?,?)');
            $userInsert->execute([$email, $phone, password_hash($pass,PASSWORD_BCRYPT)]);
            $userId = (int)$db->lastInsertId();
        }
    }
    
    $chkClient = $db->prepare('SELECT id FROM clients WHERE user_id=? LIMIT 1');
    $chkClient->execute([$userId]);
    if ($chkClient->fetch()) {
        $db->rollBack();
        respond(false,'User already has a client profile',[],409);
    }
    
    $stmt = $db->prepare('INSERT INTO clients(user_id, name) VALUES(?,?)');
    $stmt->execute([$userId, $name]);
    
    $db->commit();
    respond(true,'Registered',['id'=>(int)$db->lastInsertId(), 'user_id'=>$userId],201);
} catch(PDOException $e){ 
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    respond(false,$e->getMessage(),[],500); 
}
