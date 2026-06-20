<?php
require_once __DIR__ . '/config.php';
requireAdminRequest();

try {
    $db = getDB();
    $sql = "CREATE TABLE IF NOT EXISTS help_tickets (
        id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
        user_role VARCHAR(20) NOT NULL,
        user_id INT UNSIGNED NOT NULL DEFAULT 0,
        phone VARCHAR(120) NOT NULL,
        problem TEXT NOT NULL,
        logs TEXT,
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        INDEX idx_help_tickets_created(created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    $db->exec($sql);
    respond(true, 'help_tickets table is ready');
} catch (Throwable $e) {
    pc_log_runtime_error('fix_help_tickets: ' . $e->getMessage());
    respond(false, 'Internal server error', [], 500);
}
