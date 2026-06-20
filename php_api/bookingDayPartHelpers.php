<?php

function normalizeDayPart($value): string
{
    $value = trim((string)$value);
    return in_array($value, ['full_day', 'first_half', 'second_half'], true) ? $value : 'full_day';
}

function oppositeDayPart(string $dayPart): ?string
{
    return match (normalizeDayPart($dayPart)) {
        'first_half' => 'second_half',
        'second_half' => 'first_half',
        default => null,
    };
}

function indexExists(PDO $db, string $tableName, string $indexName): bool
{
    try {
        $stmt = $db->prepare(
            'SELECT COUNT(*)
             FROM information_schema.statistics
             WHERE table_schema=? AND table_name=? AND index_name=?'
        );
        $stmt->execute([DB_NAME, $tableName, $indexName]);
        return ((int)$stmt->fetchColumn()) > 0;
    } catch (Throwable $e) {
        return false;
    }
}

function ensureBookingDayPartSchema(PDO $db): void
{
    return;
}

function dayPartConflictSql(): string
{
    return "(day_part='full_day' OR ?='full_day' OR day_part=?)";
}

function availabilityAllowsDayPart(array $rows, string $requestedDayPart): bool
{
    $requestedDayPart = normalizeDayPart($requestedDayPart);
    $available = [];
    foreach ($rows as $row) {
        if ((string)($row['status'] ?? '') === 'Available') {
            $available[] = normalizeDayPart($row['day_part'] ?? 'full_day');
        }
    }
    $available = array_values(array_unique($available));
    if (in_array('full_day', $available, true)) {
        return true;
    }
    if ($requestedDayPart === 'full_day') {
        return in_array('first_half', $available, true) && in_array('second_half', $available, true);
    }
    return in_array($requestedDayPart, $available, true);
}

function fetchAvailabilityRowsForUpdate(PDO $db, int $takerId, string $date): array
{
    $stmt = $db->prepare(
        'SELECT status, day_part
         FROM availability
         WHERE taker_id=? AND date=?
         FOR UPDATE'
    );
    $stmt->execute([$takerId, $date]);
    return $stmt->fetchAll();
}

function upsertAvailabilityPart(PDO $db, int $takerId, string $date, string $status, string $dayPart): void
{
    $stmt = $db->prepare(
        'INSERT INTO availability(taker_id, date, status, day_part)
         VALUES(?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE status=VALUES(status), updated_at=NOW()'
    );
    $stmt->execute([$takerId, $date, $status, normalizeDayPart($dayPart)]);
}

function replaceAvailabilityWithSinglePart(PDO $db, int $takerId, string $date, string $status, string $dayPart): void
{
    $delete = $db->prepare('DELETE FROM availability WHERE taker_id=? AND date=?');
    $delete->execute([$takerId, $date]);
    upsertAvailabilityPart($db, $takerId, $date, $status, $dayPart);
}

function syncAvailabilityForDate(PDO $db, int $takerId, string $date, array $beforeRows = []): void
{
    $activeStmt = $db->prepare(
        "SELECT day_part
         FROM bookings
         WHERE taker_id=? AND booking_date=? AND status IN ('Confirmed','Completed')
         FOR UPDATE"
    );
    $activeStmt->execute([$takerId, $date]);
    $activeParts = array_values(array_unique(array_map(
        fn($row) => normalizeDayPart($row['day_part'] ?? 'full_day'),
        $activeStmt->fetchAll()
    )));

    if (empty($activeParts)) {
        $hasBookedBefore = false;
        foreach ($beforeRows as $row) {
            if ((string)($row['status'] ?? '') === 'Booked') {
                $hasBookedBefore = true;
                upsertAvailabilityPart($db, $takerId, $date, 'Available', normalizeDayPart($row['day_part'] ?? 'full_day'));
            }
        }
        if (!$hasBookedBefore && empty($beforeRows)) {
            upsertAvailabilityPart($db, $takerId, $date, 'Available', 'full_day');
        }
        return;
    }

    if (
        in_array('full_day', $activeParts, true) ||
        (in_array('first_half', $activeParts, true) && in_array('second_half', $activeParts, true))
    ) {
        replaceAvailabilityWithSinglePart($db, $takerId, $date, 'Booked', 'full_day');
        return;
    }

    $bookedPart = in_array('first_half', $activeParts, true) ? 'first_half' : 'second_half';
    $openPart = oppositeDayPart($bookedPart);
    $hadFullAvailable = false;
    $hadOpenAvailable = false;
    foreach ($beforeRows as $row) {
        $status = (string)($row['status'] ?? '');
        $part = normalizeDayPart($row['day_part'] ?? 'full_day');
        $hadFullAvailable = $hadFullAvailable || ($status === 'Available' && $part === 'full_day');
        $hadOpenAvailable = $hadOpenAvailable || ($status === 'Available' && $part === $openPart);
    }

    $delete = $db->prepare('DELETE FROM availability WHERE taker_id=? AND date=? AND day_part IN (?, ?)');
    $delete->execute([$takerId, $date, 'full_day', $bookedPart]);
    upsertAvailabilityPart($db, $takerId, $date, 'Booked', $bookedPart);

    if ($openPart !== null && ($hadFullAvailable || $hadOpenAvailable)) {
        upsertAvailabilityPart($db, $takerId, $date, 'Available', $openPart);
    }
}
