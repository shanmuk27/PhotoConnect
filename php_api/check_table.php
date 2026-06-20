<?php
require_once __DIR__ . '/config.php';
requireAdminRequest();

$db = getDB();
try {
    $stmt = $db->query('DESCRIBE help_tickets');
    respond(true, 'OK', ['columns' => $stmt->fetchAll()]);
} catch (Throwable $e) {
    pc_log_runtime_error('check_table: ' . $e->getMessage());
    respond(false, 'Internal server error', [], 500);
}
