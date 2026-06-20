<?php
require_once 'config.php';
require_once 'searchInsightsHelpers.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    respond(false, 'Method not allowed', [], 405);
}

try {
    $db = getDB();
    pc_ensure_search_insights_schema($db);
    $limit = min(20, max(1, (int)($_GET['limit'] ?? 10)));
    $stmt = $db->prepare(
        "SELECT
             COALESCE(NULLIF(location_text, ''), NULLIF(query_text, '')) AS title,
             COUNT(*) AS search_count,
             SUM(CASE WHEN result_count > 0 THEN 1 ELSE 0 END) AS successful_count,
             MAX(created_at) AS last_searched_at
         FROM search_events
         WHERE event_type='search'
           AND created_at >= DATE_SUB(NOW(), INTERVAL 14 DAY)
           AND COALESCE(NULLIF(location_text, ''), NULLIF(query_text, '')) IS NOT NULL
         GROUP BY title
         HAVING successful_count > 0
         ORDER BY search_count DESC, last_searched_at DESC
         LIMIT ?"
    );
    $stmt->bindValue(1, $limit, PDO::PARAM_INT);
    $stmt->execute();
    respond(true, 'OK', ['trending' => $stmt->fetchAll()]);
} catch (Throwable $e) {
    respond(false, $e->getMessage(), [], 500);
}

