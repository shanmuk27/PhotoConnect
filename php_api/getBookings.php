<?php
require_once 'config.php';
require_once 'bookingDayPartHelpers.php';
require_once 'trustHelpers.php';
if ($_SERVER['REQUEST_METHOD'] !== 'GET') respond(false,'Method not allowed',[],405);
$clientId = isset($_GET['clientId']) ? (int)$_GET['clientId'] : null;
$takerId  = isset($_GET['takerId'])  ? (int)$_GET['takerId']  : null;
$page = max(1, (int)($_GET['page'] ?? 1));
$limit = min(50, max(1, (int)($_GET['limit'] ?? 20)));
$offset = ($page - 1) * $limit;
try {
    $db = getDB();
    ensureBookingDayPartSchema($db);
    ensureTrustSchema($db);
    ensureBookingClientVerificationSchema($db);
    $auth = requireAuthenticatedUser();
    $takerNameColumn = firstExistingColumn($db, 'takers', ['full_name', 'name']) ?: 'full_name';
    $takerNameSelect = 't.' . $takerNameColumn . ' AS taker_name';
    $clientVerificationSelect = "
        COALESCE(
            NULLIF(b.client_verification_stage, ''),
            CASE WHEN COALESCE(sv.business_status, 'not_submitted')='approved' THEN 'business_verified' ELSE 'unverified' END
        ) AS client_verification_stage,
        CASE
            WHEN COALESCE(NULLIF(b.client_verification_stage, ''), CASE WHEN COALESCE(sv.business_status, 'not_submitted')='approved' THEN 'business_verified' ELSE 'unverified' END)='trusted'
                THEN 'Trusted studio'
            WHEN COALESCE(NULLIF(b.client_verification_stage, ''), CASE WHEN COALESCE(sv.business_status, 'not_submitted')='approved' THEN 'business_verified' ELSE 'unverified' END)='business_verified'
                THEN 'Business verified'
            ELSE 'Client not verified'
        END AS client_verification_label
    ";
    if ($clientId) {
        authorizeClientProfile($db, $auth, $clientId);
        $countStmt = $db->prepare('SELECT COUNT(*) FROM bookings WHERE client_id=?');
        $countStmt->execute([$clientId]);
        $total = (int)$countStmt->fetchColumn();
        $stmt = $db->prepare('
            SELECT b.*, ' . $takerNameSelect . ', c.name AS client_name, ' . $clientVerificationSelect . '
            FROM bookings b
            JOIN takers t ON t.id = b.taker_id
            JOIN clients c ON c.id = b.client_id
            LEFT JOIN studio_verifications sv ON sv.client_id = b.client_id
            WHERE b.client_id=?
            ORDER BY
                CASE WHEN b.status IN ("Pending","Confirmed") THEN 0 ELSE 1 END,
                CASE WHEN b.status IN ("Pending","Confirmed") THEN b.booking_date END ASC,
                CASE WHEN b.status NOT IN ("Pending","Confirmed") THEN b.booking_date END DESC
            LIMIT ' . $limit . ' OFFSET ' . $offset);
        $stmt->execute([$clientId]);
    } elseif ($takerId) {
        authorizeTaker($db, $auth, $takerId);
        $countStmt = $db->prepare('SELECT COUNT(*) FROM bookings WHERE taker_id=?');
        $countStmt->execute([$takerId]);
        $total = (int)$countStmt->fetchColumn();
        $stmt = $db->prepare('
            SELECT b.*, c.name AS client_name, ' . $takerNameSelect . ', ' . $clientVerificationSelect . '
            FROM bookings b
            JOIN clients c ON c.id = b.client_id
            JOIN takers t ON t.id = b.taker_id
            LEFT JOIN studio_verifications sv ON sv.client_id = b.client_id
            WHERE b.taker_id=?
            ORDER BY
                CASE WHEN b.status IN ("Pending","Confirmed") THEN 0 ELSE 1 END,
                CASE WHEN b.status IN ("Pending","Confirmed") THEN b.booking_date END ASC,
                CASE WHEN b.status NOT IN ("Pending","Confirmed") THEN b.booking_date END DESC
            LIMIT ' . $limit . ' OFFSET ' . $offset);
        $stmt->execute([$takerId]);
    } else { respond(false,'Missing clientId or takerId',[],422); }
    $bookings = $stmt->fetchAll();
    respond(true,'OK',[
        'bookings'=>$bookings,
        'total'=>$total ?? count($bookings),
        'page'=>$page,
        'limit'=>$limit,
        'total_pages'=>(int)max(1, ceil(($total ?? count($bookings)) / max(1, $limit))),
    ]);
} catch(PDOException $e){ respond(false,$e->getMessage(),[],500); }
