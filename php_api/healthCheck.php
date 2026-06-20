<?php
require_once 'config.php';
requireAdminRequest();

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    respond(false, 'Method not allowed', [], 405);
}

try {
    $db = getDB();

    $takerNameColumn = firstExistingColumn($db, 'takers', ['full_name', 'name']);
    $checks = [
        'php_version' => PHP_VERSION,
        'db_name' => DB_NAME,
        'base_url' => getBaseUrl(),
        'runtime_log' => basename(pc_runtime_log_path()),
        'tables' => [
            'takers' => tableExists($db, 'takers'),
            'clients' => tableExists($db, 'clients'),
            'bookings' => tableExists($db, 'bookings'),
            'availability' => tableExists($db, 'availability'),
            'taker_service_types' => tableExists($db, 'taker_service_types'),
            'rate_limits' => tableExists($db, 'rate_limits'),
            'notifications' => tableExists($db, 'notifications'),
        ],
        'takers_columns' => [
            'name_column' => $takerNameColumn,
            'phone' => tableHasColumn($db, 'takers', 'phone'),
            'email' => tableHasColumn($db, 'takers', 'email'),
            'service_type' => tableHasColumn($db, 'takers', 'service_type'),
            'is_active' => tableHasColumn($db, 'takers', 'is_active'),
            'profile_image_url' => tableHasColumn($db, 'takers', 'profile_image_url'),
            'profile_thumb_url' => tableHasColumn($db, 'takers', 'profile_thumb_url'),
            'profile_image_scope' => tableHasColumn($db, 'takers', 'profile_image_scope'),
            'avg_rating' => tableHasColumn($db, 'takers', 'avg_rating'),
            'review_count' => tableHasColumn($db, 'takers', 'review_count'),
            'is_featured' => tableHasColumn($db, 'takers', 'is_featured'),
        ],
        'clients_columns' => [
            'name' => tableHasColumn($db, 'clients', 'name'),
            'phone' => tableHasColumn($db, 'clients', 'phone'),
            'email' => tableHasColumn($db, 'clients', 'email'),
            'linked_taker_id' => tableHasColumn($db, 'clients', 'linked_taker_id'),
            'is_active' => tableHasColumn($db, 'clients', 'is_active'),
        ],
    ];

    respond(true, 'Health check OK', $checks);
} catch (Throwable $e) {
    pc_log_runtime_error('healthCheck: ' . $e->getMessage());
    respond(false, $e->getMessage(), [], 500);
}
