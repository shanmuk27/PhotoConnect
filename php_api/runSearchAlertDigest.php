<?php
require_once 'config.php';
require_once 'searchInsightsHelpers.php';

requireAdminRequest();

try {
    $db = getDB();
    pc_ensure_search_insights_schema($db);
    $stmt = $db->query(
        "SELECT id, actor_role, actor_id, query_text, location_text, service_types_json,
                service_match_mode, radius_km, filters_json, last_checked_at, last_notified_at
         FROM saved_search_alerts
         WHERE is_active=1
           AND (last_notified_at IS NULL OR last_notified_at < DATE_SUB(NOW(), INTERVAL 20 HOUR))
         ORDER BY COALESCE(last_checked_at, '1970-01-01') ASC
         LIMIT 100"
    );
    $alerts = $stmt->fetchAll();
    $sent = 0;
    foreach ($alerts as $alert) {
        $filters = json_decode((string)($alert['filters_json'] ?? '{}'), true);
        $filters = is_array($filters) ? $filters : [];
        $serviceTypes = json_decode((string)($alert['service_types_json'] ?? '[]'), true);
        $serviceTypes = is_array($serviceTypes) ? $serviceTypes : [];
        $since = $alert['last_checked_at'] ?: date('Y-m-d H:i:s', strtotime('-1 day'));
        $where = ['t.created_at >= ?'];
        $params = [$since];
        $location = trim((string)($alert['location_text'] ?? ''));
        if ($location !== '') {
            $where[] = '(t.city LIKE ? OR t.area LIKE ? OR t.state LIKE ? OR t.pincode LIKE ?)';
            $like = '%' . $location . '%';
            array_push($params, $like, $like, $like, $like);
        }
        if (!empty($serviceTypes) && tableExists($db, 'taker_service_types')) {
            $where[] = 'EXISTS (SELECT 1 FROM taker_service_types tst WHERE tst.taker_id=t.id AND tst.service_type IN (' . implode(',', array_fill(0, count($serviceTypes), '?')) . '))';
            foreach ($serviceTypes as $service) {
                $params[] = (string)$service;
            }
        }
        if (tableHasColumn($db, 'takers', 'is_active')) {
            $where[] = 't.is_active=1';
        }
        $countStmt = $db->prepare('SELECT COUNT(*) FROM takers t WHERE ' . implode(' AND ', $where));
        $countStmt->execute($params);
        $count = (int)$countStmt->fetchColumn();
        $db->prepare('UPDATE saved_search_alerts SET last_checked_at=NOW() WHERE id=?')->execute([(int)$alert['id']]);
        if ($count <= 0) {
            continue;
        }
        createNotification(
            $db,
            (string)$alert['actor_role'],
            (int)$alert['actor_id'],
            'search_alert_digest',
            'New creators match your search',
            $count . ' new creator' . ($count === 1 ? '' : 's') . ' matched your saved search.',
            ['alert_id' => (int)$alert['id'], 'count' => $count]
        );
        $db->prepare('UPDATE saved_search_alerts SET last_notified_at=NOW() WHERE id=?')->execute([(int)$alert['id']]);
        $sent++;
    }
    respond(true, 'Digest complete', ['checked' => count($alerts), 'sent' => $sent]);
} catch (Throwable $e) {
    respond(false, $e->getMessage(), [], 500);
}
