<?php
require_once 'config.php';
require_once 'eventHelpers.php';
require_once 'bookingDayPartHelpers.php';
if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(false, 'Method not allowed', [], 405);

$b = json_decode(file_get_contents('php://input'), true);
if (!is_array($b)) respond(false, 'Invalid JSON payload', [], 422);
$eventId = (int)($b['event_id'] ?? 0);
if ($eventId <= 0) respond(false, 'Missing event_id', [], 422);

try {
    $db = getDB();
    ensureEventsSchema($db);
    ensureBookingDayPartSchema($db);
    $auth = requireAuthenticatedUser();
    $db->beginTransaction();

    $select = $db->prepare(
        'SELECT id, booking_id, created_by_role, created_by_id, client_id, taker_id,
                title, event_date, day_part, service_type, location, client_name, client_phone,
                taker_name, total_amount, paid_amount, notes, status
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
        respond(false, 'You cannot update this event', [], 403);
    }

    $title = trim((string)($b['title'] ?? $row['title']));
    $eventDate = trim((string)($b['event_date'] ?? $row['event_date']));
    if ($title === '' || !preg_match('/^\d{4}-\d{2}-\d{2}$/', $eventDate)) {
        respond(false, 'Title and valid event date are required', [], 422);
    }

    $status = normalizeEventStatus((string)($b['status'] ?? $row['status']), !empty($row['booking_id']));
    if ((string)$row['status'] === 'Cancelled') {
        $total = normalizeMoney($row['total_amount']);
        $paid = min($total, normalizeMoney($row['paid_amount']));
        $status = 'Cancelled';
    } else {
        $total = normalizeMoney($b['total_amount'] ?? $row['total_amount']);
        $paid = min($total, normalizeMoney($b['paid_amount'] ?? $row['paid_amount']));
    }
    $dayPart = normalizeDayPart($b['day_part'] ?? ($row['day_part'] ?? 'full_day'));
    $clientPhone = normalize_phone_digits_string((string)($b['client_phone'] ?? $row['client_phone']));
    if ($clientPhone !== '' && strlen($clientPhone) !== 10) {
        respond(false, 'Invalid client phone number', [], 422);
    }

    $stmt = $db->prepare(
        'UPDATE events
         SET title=?, event_date=?, day_part=?, service_type=?, location=?, client_name=?, client_phone=?,
             taker_name=?, total_amount=?, paid_amount=?, notes=?, status=?, updated_at=NOW()
         WHERE id=?'
    );
    $params = [
        $title,
        $eventDate,
        $dayPart,
        trim((string)($b['service_type'] ?? $row['service_type'])) ?: null,
        trim((string)($b['location'] ?? $row['location'])) ?: null,
        trim((string)($b['client_name'] ?? $row['client_name'])) ?: null,
        $clientPhone ?: null,
        trim((string)($b['taker_name'] ?? $row['taker_name'])) ?: null,
        $total,
        $paid,
        trim((string)($b['notes'] ?? $row['notes'])) ?: null,
        $status,
        $eventId,
    ];
    $stmt->execute($params);

    $updated = $db->prepare(
        'SELECT id, booking_id, created_by_role, created_by_id, client_id, taker_id,
                title, event_date, day_part, service_type, location, client_name, client_phone,
                taker_name, total_amount, paid_amount, notes, status, created_at, updated_at
         FROM events
         WHERE id=? LIMIT 1'
    );
    $updated->execute([$eventId]);
    $event = $updated->fetch();
    $db->commit();

    respond(true, 'Event updated', [
        'event_id' => $eventId,
        'event' => $event ? eventRowForResponse($event) : null,
    ]);
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    respond(false, $e->getMessage(), [], 500);
}
