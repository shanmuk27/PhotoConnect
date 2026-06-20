<?php
require_once 'config.php';
require_once 'eventHelpers.php';
if ($_SERVER['REQUEST_METHOD'] !== 'DELETE') respond(false, 'Method not allowed', [], 405);

$b = json_decode(file_get_contents('php://input'), true);
if (!is_array($b)) respond(false, 'Invalid JSON payload', [], 422);
$eventId = (int)($b['event_id'] ?? 0);
if ($eventId <= 0) respond(false, 'Missing event_id', [], 422);

try {
    $db = getDB();
    ensureEventsSchema($db);
    $auth = requireAuthenticatedUser();
    $db->beginTransaction();
    $select = $db->prepare(
        'SELECT id, booking_id, created_by_role, created_by_id, client_id, taker_id
         FROM events
         WHERE id=? LIMIT 1 FOR UPDATE'
    );
    $select->execute([$eventId]);
    $row = $select->fetch();
    if (!$row) {
        $db->rollBack();
        respond(false, 'Event not found', [], 404);
    }
    if (!canAccessEventRow($db, $auth, $row)) {
        $db->rollBack();
        respond(false, 'You cannot delete this event', [], 403);
    }
    if (!empty($row['booking_id'])) {
        $db->rollBack();
        respond(false, 'Booking events cannot be deleted here', [], 409);
    }

    $delete = $db->prepare('DELETE FROM events WHERE id=?');
    $delete->execute([$eventId]);
    $db->commit();
    respond(true, 'Event deleted', ['deleted' => true]);
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    respond(false, $e->getMessage(), [], 500);
}
