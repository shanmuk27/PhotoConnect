<?php
require_once 'config.php';
require_once 'searchInsightsHelpers.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

try {
    $db = getDB();
    pc_ensure_search_insights_schema($db);
    $auth = getOptionalAuthenticatedUser();
    [$actorRole, $actorId] = pc_search_actor_from_auth($db, $auth);
    if ($actorRole === null || $actorId === null) {
        respond(false, 'Please sign in to save search alerts', [], 401);
    }
    $input = pc_json_input();
    $serviceTypes = $input['service_types'] ?? [];
    if (!is_array($serviceTypes)) {
        $serviceTypes = [];
    }
    $filters = $input['filters'] ?? [];
    if (!is_array($filters)) {
        $filters = [];
    }
    $radius = isset($input['radius_km']) && is_numeric($input['radius_km'])
        ? min(100.0, max(1.0, (float)$input['radius_km']))
        : 25.0;
    $stmt = $db->prepare(
        "INSERT INTO saved_search_alerts
         (actor_role, actor_id, query_text, location_text, service_types_json, service_match_mode, radius_km, filters_json)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    );
    $stmt->execute([
        $actorRole,
        $actorId,
        trim((string)($input['query_text'] ?? '')),
        trim((string)($input['location_text'] ?? '')),
        json_encode(array_values($serviceTypes), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        trim((string)($input['service_match_mode'] ?? 'smart')) ?: 'smart',
        $radius,
        json_encode($filters, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
    ]);
    $alertId = (int)$db->lastInsertId();
    createNotification(
        $db,
        $actorRole,
        $actorId,
        'search_alert_created',
        'Search alert saved',
        'We will send a daily digest when new matching creators appear.',
        ['alert_id' => $alertId]
    );
    respond(true, 'Search alert saved', ['id' => $alertId]);
} catch (Throwable $e) {
    respond(false, $e->getMessage(), [], 500);
}

