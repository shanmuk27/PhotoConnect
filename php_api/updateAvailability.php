<?php
require_once 'config.php';
require_once 'bookingDayPartHelpers.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(false, 'Method not allowed', [], 405);

$body = json_decode(file_get_contents('php://input'), true);
if (empty($body['taker_id']) || empty($body['dates']) || !is_array($body['dates'])) {
    respond(false, 'Missing taker_id or dates', [], 422);
}

$takerId = (int)$body['taker_id'];
$validStatuses = ['Available', 'Not Available'];
$today = date('Y-m-d');
$validRows = [];
$skippedPast = 0;

foreach ($body['dates'] as $entry) {
    $date = (string)($entry['date'] ?? '');
    $status = (string)($entry['status'] ?? '');
    if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $date)) continue;
    if (!in_array($status, $validStatuses, true)) continue;
    if ($date < $today) {
        $skippedPast++;
        continue;
    }
    $dayPart = normalizeDayPart($entry['day_part'] ?? 'full_day');
    $validRows[$date . '|' . $dayPart] = [$date, $status, $dayPart];
}

if (empty($validRows)) {
    respond(true, 'No editable dates', [
        'updated_count' => 0,
        'skipped_booked_count' => 0,
        'skipped_past_count' => $skippedPast,
    ]);
}

try {
    $db = getDB();
    ensureBookingDayPartSchema($db);
    $auth = requireAuthenticatedUser();
    authorizeTaker($db, $auth, $takerId);

    $chk = $db->prepare('SELECT id FROM takers WHERE id=? AND is_active=1 LIMIT 1');
    $chk->execute([$takerId]);
    if (!$chk->fetch()) respond(false, 'Taker not found', [], 404);

    $maxRetries = 3;
    $retryCount = 0;

    while (true) {
        try {
            $db->beginTransaction();
            $db->exec('DROP TEMPORARY TABLE IF EXISTS tmp_availability_update');
            $db->exec(
                "CREATE TEMPORARY TABLE tmp_availability_update (
                    date DATE NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    day_part VARCHAR(20) NOT NULL,
                    PRIMARY KEY(date, day_part)
                ) ENGINE=MEMORY"
            );

            $placeholders = [];
            $params = [];
            foreach ($validRows as $row) {
                $placeholders[] = '(?, ?, ?)';
                array_push($params, $row[0], $row[1], $row[2]);
            }
            $insertTmp = $db->prepare(
                'INSERT INTO tmp_availability_update(date, status, day_part) VALUES ' .
                implode(',', $placeholders)
            );
            $insertTmp->execute($params);

            $skippedStmt = $db->prepare(
                "SELECT COUNT(*)
                 FROM tmp_availability_update t
                 WHERE EXISTS (
                     SELECT 1
                     FROM bookings b
                     WHERE b.taker_id=?
                       AND b.booking_date=t.date
                       AND b.status IN ('Confirmed','Completed')
                       AND (BINARY b.day_part='full_day' OR BINARY t.day_part='full_day' OR BINARY b.day_part=BINARY t.day_part)
                     LIMIT 1
                 )"
            );
            $skippedStmt->execute([$takerId]);
            $skippedBooked = (int)$skippedStmt->fetchColumn();

            $deleteFull = $db->prepare(
                "DELETE a
                 FROM availability a
                 JOIN tmp_availability_update t ON t.date=a.date
                 WHERE a.taker_id=?
                   AND BINARY t.day_part='full_day'
                   AND a.day_part IN ('first_half','second_half')
                   AND NOT EXISTS (
                       SELECT 1
                       FROM bookings b
                       WHERE b.taker_id=?
                         AND b.booking_date=t.date
                         AND b.status IN ('Confirmed','Completed')
                         AND (BINARY b.day_part='full_day' OR BINARY t.day_part='full_day' OR BINARY b.day_part=BINARY t.day_part)
                       LIMIT 1
                   )"
            );
            $deleteFull->execute([$takerId, $takerId]);

            $deleteHalf = $db->prepare(
                "DELETE a
                 FROM availability a
                 JOIN tmp_availability_update t ON t.date=a.date
                 WHERE a.taker_id=?
                   AND BINARY t.day_part<>'full_day'
                   AND NOT EXISTS (
                       SELECT 1
                       FROM bookings b
                       WHERE b.taker_id=?
                         AND b.booking_date=t.date
                         AND b.status IN ('Confirmed','Completed')
                         AND (BINARY b.day_part='full_day' OR BINARY t.day_part='full_day' OR BINARY b.day_part=BINARY t.day_part)
                       LIMIT 1
                   )"
            );
            $deleteHalf->execute([$takerId, $takerId]);

            $upsert = $db->prepare(
                "INSERT INTO availability(taker_id, date, status, day_part)
                 SELECT ?, t.date, t.status, t.day_part
                 FROM tmp_availability_update t
                 WHERE NOT EXISTS (
                     SELECT 1
                     FROM bookings b
                     WHERE b.taker_id=?
                       AND b.booking_date=t.date
                       AND b.status IN ('Confirmed','Completed')
                       AND (BINARY b.day_part='full_day' OR BINARY t.day_part='full_day' OR BINARY b.day_part=BINARY t.day_part)
                     LIMIT 1
                 )
                 ON DUPLICATE KEY UPDATE status=VALUES(status), updated_at=NOW()"
            );
            $upsert->execute([$takerId, $takerId]);

            $updatedStmt = $db->prepare(
                "SELECT COUNT(*)
                 FROM tmp_availability_update t
                 WHERE NOT EXISTS (
                     SELECT 1
                     FROM bookings b
                     WHERE b.taker_id=?
                       AND b.booking_date=t.date
                       AND b.status IN ('Confirmed','Completed')
                       AND (BINARY b.day_part='full_day' OR BINARY t.day_part='full_day' OR BINARY b.day_part=BINARY t.day_part)
                     LIMIT 1
                 )"
            );
            $updatedStmt->execute([$takerId]);
            $updated = (int)$updatedStmt->fetchColumn();

            $db->commit();
            respond(true, "Updated $updated date(s)", [
                'updated_count' => $updated,
                'skipped_booked_count' => $skippedBooked,
                'skipped_past_count' => $skippedPast,
            ]);
        } catch (PDOException $e) {
            if ($db->inTransaction()) $db->rollBack();
            if (in_array($e->errorInfo[1] ?? 0, [1213, 1205], true) && $retryCount < $maxRetries) {
                $retryCount++;
                usleep(random_int(10000, 50000));
                continue;
            }
            respond(false, $e->getMessage(), [], 500);
        }
    }
} catch (Throwable $e) {
    respond(false, $e->getMessage(), [], 500);
}
