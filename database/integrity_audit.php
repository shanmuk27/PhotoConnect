<?php
declare(strict_types=1);

ini_set('display_errors', '1');
header('Content-Type: text/plain; charset=utf-8');

$rootDir = dirname(__DIR__);
$env = pc_audit_load_env([
    $rootDir . DIRECTORY_SEPARATOR . 'php_api' . DIRECTORY_SEPARATOR . '.env',
    $rootDir . DIRECTORY_SEPARATOR . '.env',
]);

$isCli = PHP_SAPI === 'cli';
if (!$isCli) {
    $configuredToken = $env['MIGRATION_ADMIN_TOKEN'] ?? $env['ADMIN_API_KEY'] ?? '';
    $providedToken = $_SERVER['HTTP_X_ADMIN_TOKEN'] ?? $_GET['token'] ?? '';
    if ($configuredToken === '' || !hash_equals((string)$configuredToken, (string)$providedToken)) {
        http_response_code(403);
        echo "Forbidden. Set MIGRATION_ADMIN_TOKEN in php_api/.env and pass it as X-Admin-Token.\n";
        exit(1);
    }
}

$dbHost = $env['DB_HOST'] ?? 'localhost';
$dbUser = $env['DB_USER'] ?? 'root';
$dbPass = $env['DB_PASS'] ?? '';
$dbName = $env['DB_NAME'] ?? 'photoconnect';

