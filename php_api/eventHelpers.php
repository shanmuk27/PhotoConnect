<?php

function ensureEventsSchema(PDO $db): void
{
    return;
}

function normalizeEventStatus(string $status, bool $fromBooking = false): string
{
    $status = trim($status);
    $allowed = $fromBooking
        ? ['Pending', 'Confirmed', 'Completed', 'Cancelled']
        : ['Upcoming', 'Pending', 'Confirmed', 'Completed', 'Cancelled'];
    return in_array($status, $allowed, true) ? $status : ($fromBooking ? 'Pending' : 'Upcoming');
}

function normalizeMoney($value): float
{
    if ($value === null || $value === '') {
        return 0.0;
    }
    return max(0.0, round((float)$value, 2));
}

function resolveEventClientProfileId(PDO $db, array $auth): ?int
{
    $role = (string)($auth['role'] ?? '');
    $userId = (int)($auth['user_id'] ?? 0);
    if ($userId <= 0) {
        return null;
    }

    $activeClause = tableHasColumn($db, 'clients', 'is_active') ? ' AND is_active=1' : '';
    if (tableHasColumn($db, 'clients', 'user_id')) {
        $stmt = $db->prepare(
            'SELECT id
             FROM clients
             WHERE user_id=?' . $activeClause . '
             LIMIT 1'
        );
        $stmt->execute([$userId]);
        $clientId = $stmt->fetchColumn();
        return $clientId !== false ? (int)$clientId : null;
    }

    if ($role === 'client') {
        $stmt = $db->prepare(
            'SELECT id
             FROM clients
             WHERE id=?' . $activeClause . '
             LIMIT 1'
        );
        $stmt->execute([$userId]);
        $clientId = $stmt->fetchColumn();
        return $clientId !== false ? (int)$clientId : null;
    }

    $takerProfileId = resolveEventTakerProfileId($db, $auth);
    if ($takerProfileId !== null && tableHasColumn($db, 'clients', 'linked_taker_id')) {
        $stmt = $db->prepare(
            'SELECT id
             FROM clients
             WHERE linked_taker_id=?' . $activeClause . '
             LIMIT 1'
        );
        $stmt->execute([$takerProfileId]);
        $clientId = $stmt->fetchColumn();
        return $clientId !== false ? (int)$clientId : null;
    }

    return null;
}

function resolveEventTakerProfileId(PDO $db, array $auth): ?int
{
    $role = (string)($auth['role'] ?? '');
    $userId = (int)($auth['user_id'] ?? 0);
    if ($userId <= 0) {
        return null;
    }

    $activeClause = tableHasColumn($db, 'takers', 'is_active') ? ' AND is_active=1' : '';
    if (tableHasColumn($db, 'takers', 'user_id')) {
        $stmt = $db->prepare(
            'SELECT id
             FROM takers
             WHERE user_id=?' . $activeClause . '
             LIMIT 1'
        );
        $stmt->execute([$userId]);
        $takerId = $stmt->fetchColumn();
        if ($takerId !== false) {
            return (int)$takerId;
        }
    }

    if ($role === 'taker') {
        $stmt = $db->prepare(
            'SELECT id
             FROM takers
             WHERE id=?' . $activeClause . '
             LIMIT 1'
        );
        $stmt->execute([$userId]);
        $takerId = $stmt->fetchColumn();
        return $takerId !== false ? (int)$takerId : null;
    }

    return null;
}

function eventRowForResponse(array $row): array
{
    $total = normalizeMoney($row['total_amount'] ?? 0);
    $paid = min($total, normalizeMoney($row['paid_amount'] ?? 0));
    $row['id'] = (int)$row['id'];
    $row['booking_id'] = isset($row['booking_id']) ? (int)$row['booking_id'] : null;
    $row['created_by_id'] = (int)$row['created_by_id'];
    $row['client_id'] = isset($row['client_id']) ? (int)$row['client_id'] : null;
    $row['taker_id'] = isset($row['taker_id']) ? (int)$row['taker_id'] : null;
    $row['client_phone'] = normalize_phone_digits_string((string)($row['client_phone'] ?? '')) ?: null;
    $row['taker_phone'] = normalize_phone_digits_string((string)($row['taker_phone'] ?? '')) ?: null;
    $row['day_part'] = $row['day_part'] ?? 'full_day';
    $row['total_amount'] = $total;
    $row['paid_amount'] = $paid;
    $row['balance_amount'] = max(0.0, round($total - $paid, 2));
    return $row;
}

