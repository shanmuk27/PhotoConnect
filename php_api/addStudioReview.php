<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/trustHelpers.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true);
if (!is_array($body)) {
    respond(false, 'Invalid JSON', [], 422);
}

$clientId = (int)($body['client_id'] ?? 0);
$takerId = (int)($body['taker_id'] ?? 0);
$rating = (int)($body['rating'] ?? 0);
$comment = trim((string)($body['comment'] ?? ''));

if ($clientId <= 0 || $takerId <= 0 || $rating < 1 || $rating > 5) {
    respond(false, 'Invalid data', [], 422);
}

try {
    $db = getDB();
    ensureTrustSchema($db);
    $auth = requireAuthenticatedUser();
    authorizeTaker($db, $auth, $takerId);

    $bookingStmt = $db->prepare(
        "SELECT id
         FROM bookings
         WHERE client_id=? AND taker_id=? AND status='Completed'
         ORDER BY booking_date DESC, id DESC
         LIMIT 1"
    );
    $bookingStmt->execute([$clientId, $takerId]);
    $booking = $bookingStmt->fetch();
    if (!$booking) {
        respond(false, 'You can rate a studio only after a completed booking', [], 409);
    }

    $dup = $db->prepare('SELECT id FROM studio_reviews WHERE booking_id=? LIMIT 1');
    $dup->execute([(int)$booking['id']]);
    if ($dup->fetch()) {
        respond(false, 'You already rated this studio for the completed booking', [], 409);
    }

    $stmt = $db->prepare('INSERT INTO studio_reviews(client_id, taker_id, booking_id, rating, comment) VALUES(?,?,?,?,?)');
    $stmt->execute([$clientId, $takerId, (int)$booking['id'], $rating, $comment !== '' ? $comment : null]);

    respond(true, 'Studio review added', [
        'id' => (int)$db->lastInsertId(),
        'studio_trust' => pc_studio_trust_summary($db, $clientId),
    ], 201);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
