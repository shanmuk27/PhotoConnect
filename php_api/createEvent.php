<?php
require_once 'config.php';
require_once 'eventHelpers.php';
require_once 'bookingDayPartHelpers.php';
if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(false, 'Method not allowed', [], 405);

$b = json_decode(file_get_contents('php://input'), true);
if (!is_array($b)) respond(false, 'Invalid JSON payload', [], 422);

$title = trim((string)($b['title'] ?? ''));
$eventDate = trim((string)($b['event_date'] ?? ''));
if ($title === '' || !preg_match('/^\d{4}-\d{2}-\d{2}$/', $eventDate)) {
    respond(false, 'Title and valid event date are required', [], 422);
}

try {
    $db = getDB();
    ensureEventsSchema($db);
    ensureBookingDayPartSchema($db);
    ensureEventClientRequestSchema($db);
    $auth = requireAuthenticatedUser();
    $role = $auth['role'];
    $actorId = (int)$auth['user_id'];
    $clientId = isset($b['client_id']) ? (int)$b['client_id'] : null;
    $takerId = isset($b['taker_id']) ? (int)$b['taker_id'] : null;

    if ($role === 'client') {
        $clientProfileId = resolveEventClientProfileId($db, $auth);
        if ($clientProfileId === null) {
            respond(false, 'Client profile not found', [], 404);
        }
        $actorId = $clientProfileId;
        $clientId = $clientProfileId;
    } elseif ($role === 'taker') {
        $takerProfileId = resolveEventTakerProfileId($db, $auth);
        if ($takerProfileId === null) {
            respond(false, 'Taker profile not found', [], 404);
        }
        $actorId = $takerProfileId;
        if ($takerId === null) {
            $takerId = $takerProfileId;
        }
    }

    $total = normalizeMoney($b['total_amount'] ?? 0);
    $paid = min($total, normalizeMoney($b['paid_amount'] ?? 0));
    $status = normalizeEventStatus((string)($b['status'] ?? 'Upcoming'));
    $dayPart = normalizeDayPart($b['day_part'] ?? 'full_day');
    $clientRequestId = preg_replace('/[^a-zA-Z0-9_-]/', '', (string)($b['client_request_id'] ?? ''));
    $clientRequestId = $clientRequestId !== '' ? substr($clientRequestId, 0, 80) : null;
    $clientPhone = normalize_phone_digits_string((string)($b['client_phone'] ?? ''));
    if ($clientPhone !== '' && strlen($clientPhone) !== 10) {
        respond(false, 'Invalid client phone number', [], 422);
    }

    if ($clientRequestId !== null) {
        $existingStmt = $db->prepare(
            'SELECT id, booking_id, client_request_id, created_by_role, created_by_id, client_id, taker_id,
                    title, event_date, day_part, service_type, location, client_name, client_phone,
                    taker_name, total_amount, paid_amount, notes, status, created_at, updated_at
             FROM events
             WHERE created_by_role=? AND created_by_id=? AND client_request_id=?
             LIMIT 1'
        );
        $existingStmt->execute([$role, $actorId, $clientRequestId]);
        $existing = $existingStmt->fetch();
        if ($existing) {
            respond(true, 'Event already created', [
                'event_id' => (int)$existing['id'],
                'event' => eventRowForResponse($existing),
            ], 200);
        }
    }

    $stmt = $db->prepare(
        'INSERT INTO events (
            created_by_role, created_by_id, client_id, taker_id, title, event_date,
            day_part, service_type, location, client_name, client_phone, taker_name,
            total_amount, paid_amount, notes, status, client_request_id
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
    );
    $params = [
        $role,
        $actorId,
        $clientId ?: null,
        $takerId ?: null,
        $title,
        $eventDate,
        $dayPart,
        trim((string)($b['service_type'] ?? '')) ?: null,
        trim((string)($b['location'] ?? '')) ?: null,
        trim((string)($b['client_name'] ?? '')) ?: null,
        $clientPhone ?: null,
        trim((string)($b['taker_name'] ?? '')) ?: null,
        $total,
        $paid,
        trim((string)($b['notes'] ?? '')) ?: null,
        $status,
        $clientRequestId,
    ];
    $stmt->execute($params);

    $eventId = (int)$db->lastInsertId();
    $created = $db->prepare(
        'SELECT id, booking_id, client_request_id, created_by_role, created_by_id, client_id, taker_id,
                title, event_date, day_part, service_type, location, client_name, client_phone,
                taker_name, total_amount, paid_amount, notes, status, created_at, updated_at
         FROM events
         WHERE id=? LIMIT 1'
    );
    $created->execute([$eventId]);
    $event = $created->fetch();

    respond(true, 'Event created', [
        'event_id' => $eventId,
        'event' => $event ? eventRowForResponse($event) : null,
    ], 201);
} catch (PDOException $e) {
    if (($e->errorInfo[1] ?? 0) === 1062 && isset($db) && isset($clientRequestId) && $clientRequestId !== null) {
        $existingStmt = $db->prepare(
            'SELECT id, booking_id, client_request_id, created_by_role, created_by_id, client_id, taker_id,
                    title, event_date, day_part, service_type, location, client_name, client_phone,
                    taker_name, total_amount, paid_amount, notes, status, created_at, updated_at
             FROM events
             WHERE created_by_role=? AND created_by_id=? AND client_request_id=?
             LIMIT 1'
        );
        $existingStmt->execute([$role ?? '', $actorId ?? 0, $clientRequestId]);
        $existing = $existingStmt->fetch();
        if ($existing) {
            respond(true, 'Event already created', [
                'event_id' => (int)$existing['id'],
                'event' => eventRowForResponse($existing),
            ], 200);
        }
    }
    respond(false, $e->getMessage(), [], 500);
}

function ensureEventClientRequestSchema(PDO $db): void
{
    if (!tableHasColumn($db, 'events', 'client_request_id')) {
        $db->exec('ALTER TABLE events ADD COLUMN client_request_id VARCHAR(80) DEFAULT NULL AFTER booking_id');
    }
    try {
        $stmt = $db->prepare(
            'SELECT COUNT(*)
             FROM information_schema.statistics
             WHERE table_schema=? AND table_name=? AND index_name=?'
        );
        $stmt->execute([DB_NAME, 'events', 'uniq_events_client_request']);
        if ((int)$stmt->fetchColumn() === 0) {
            $db->exec('CREATE UNIQUE INDEX uniq_events_client_request ON events(created_by_role, created_by_id, client_request_id)');
        }
    } catch (Throwable $e) {
        pc_log_runtime_error('Could not ensure event request id index: ' . $e->getMessage());
    }
}
