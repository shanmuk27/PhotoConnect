<?php
require_once 'config.php';
if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(false,'Method not allowed',[],405);
$b = json_decode(file_get_contents('php://input'), true);
$takerId  = (int)($b['taker_id']  ?? 0);
$clientId = (int)($b['client_id'] ?? 0);
$rating   = (int)($b['rating']    ?? 0);
$comment  = trim($b['comment'] ?? '');
if (!$takerId || !$clientId || $rating < 1 || $rating > 5) respond(false,'Invalid data',[],422);
try {
    $db = getDB();
    ensureNotificationSchema($db);
    ensureNotificationDeliveryQueueSchema($db);
    $auth = requireAuthenticatedUser();
    authorizeClientProfile($db, $auth, $clientId);
    rateLimit('review_user:' . (int)$auth['user_id'], 'review-create', 20, 300);
    $db->beginTransaction();

    $hasReviewBookingId = tableHasColumn($db, 'reviews', 'booking_id');
    $clientActiveClause = tableHasColumn($db, 'clients', 'is_active') ? ' AND is_active=1' : '';
    $clientLinkedSelect = tableHasColumn($db, 'clients', 'linked_taker_id') ? 'linked_taker_id' : 'NULL AS linked_taker_id';
    $clientStmt = $db->prepare('SELECT name, ' . $clientLinkedSelect . ' FROM clients WHERE id=?' . $clientActiveClause . ' LIMIT 1 FOR UPDATE');
    $clientStmt->execute([$clientId]);
    $client = $clientStmt->fetch();
    if (!$client) {
        $db->rollBack();
        respond(false,'Client not found',[],404);
    }
    if ((int)($client['linked_taker_id'] ?? 0) === $takerId) {
        $db->rollBack();
        respond(false,'You cannot review your own taker profile',[],409);
    }

    $bookingStmt = $db->prepare("
        SELECT id
        FROM bookings
        WHERE client_id=? AND taker_id=? AND status='Completed'
        ORDER BY booking_date DESC
        LIMIT 1
        FOR UPDATE
    ");
    $bookingStmt->execute([$clientId, $takerId]);
    $booking = $bookingStmt->fetch();
    if (!$booking) {
        $db->rollBack();
        respond(false,'You can review only after a completed booking with this taker',[],409);
    }

    if ($hasReviewBookingId) {
        $dupStmt = $db->prepare('SELECT id FROM reviews WHERE booking_id=? LIMIT 1 FOR UPDATE');
        $dupStmt->execute([(int)$booking['id']]);
        if ($dupStmt->fetch()) {
            $db->rollBack();
            respond(false,'You already reviewed this booking',[],409);
        }

        $stmt = $db->prepare('INSERT INTO reviews(taker_id,client_id,booking_id,rating,comment) VALUES(?,?,?,?,?)');
        $stmt->execute([$takerId,$clientId,(int)$booking['id'],$rating,$comment?:null]);
    } else {
        // Legacy schemas may not have reviews.booking_id. Fall back to "one review per client+taker".
        $dupStmt = $db->prepare('SELECT id FROM reviews WHERE taker_id=? AND client_id=? LIMIT 1 FOR UPDATE');
        $dupStmt->execute([$takerId, $clientId]);
        if ($dupStmt->fetch()) {
            $db->rollBack();
            respond(false,'You already reviewed this taker',[],409);
        }

        $stmt = $db->prepare('INSERT INTO reviews(taker_id,client_id,rating,comment) VALUES(?,?,?,?)');
        $stmt->execute([$takerId,$clientId,$rating,$comment?:null]);
    }
    $id = $db->lastInsertId();
    // Update avg_rating
    $avg = $db->prepare('UPDATE takers SET avg_rating=(SELECT AVG(rating) FROM reviews WHERE taker_id=?), review_count=(SELECT COUNT(*) FROM reviews WHERE taker_id=?) WHERE id=?');
    $avg->execute([$takerId,$takerId,$takerId]);
    $takerNameColumn = firstExistingColumn($db, 'takers', ['full_name', 'name']) ?: 'full_name';
    $takerStmt = $db->prepare('SELECT ' . $takerNameColumn . ' AS full_name FROM takers WHERE id=? LIMIT 1');
    $takerStmt->execute([$takerId]);
    $taker = $takerStmt->fetch() ?: [];
    createNotification(
        $db,
        'taker',
        $takerId,
        'new_review',
        'New review received',
        trim(($client['name'] ?? 'A client') . ' left a ' . $rating . '-star review for ' . ($taker['full_name'] ?? 'your profile') . '.'),
        [
            'review_id' => (int)$id,
            'booking_id' => (int)$booking['id'],
            'rating' => $rating,
        ]
    );
    $db->commit();
    pc_respond_then_flush_notifications($db, 'Review added', ['id'=>(int)$id], 201);
} catch(PDOException $e){
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    pc_clear_deferred_notification_deliveries();
    if (($e->errorInfo[1] ?? 0) === 1062) {
        respond(false, 'You already reviewed this booking', [], 409);
    }
    respond(false,$e->getMessage(),[],500);
}