try {
    $pdo = new PDO(
        "mysql:host={$dbHost};dbname={$dbName};charset=utf8mb4",
        $dbUser,
        $dbPass,
        [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
        ]
    );

    echo "PhotoConnect integrity audit\n";
    echo "Database: {$dbName} @ {$dbHost}\n\n";

    $checks = [];
    $checks[] = ['Users email unique', pc_audit_has_unique_column($pdo, 'users', 'email')];
    $checks[] = ['Users phone unique', pc_audit_has_unique_column($pdo, 'users', 'phone')];
    $checks[] = ['Users token_version column', pc_audit_has_column($pdo, 'users', 'token_version')];
    $checks[] = ['Taker phone unique', pc_audit_has_unique_column($pdo, 'takers', 'phone')];
    $checks[] = ['Client phone unique', pc_audit_has_unique_column($pdo, 'clients', 'phone')];
    $checks[] = ['Availability slot unique', pc_audit_has_unique_index($pdo, 'availability', ['taker_id', 'date', 'day_part'])];
    $checks[] = ['Booking confirmed exact slot unique', pc_audit_has_unique_index($pdo, 'bookings', ['taker_id', 'booking_date', 'day_part', 'active_confirmed_token'])];
    $checks[] = ['Booking event unique', pc_audit_has_unique_index($pdo, 'events', ['booking_id'])];
    $checks[] = ['Manual event request id unique', pc_audit_has_unique_index($pdo, 'events', ['created_by_role', 'created_by_id', 'client_request_id'])];
    $checks[] = ['Favorite unique', pc_audit_has_unique_index($pdo, 'taker_favorites', ['taker_id', 'actor_role', 'actor_id'])];
    $checks[] = ['Post upload idempotency unique', pc_audit_has_unique_index($pdo, 'taker_posts', ['taker_id', 'client_upload_id'])];
    $checks[] = ['Booking client FK', pc_audit_has_fk($pdo, 'bookings', 'client_id', 'clients', 'id')];
    $checks[] = ['Booking taker FK', pc_audit_has_fk($pdo, 'bookings', 'taker_id', 'takers', 'id')];
    $checks[] = ['Events booking FK', pc_audit_has_fk($pdo, 'events', 'booking_id', 'bookings', 'id')];
    $checks[] = ['Events client FK', pc_audit_has_fk($pdo, 'events', 'client_id', 'clients', 'id')];
    $checks[] = ['Events taker FK', pc_audit_has_fk($pdo, 'events', 'taker_id', 'takers', 'id')];

    foreach ($checks as [$label, $ok]) {
        echo ($ok ? 'OK   ' : 'MISS ') . $label . "\n";
    }

    echo "\nData quality checks\n";
    pc_audit_count($pdo, 'Duplicate active exact booking slots', "
        SELECT COUNT(*) FROM (
            SELECT taker_id, booking_date, day_part, COUNT(*) c
            FROM bookings
            WHERE status IN ('Confirmed','Completed')
            GROUP BY taker_id, booking_date, day_part
            HAVING c > 1
        ) x
    ");
    pc_audit_count($pdo, 'Duplicate favorite rows', "
        SELECT COUNT(*) FROM (
            SELECT taker_id, actor_role, actor_id, COUNT(*) c
            FROM taker_favorites
            GROUP BY taker_id, actor_role, actor_id
            HAVING c > 1
        ) x
    ");
    pc_audit_count($pdo, 'Duplicate event rows for same booking', "
        SELECT COUNT(*) FROM (
            SELECT booking_id, COUNT(*) c
            FROM events
            WHERE booking_id IS NOT NULL
            GROUP BY booking_id
            HAVING c > 1
        ) x
    ");
    pc_audit_count($pdo, 'Orphan bookings without client', "
        SELECT COUNT(*)
        FROM bookings b
        LEFT JOIN clients c ON c.id = b.client_id
        WHERE c.id IS NULL
    ");
    pc_audit_count($pdo, 'Orphan bookings without taker', "
        SELECT COUNT(*)
        FROM bookings b
        LEFT JOIN takers t ON t.id = b.taker_id
        WHERE t.id IS NULL
    ");
    pc_audit_count($pdo, 'Orphan events with missing booking', "
        SELECT COUNT(*)
        FROM events e
        LEFT JOIN bookings b ON b.id = e.booking_id
        WHERE e.booking_id IS NOT NULL AND b.id IS NULL
    ");

    echo "\nIf every line is OK/0, the database is ready for the hardening migration.\n";
} catch (Throwable $e) {
    http_response_code(500);
    echo "FAILED: " . $e->getMessage() . "\n";
    exit(1);
}

function pc_audit_load_env(array $paths): array
{
    $env = [];
    foreach ($paths as $path) {
        if (!is_file($path)) {
            continue;
        }
        $lines = file($path, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
        if (!is_array($lines)) {
            continue;
        }
        foreach ($lines as $line) {
            $line = trim($line);
            if ($line === '' || $line[0] === '#' || strpos($line, '=') === false) {
                continue;
            }
            [$key, $value] = array_map('trim', explode('=', $line, 2));
            if (
                (str_starts_with($value, '"') && str_ends_with($value, '"')) ||
                (str_starts_with($value, "'") && str_ends_with($value, "'"))
            ) {
                $value = substr($value, 1, -1);
            }
            $env[$key] = $value;
        }
    }
    return $env;
}

function pc_audit_has_unique_column(PDO $pdo, string $table, string $column): bool
{
    $stmt = $pdo->prepare(
        "SELECT COUNT(*)
         FROM INFORMATION_SCHEMA.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = ?
           AND COLUMN_NAME = ?
           AND NON_UNIQUE = 0"
    );
    $stmt->execute([$table, $column]);
    return (int)$stmt->fetchColumn() > 0;
}

function pc_audit_has_column(PDO $pdo, string $table, string $column): bool
{
    $stmt = $pdo->prepare(
        "SELECT COUNT(*)
         FROM INFORMATION_SCHEMA.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = ?
           AND COLUMN_NAME = ?"
    );
    $stmt->execute([$table, $column]);
    return (int)$stmt->fetchColumn() > 0;
}

function pc_audit_has_unique_index(PDO $pdo, string $table, array $columns): bool
{
    $stmt = $pdo->prepare(
        "SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS cols
         FROM INFORMATION_SCHEMA.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = ?
           AND NON_UNIQUE = 0
         GROUP BY INDEX_NAME"
    );
    $stmt->execute([$table]);
    $expected = implode(',', $columns);
    foreach ($stmt->fetchAll() as $row) {
        if ((string)$row['cols'] === $expected) {
            return true;
        }
    }
    return false;
}

function pc_audit_has_fk(PDO $pdo, string $table, string $column, string $refTable, string $refColumn): bool
{
    $stmt = $pdo->prepare(
        "SELECT COUNT(*)
         FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME = ?
           AND COLUMN_NAME = ?
           AND REFERENCED_TABLE_NAME = ?
           AND REFERENCED_COLUMN_NAME = ?"
    );
    $stmt->execute([$table, $column, $refTable, $refColumn]);
    return (int)$stmt->fetchColumn() > 0;
}

function pc_audit_count(PDO $pdo, string $label, string $sql): void
{
    $count = (int)$pdo->query($sql)->fetchColumn();
    echo ($count === 0 ? 'OK   ' : 'WARN ') . $label . ': ' . $count . "\n";
}
