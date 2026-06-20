<?php
require_once 'config.php';
require_once 'searchInsightsHelpers.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

try {
    $db = getDB();
    pc_ensure_search_insights_schema($db);
    $auth = requireAuthenticatedUser();
    [$actorRole, $actorId] = pc_search_actor_from_auth($db, $auth);
    if ($actorRole === null || $actorId === null) {
        respond(false, 'Authentication required', [], 401);
    }
    $input = pc_json_input();
    $eventType = trim((string)($input['event_type'] ?? 'click'));
    if (!in_array($eventType, ['search', 'click', 'favorite', 'booking', 'alert_created'], true)) {
        $eventType = 'click';
    }
    $serviceTypes = $input['service_types'] ?? [];
    if (!is_array($serviceTypes)) {
        $serviceTypes = [];
    }
    $filters = $input['filters'] ?? [];
    if (!is_array($filters)) {
        $filters = [];
    }
    $stmt = $db->prepare(
        "INSERT INTO search_events
         (actor_role, actor_id, event_type, query_text, location_text, service_types_json, service_match_mode,
          requested_radius_km, applied_radius_km, result_count, taker_id, filters_json)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    );
    $stmt->execute([
        $actorRole,
        $actorId,
        $eventType,
        trim((string)($input['query_text'] ?? '')),
        trim((string)($input['location_text'] ?? '')),
        json_encode(array_values($serviceTypes), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        trim((string)($input['service_match_mode'] ?? 'smart')),
        isset($input['requested_radius_km']) && is_numeric($input['requested_radius_km']) ? (float)$input['requested_radius_km'] : null,
        isset($input['applied_radius_km']) && is_numeric($input['applied_radius_km']) ? (float)$input['applied_radius_km'] : null,
        isset($input['result_count']) && is_numeric($input['result_count']) ? (int)$input['result_count'] : 0,
        isset($input['taker_id']) && is_numeric($input['taker_id']) ? (int)$input['taker_id'] : null,
        json_encode($filters, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
    ]);
    respond(true, 'Recorded', ['id' => (int)$db->lastInsertId()]);
} catch (Throwable $e) {
    respond(false, $e->getMessage(), [], 500);
}
