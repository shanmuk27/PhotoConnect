<?php
require_once 'config.php';
require_once 'eventHelpers.php';
require_once 'bookingDayPartHelpers.php';
require_once 'trustHelpers.php';
if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(false, 'Method not allowed', [], 405);

$b = json_decode(file_get_contents('php://input'), true);
$required = ['client_id', 'taker_id', 'booking_date', 'service_type'];
foreach ($required as $f) {
    if (empty($b[$f])) respond(false, "Missing: $f", [], 422);
}

$clientId = (int)$b['client_id'];
$takerId = (int)$b['taker_id'];
$bookingDate = trim($b['booking_date']);
$dayPart = normalizeDayPart($b['day_part'] ?? 'full_day');
$serviceType = trim($b['service_type']);
$eventLocation = trim((string)($b['event_location'] ?? ''));
$notes = trim((string)($b['notes'] ?? ''));
$bookingLockName = '';
$bookingLockAcquired = false;

if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $bookingDate)) {
    respond(false, 'Invalid date format', [], 422);
}
if ($bookingDate < date('Y-m-d')) {
    respond(false, 'Booking date cannot be in the past', [], 422);
}
if (!isAllowedServiceType($serviceType)) {
    respond(false, 'Invalid service_type', [], 422);
}

try {
    $db = getDB();
    ensureEventsSchema($db);
    ensureBookingDayPartSchema($db);
    ensureTrustSchema($db);
    ensureBookingClientVerificationSchema($db);
    ensureNotificationSchema($db);
    ensureNotificationDeliveryQueueSchema($db);
    $auth = requireAuthenticatedUser();
    authorizeClientProfile($db, $auth, $clientId);
    rateLimit('booking_user:' . (int)$auth['user_id'], 'booking-create', 20, 300);
    rateLimit('booking_client:' . $clientId, 'booking-create-client', 10, 300);

    $bookingLockName = 'pc_book_' . $clientId . '_' . $takerId . '_' . $bookingDate;
    $lockStmt = $db->prepare('SELECT GET_LOCK(?, 5)');
    $lockStmt->execute([$bookingLockName]);
    $bookingLockAcquired = ((int)$lockStmt->fetchColumn()) === 1;
    if (!$bookingLockAcquired) {
        respond(false, 'Another booking request is still processing. Please wait a moment.', [], 409);
    }
    $studioTrust = pc_studio_trust_summary($db, $clientId);
    $clientVerificationStage = pc_studio_booking_stage($studioTrust);

    $finishWithError = function (string $message, int $statusCode = 422, array $data = []) use (&$db, &$bookingLockAcquired, $bookingLockName): void {
        if (isset($db) && $db->inTransaction()) {
            $db->rollBack();
        }
        pc_clear_deferred_notification_deliveries();
        if ($bookingLockAcquired && $bookingLockName !== '') {
            $release = $db->prepare('SELECT RELEASE_LOCK(?)');
            $release->execute([$bookingLockName]);
            $bookingLockAcquired = false;
        }
        respond(false, $message, $data, $statusCode);
    };

    $db->beginTransaction();

    $clientStmt = $db->prepare("SELECT id, name FROM clients WHERE id=? AND is_active=1 LIMIT 1");
    $clientStmt->execute([$clientId]);
    $client = $clientStmt->fetch();
    if (!$client) {
        $finishWithError('Client not found', 404);
    }

    $selfCheck = $db->prepare("SELECT linked_taker_id FROM clients WHERE id=? LIMIT 1");
    $selfCheck->execute([$clientId]);
    $clientProfile = $selfCheck->fetch();

    $takerStmt = $db->prepare("SELECT id, full_name FROM takers WHERE id=? AND is_active=1 LIMIT 1");
    $takerStmt->execute([$takerId]);
    $taker = $takerStmt->fetch();
    if (!$taker) {
        $finishWithError('Taker not found', 404);
    }
    if ((int)($clientProfile['linked_taker_id'] ?? 0) === $takerId) {
        $finishWithError('You cannot book your own taker profile', 409);
    }

    $serviceStmt = $db->prepare(
        "SELECT 1 FROM taker_service_types WHERE taker_id=? AND (service_type=? OR service_type='other') LIMIT 1"
    );
    $serviceStmt->execute([$takerId, $serviceType]);
    if (!$serviceStmt->fetch()) {
        $finishWithError('Selected service is not offered by this taker', 422);
    }

    $duplicateClientBookingStmt = $db->prepare(
        "SELECT id, status
         FROM bookings
         WHERE client_id=? AND taker_id=? AND booking_date=? AND status IN ('Pending','Confirmed','Completed')
           AND " . dayPartConflictSql() . "
         LIMIT 1
         FOR UPDATE"
    );
    $duplicateClientBookingStmt->execute([$clientId, $takerId, $bookingDate, $dayPart, $dayPart]);
    if ($duplicateClientBookingStmt->fetch()) {
        $finishWithError('You already sent a booking request for this creator on this date.', 409);
    }

    $existingBookingStmt = $db->prepare(
        "SELECT id, status
         FROM bookings
         WHERE taker_id=? AND booking_date=? AND status IN ('Confirmed','Completed')
           AND " . dayPartConflictSql() . "
         LIMIT 1
         FOR UPDATE"
    );
    $existingBookingStmt->execute([$takerId, $bookingDate, $dayPart, $dayPart]);
    $existingBooking = $existingBookingStmt->fetch();
    if ($existingBooking) {
        $finishWithError('This taker already has an active booking on the selected date', 409);
    }

    // Do not let stale 'Booked' rows from old pending flow block new requests.
    $availStmt = $db->prepare("SELECT status, day_part FROM availability WHERE taker_id=? AND date=? FOR UPDATE");
    $availStmt->execute([$takerId, $bookingDate]);
    $availabilityRows = $availStmt->fetchAll();

    if (!availabilityAllowsDayPart($availabilityRows, $dayPart)) {
        $finishWithError('Taker not available for the selected duration on this date', 409, [
            'requested_day_part' => $dayPart,
        ]);
    }

    $bookingStmt = $db->prepare(
        "INSERT INTO bookings(client_id, taker_id, booking_date, day_part, service_type, event_location, notes, status, client_verification_stage)
         VALUES(?, ?, ?, ?, ?, ?, ?, 'Pending', ?)"
    );
    $bookingStmt->execute([
        $clientId,
        $takerId,
        $bookingDate,
        $dayPart,
        $serviceType,
        $eventLocation !== '' ? $eventLocation : null,
        $notes !== '' ? $notes : null,
        $clientVerificationStage,
    ]);

    $bookingId = (int)$db->lastInsertId();
    upsertEventForBooking($db, $bookingId);

    createNotification(
        $db,
        'taker',
        $takerId,
        'booking_request',
        'New booking request',
        trim(($client['name'] ?? 'A client') . ' requested ' . str_replace('_', ' ', $serviceType) . ' on ' . $bookingDate . '.'),
        [
            'booking_id' => $bookingId,
            'client_id' => $clientId,
            'taker_id' => $takerId,
            'booking_date' => $bookingDate,
            'day_part' => $dayPart,
            'service_type' => $serviceType,
        ]
    );
    createNotification(
        $db,
        'client',
        $clientId,
        'booking_created',
        'Booking request sent',
        trim('Your request to ' . ($taker['full_name'] ?? 'this creator') . ' for ' . $bookingDate . ' is pending confirmation.'),
        [
            'booking_id' => $bookingId,
            'client_id' => $clientId,
            'taker_id' => $takerId,
            'booking_date' => $bookingDate,
            'day_part' => $dayPart,
            'service_type' => $serviceType,
        ]
    );

    // Keep date available while booking is pending; only confirmed/completed should block it.

    $db->commit();
    if ($bookingLockAcquired && $bookingLockName !== '') {
        $release = $db->prepare('SELECT RELEASE_LOCK(?)');
        $release->execute([$bookingLockName]);
        $bookingLockAcquired = false;
    }
    pc_audit_log('booking_created', (int)$auth['user_id'], 'client', 'booking', $bookingId, [
        'client_id' => $clientId,
        'taker_id' => $takerId,
        'booking_date' => $bookingDate,
        'day_part' => $dayPart,
    ]);
    pc_respond_then_flush_notifications($db, 'Booking created', [
        'booking_id' => $bookingId,
        'client_verification_stage' => $clientVerificationStage,
    ], 201);
    
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    pc_clear_deferred_notification_deliveries();
    if (isset($db) && $bookingLockAcquired && $bookingLockName !== '') {
        $release = $db->prepare('SELECT RELEASE_LOCK(?)');
        $release->execute([$bookingLockName]);
        $bookingLockAcquired = false;
    }
    // The UNIQUE constraint acts as our safety net against race conditions
    if ($e->getCode() === '23000') {
        respond(false, 'This taker already has an active booking on the selected date', [], 409);
    }
    if (stripos($e->getMessage(), 'active transaction') !== false) {
        respond(false, 'Booking was received. Please refresh your events if it does not appear.', [], 202);
    }
    respond(false, 'Could not create booking right now. Please try again.', [], 500);
}
