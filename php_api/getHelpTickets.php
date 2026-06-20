<?php
require_once __DIR__ . '/config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    respond(false, 'Method not allowed', [], 405);
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    $userId = (int)$auth['user_id'];
    $userRole = $auth['role'];
    if ($userRole === 'user') {
        $userRole = 'taker';
    }

    $stmt = $db->prepare(
        "SELECT id, problem, created_at
         FROM help_tickets
         WHERE user_role = ? AND user_id = ?
         ORDER BY created_at DESC"
    );
    $stmt->execute([$userRole, $userId]);
    $items = $stmt->fetchAll();

    respond(true, 'OK', [
        'tickets' => $items
    ]);
} catch (PDOException $e) {
    pc_log_runtime_error('Get help tickets error: ' . $e->getMessage());
    respond(false, 'Internal server error', [], 500);
}
