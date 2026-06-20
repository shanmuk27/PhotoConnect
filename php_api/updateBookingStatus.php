<?php
require_once 'config.php';
require_once 'eventHelpers.php';
require_once 'bookingDayPartHelpers.php';
if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(false, 'Method not allowed', [], 405);

$b = json_decode(file_get_contents('php://input'), true);
$bookingId = (int)($b['booking_id'] ?? 0);
$status = trim((string)($b['status'] ?? ''));
$actorRole = trim((string)($b['actor_role'] ?? ''));
$actorId = (int)($b['actor_id'] ?? 0);

$allowedStatuses = ['Confirmed', 'Cancelled', 'Completed'];
if (
    $bookingId <= 0 ||
    $actorId <= 0 ||
    !in_array($actorRole, ['client', 'taker'], true) ||
    !in_array($status, $allowedStatuses, true)
) {
    respond(false, 'Invalid request payload', [], 422);
}

try {
    $db = getDB();
    ensureEventsSchema($db);
    ensureBookingDayPartSchema($db);
    ensureNotificationDeliveryQueueSchema($db);
    $auth = requireAuthenticatedUser();
    requireActor($db, $auth, $actorRole, $actorId);
    $db->beginTransaction();

    $bookingStmt = $db->prepare(
        'SELECT id, client_id, taker_id, booking_date, day_part, status
         FROM bookings
         WHERE id=?
         LIMIT 1
         FOR UPDATE'
    );
    $bookingStmt->execute([$bookingId]);
    $booking = $bookingStmt->fetch();
    if (!$booking) {
        $db->rollBack();
        respond(false, 'Booking not found', [], 404);
    }

    $currentStatus = $booking['status'];
    if ($currentStatus === $status) {
        $db->commit();
        pc_clear_deferred_notification_deliveries();
        respond(true, 'Booking already up to date', ['booking_id' => $bookingId, 'status' => $status]);
    }

    if ($actorRole === 'client') {
        if ((int)$booking['client_id'] !== $actorId) {
            $db->rollBack();
            respond(false, 'You cannot update this booking', [], 403);
        }
        if ($status !== 'Cancelled') {
            $db->rollBack();
            respond(false, 'Clients can only cancel their bookings', [], 422);
        }
        if (!in_array($currentStatus, ['Pending', 'Confirmed'], true)) {
            $db->rollBack();
            respond(false, 'This booking can no longer be cancelled', [], 409);
        }
    } else {
        if ((int)$booking['taker_id'] !== $actorId) {
            $db->rollBack();
            respond(false, 'You cannot update this booking', [], 403);
        }
        $allowedTransitions = match ($currentStatus) {
            'Pending' => ['Confirmed', 'Cancelled'],
            'Confirmed' => ['Completed', 'Cancelled'],
            default => [],
        };
        if (!in_array($status, $allowedTransitions, true)) {
            $db->rollBack();
            respond(false, 'Invalid booking status transition', [], 409);
        }
    }

    $bookingDayPart = normalizeDayPart($booking['day_part'] ?? 'full_day');
    if ($status === 'Confirmed') {
        $conflictStmt = $db->prepare(
            "SELECT id
             FROM bookings
             WHERE taker_id=? AND booking_date=? AND status IN ('Confirmed','Completed') AND id<>?
               AND " . dayPartConflictSql() . "
             LIMIT 1
             FOR UPDATE"
        );
        $conflictStmt->execute([$booking['taker_id'], $booking['booking_date'], $bookingId, $bookingDayPart, $bookingDayPart]);
        if ($conflictStmt->fetch()) {
            $db->rollBack();
            respond(false, 'Another booking is already confirmed for this date and duration', [], 409);
        }
    }

    $availabilityBefore = fetchAvailabilityRowsForUpdate($db, (int)$booking['taker_id'], (string)$booking['booking_date']);
    $autoCancelledBookingIds = [];
    if ($status === 'Confirmed') {
        $pendingConflictStmt = $db->prepare(
            "SELECT id, client_id
             FROM bookings
             WHERE taker_id=? AND booking_date=? AND status NOT IN ('Confirmed','Completed','Cancelled') AND id<>?
               AND " . dayPartConflictSql() . "
             FOR UPDATE"
        );
        $pendingConflictStmt->execute([$booking['taker_id'], $booking['booking_date'], $bookingId, $bookingDayPart, $bookingDayPart]);
        $pendingConflicts = $pendingConflictStmt->fetchAll();

        if (!empty($pendingConflicts)) {
            $pendingConflictIds = array_map(static fn($row) => (int)$row['id'], $pendingConflicts);
            $placeholders = implode(',', array_fill(0, count($pendingConflictIds), '?'));
            $bulkCancelStmt = $db->prepare("UPDATE bookings SET status='Cancelled', updated_at=NOW() WHERE id IN ($placeholders)");
            $bulkCancelStmt->execute($pendingConflictIds);

            foreach ($pendingConflicts as $pendingConflict) {
                $cancelledId = (int)$pendingConflict['id'];
                upsertEventForBooking($db, $cancelledId);
                $autoCancelledBookingIds[] = $cancelledId;

                createNotification(
                    $db,
                    'client',
                    (int)$pendingConflict['client_id'],
                    'booking_cancelled',
                    'Booking cancelled',
                    'Your pending booking for ' . $booking['booking_date'] . ' was cancelled because the creator confirmed another booking for the same date and time.',
                    [
                        'booking_id' => $cancelledId,
                        'status' => 'Cancelled',
                        'booking_date' => $booking['booking_date'],
                        'auto_cancelled' => true,
                        'confirmed_booking_id' => $bookingId,
                    ]
                );
            }
        }
    }

    $updateStmt = $db->prepare('UPDATE bookings SET status=?, updated_at=NOW() WHERE id=?');
    $updateStmt->execute([$status, $bookingId]);
    upsertEventForBooking($db, $bookingId);
    syncAvailabilityForDate($db, (int)$booking['taker_id'], (string)$booking['booking_date'], $availabilityBefore);

    $partyStmt = $db->prepare(
        'SELECT t.full_name AS taker_name, c.name AS client_name
         FROM bookings b
         JOIN takers t ON t.id = b.taker_id
         JOIN clients c ON c.id = b.client_id
         WHERE b.id = ?
         LIMIT 1'
    );
    $partyStmt->execute([$bookingId]);
    $party = $partyStmt->fetch() ?: [];

    createNotification(
        $db,
        'client',
        (int)$booking['client_id'],
        'booking_status',
        'Booking status updated',
        trim('Your booking with ' . ($party['taker_name'] ?? 'the creator') . ' is now ' . $status . '.'),
        [
            'booking_id' => $bookingId,
            'status' => $status,
            'booking_date' => $booking['booking_date'],
        ]
    );

    if ($actorRole === 'client' && $status === 'Cancelled') {
        createNotification(
            $db,
            'taker',
            (int)$booking['taker_id'],
            'booking_cancelled',
            'Booking cancelled',
            trim(($party['client_name'] ?? 'A client') . ' cancelled the booking for ' . $booking['booking_date'] . '.'),
            [
                'booking_id' => $bookingId,
                'status' => $status,
                'booking_date' => $booking['booking_date'],
            ]
        );
    }

    $db->commit();
    pc_audit_log('booking_status_updated', (int)$auth['user_id'], $actorRole, 'booking', $bookingId, [
        'status' => $status,
        'actor_id' => $actorId,
        'auto_cancelled_booking_ids' => $autoCancelledBookingIds,
    ]);
    pc_respond_then_flush_notifications($db, 'Booking updated', [
        'booking_id' => $bookingId,
        'status' => $status,
        'auto_cancelled_booking_ids' => $autoCancelledBookingIds,
    ]);
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    pc_clear_deferred_notification_deliveries();
    if ($e->getCode() === '23000' || (int)($e->errorInfo[1] ?? 0) === 1062) {
        respond(false, 'Another booking is already confirmed for this date and duration', [], 409);
    }
    respond(false, $e->getMessage(), [], 500);
}
