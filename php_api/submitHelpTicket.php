<?php
require_once __DIR__ . '/config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$input = file_get_contents('php://input');
$data = json_decode($input, true) ?: [];

$problem = trim((string)($data['problem'] ?? ''));
$contact = trim((string)($data['phone'] ?? $data['contact'] ?? ''));
$logs = trim((string)($data['logs'] ?? ''));

if ($problem === '') {
    respond(false, 'Problem description is required', [], 400);
}
if (mb_strlen($problem) > 5000) {
    $problem = mb_substr($problem, 0, 5000);
}
if (mb_strlen($contact) > 120) {
    $contact = mb_substr($contact, 0, 120);
}
if (mb_strlen($logs) > 12000) {
    $logs = mb_substr($logs, -12000);
}

try {
    $db = getDB();
    $user = requireAuthenticatedUser(false);
    $userId = $user !== null ? (int)$user['user_id'] : 0;
    $userRole = $user !== null ? (string)$user['role'] : 'client';
    if ($user === null) {
        $roleColumn = $db->query("SHOW COLUMNS FROM help_tickets LIKE 'user_role'")->fetch(PDO::FETCH_ASSOC);
        $roleType = strtolower((string)($roleColumn['Type'] ?? ''));
        $userRole = (strpos($roleType, 'enum') === false || strpos($roleType, "'guest'") !== false) ? 'guest' : 'client';
    }
    if ($userRole === 'user' || $userRole === 'auto') {
        $userRole = 'taker';
    }
    if ($user === null) {
        rateLimit('support:' . clientIpIdentifier(), 'support-anonymous', 5, 3600);
        if ($contact === '') {
            respond(false, 'Please enter an email or phone number so support can contact you', [], 400);
        }
    }
    $phoneColumn = $db->query("SHOW COLUMNS FROM help_tickets LIKE 'phone'")->fetch(PDO::FETCH_ASSOC);
    $phoneType = strtolower((string)($phoneColumn['Type'] ?? 'varchar(20)'));
    $phoneLimit = 20;
    if (preg_match('/varchar\((\d+)\)/', $phoneType, $m)) {
        $phoneLimit = max(20, (int)$m[1]);
    }
    $storedContact = $contact !== '' ? mb_substr($contact, 0, $phoneLimit) : '';
    $sourcePrefix = $user === null ? "Source: login support\n" : '';
    $problemForTicket = $contact !== ''
        ? "{$sourcePrefix}Contact: {$contact}\n\n{$problem}"
        : $problem;
    $logsForTicket = $contact !== ''
        ? "{$sourcePrefix}Contact: {$contact}\n\n{$logs}"
        : $logs;
    
    $stmt = $db->prepare("
        INSERT INTO help_tickets (user_role, user_id, phone, problem, logs)
        VALUES (?, ?, ?, ?, ?)
    ");
    
    $stmt->execute([
        $userRole,
        $userId,
        $storedContact,
        $problemForTicket,
        $logsForTicket
    ]);

    respond(true, 'Ticket submitted successfully', ['id' => (int)$db->lastInsertId()]);
} catch (PDOException $e) {
    pc_log_runtime_error('Help ticket submission error: ' . $e->getMessage());
    respond(false, 'Internal server error', [], 500);
}
