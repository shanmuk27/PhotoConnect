<?php

if (!function_exists('pc_ensure_search_insights_schema')) {
    function pc_ensure_search_insights_schema(PDO $db): void
    {
        try {
            $db->exec("CREATE TABLE IF NOT EXISTS search_events (
                id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                actor_role ENUM('client','taker') DEFAULT NULL,
                actor_id INT UNSIGNED DEFAULT NULL,
                event_type ENUM('search','click','favorite','booking','alert_created') NOT NULL DEFAULT 'search',
                query_text VARCHAR(255) DEFAULT NULL,
                location_text VARCHAR(255) DEFAULT NULL,
                service_types_json JSON DEFAULT NULL,
                service_match_mode VARCHAR(24) DEFAULT NULL,
                requested_radius_km DECIMAL(6,2) DEFAULT NULL,
                applied_radius_km DECIMAL(6,2) DEFAULT NULL,
                result_count INT UNSIGNED NOT NULL DEFAULT 0,
                taker_id INT UNSIGNED DEFAULT NULL,
                filters_json JSON DEFAULT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_search_events_created(created_at),
                INDEX idx_search_events_actor(actor_role, actor_id, created_at),
                INDEX idx_search_events_type_created(event_type, created_at),
                INDEX idx_search_events_taker(taker_id, created_at),
                INDEX idx_search_events_query(query_text(80), created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            $db->exec("CREATE TABLE IF NOT EXISTS saved_search_alerts (
                id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
                actor_role ENUM('client','taker') NOT NULL,
                actor_id INT UNSIGNED NOT NULL,
                query_text VARCHAR(255) DEFAULT NULL,
                location_text VARCHAR(255) DEFAULT NULL,
                service_types_json JSON DEFAULT NULL,
                service_match_mode VARCHAR(24) NOT NULL DEFAULT 'smart',
                radius_km DECIMAL(6,2) NOT NULL DEFAULT 25.00,
                filters_json JSON DEFAULT NULL,
                is_active TINYINT(1) NOT NULL DEFAULT 1,
                last_checked_at DATETIME DEFAULT NULL,
                last_notified_at DATETIME DEFAULT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_saved_search_alerts_actor(actor_role, actor_id, is_active),
                INDEX idx_saved_search_alerts_active(is_active, last_checked_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (Throwable $e) {
            pc_log_runtime_error('Search insights schema skipped: ' . $e->getMessage());
        }
    }
}

if (!function_exists('pc_search_actor_from_auth')) {
    function pc_search_actor_from_auth(PDO $db, ?array $auth): array
    {
        if (!$auth) {
            return [null, null];
        }
        $role = (string)($auth['role'] ?? '');
        if (!in_array($role, ['client', 'taker'], true)) {
            return [null, null];
        }
        $id = resolveProfileIdForRole($db, $auth, $role);
        return $id === null ? [null, null] : [$role, (int)$id];
    }
}

if (!function_exists('pc_json_input')) {
    function pc_json_input(): array
    {
        $raw = file_get_contents('php://input');
        $decoded = is_string($raw) ? json_decode($raw, true) : null;
        return is_array($decoded) ? $decoded : [];
    }
}