function upsertEventForBooking(PDO $db, int $bookingId): void
{
    ensureEventsSchema($db);
    $stmt = $db->prepare(
        'SELECT b.*, c.name AS client_name, c.phone AS client_phone, t.full_name AS taker_name
         FROM bookings b
         JOIN clients c ON c.id = b.client_id
         JOIN takers t ON t.id = b.taker_id
         WHERE b.id=?
         LIMIT 1'
    );
    $stmt->execute([$bookingId]);
    $booking = $stmt->fetch();
    if (!$booking) {
        return;
    }

    $service = trim((string)($booking['service_type'] ?? ''));
    $title = trim(str_replace('_', ' ', $service));
    if ($title === '') {
        $title = 'Booked event';
    }

    $status = normalizeEventStatus((string)($booking['status'] ?? 'Pending'), true);
    $hasDayPart = tableHasColumn($db, 'events', 'day_part', true) && tableHasColumn($db, 'bookings', 'day_part', true);
    $insert = $db->prepare(
        'INSERT INTO events (
            booking_id, created_by_role, created_by_id, client_id, taker_id, title,
            event_date' . ($hasDayPart ? ', day_part' : '') . ', service_type, location, client_name, client_phone, taker_name,
            notes, status
         ) VALUES (?, \'client\', ?, ?, ?, ?, ?' . ($hasDayPart ? ', ?' : '') . ', ?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE
            client_id=VALUES(client_id),
            taker_id=VALUES(taker_id),
            event_date=VALUES(event_date),' . ($hasDayPart ? '
            day_part=VALUES(day_part),' : '') . '
            service_type=VALUES(service_type),
            location=VALUES(location),
            client_name=VALUES(client_name),
            client_phone=VALUES(client_phone),
            taker_name=VALUES(taker_name),
            notes=VALUES(notes),
            status=VALUES(status),
            updated_at=NOW()'
    );
    $params = [
        $bookingId,
        (int)$booking['client_id'],
        (int)$booking['client_id'],
        (int)$booking['taker_id'],
        ucwords($title),
        $booking['booking_date'],
    ];
    if ($hasDayPart) {
        $params[] = $booking['day_part'] ?? 'full_day';
    }
    $params = array_merge($params, [
        $service !== '' ? $service : null,
        $booking['event_location'] ?: null,
        $booking['client_name'] ?: null,
        $booking['client_phone'] ?: null,
        $booking['taker_name'] ?: null,
        $booking['notes'] ?: null,
        $status,
    ]);
    $insert->execute($params);
}

function canAccessEventRow(PDO $db, array $auth, array $row): bool
{
    $role = (string)$auth['role'];
    $id = (int)$auth['user_id'];
    if (($row['created_by_role'] ?? '') === $role && (int)($row['created_by_id'] ?? 0) === $id) {
        return true;
    }
    $clientProfileId = resolveEventClientProfileId($db, $auth);
    if ($clientProfileId !== null) {
        if ((int)($row['created_by_id'] ?? 0) === $clientProfileId && ($row['created_by_role'] ?? '') === 'client') {
            return true;
        }
        if ((int)($row['client_id'] ?? 0) === $clientProfileId) {
            return true;
        }
    }
    if ($role === 'client' && (int)($row['client_id'] ?? 0) === $id) {
        return true;
    }
    if ($role === 'taker' && (int)($row['taker_id'] ?? 0) === $id) {
        return true;
    }
    return false;
}
