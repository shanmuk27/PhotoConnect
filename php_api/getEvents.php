<?php
require_once 'config.php';
require_once 'eventHelpers.php';
if ($_SERVER['REQUEST_METHOD'] !== 'GET') respond(false, 'Method not allowed', [], 405);

$scope = trim((string)($_GET['scope'] ?? 'all'));
$scope = in_array($scope, ['all', 'placed', 'received'], true) ? $scope : 'all';
$page = max(1, (int)($_GET['page'] ?? 1));
$limit = min(80, max(1, (int)($_GET['limit'] ?? 40)));
$offset = ($page - 1) * $limit;

try {
    $db = getDB();
    ensureEventsSchema($db);
    $auth = requireAuthenticatedUser();
    $role = $auth['role'];
    $id = (int)$auth['user_id'];
    $clientProfileId = resolveEventClientProfileId($db, $auth);
    $takerProfileId = resolveEventTakerProfileId($db, $auth);

    if ($role === 'client' && $clientProfileId === null) {
        respond(false, 'Client profile not found', [], 404);
    }
    if ($role === 'taker' && $takerProfileId === null) {
        respond(false, 'Taker profile not found', [], 404);
    }

    $where = [];
    $params = [];
    if ($role === 'client') {
        $where[] = '(client_id=? OR (created_by_role=\'client\' AND created_by_id=?))';
        $params[] = $clientProfileId;
        $params[] = $clientProfileId;
    } else {
        if ($scope === 'placed') {
            if ($clientProfileId !== null) {
                $where[] = "((created_by_role='taker' AND created_by_id=?) OR client_id=?)";
                $params[] = $takerProfileId;
                $params[] = $clientProfileId;
            } else {
                $where[] = "(created_by_role='taker' AND created_by_id=?)";
                $params[] = $takerProfileId;
            }
        } elseif ($scope === 'received') {
            $where[] = 'taker_id=?';
            $params[] = $takerProfileId;
            if ($clientProfileId !== null) {
                $where[] = '(client_id IS NULL OR client_id<>?)';
                $params[] = $clientProfileId;
            }
        } else {
            if ($clientProfileId !== null) {
                $where[] = "(taker_id=? OR client_id=? OR (created_by_role='taker' AND created_by_id=?))";
                $params[] = $takerProfileId;
                $params[] = $clientProfileId;
                $params[] = $takerProfileId;
            } else {
                $where[] = "(taker_id=? OR (created_by_role='taker' AND created_by_id=?))";
                $params[] = $takerProfileId;
                $params[] = $takerProfileId;
            }
        }
    }
    $whereSql = implode(' AND ', $where);

    $countStmt = $db->prepare("SELECT COUNT(*) FROM events WHERE $whereSql");
    $countStmt->execute($params);
    $total = (int)$countStmt->fetchColumn();

    $stmt = $db->prepare(
        "SELECT e.id,
                e.booking_id,
                e.created_by_role,
                e.created_by_id,
                e.client_id,
                e.taker_id,
                e.title,
                e.event_date,
                e.day_part,
                e.service_type,
                e.location,
                COALESCE(NULLIF(e.client_name, ''), c.name) AS client_name,
                COALESCE(NULLIF(e.client_phone, ''), c.phone) AS client_phone,
                COALESCE(NULLIF(e.taker_name, ''), t.full_name) AS taker_name,
                e.total_amount,
                e.paid_amount,
                e.notes,
                e.status,
                e.created_at,
                e.updated_at,
                t.phone AS taker_phone
         FROM (
            SELECT
                id,
                booking_id,
                created_by_role,
                created_by_id,
                client_id,
                taker_id,
                title,
                event_date,
                day_part,
                service_type,
                location,
                client_name,
                client_phone,
                taker_name,
                total_amount,
                paid_amount,
                notes,
                status,
                created_at,
                updated_at
            FROM events
            WHERE $whereSql
            ORDER BY
                CASE WHEN status IN ('Upcoming','Pending','Confirmed') THEN 0 ELSE 1 END,
                CASE WHEN status IN ('Upcoming','Pending','Confirmed') THEN event_date END ASC,
                CASE WHEN status NOT IN ('Upcoming','Pending','Confirmed') THEN event_date END DESC,
                id DESC
            LIMIT $limit OFFSET $offset
         ) e
         LEFT JOIN clients c ON c.id = e.client_id
         LEFT JOIN takers t ON t.id = e.taker_id
         ORDER BY
            CASE WHEN e.status IN ('Upcoming','Pending','Confirmed') THEN 0 ELSE 1 END,
            CASE WHEN e.status IN ('Upcoming','Pending','Confirmed') THEN e.event_date END ASC,
            CASE WHEN e.status NOT IN ('Upcoming','Pending','Confirmed') THEN e.event_date END DESC,
            e.id DESC"
    );
    $stmt->execute($params);
    $events = array_map('eventRowForResponse', $stmt->fetchAll());

    respond(true, 'OK', [
        'events' => $events,
        'total' => $total,
        'page' => $page,
        'limit' => $limit,
        'total_pages' => (int)max(1, ceil($total / max(1, $limit))),
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
