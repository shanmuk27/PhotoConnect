<?php
declare(strict_types=1);

ini_set('display_errors', '1');

$rootDir = dirname(__DIR__);
$migrationDir = __DIR__ . DIRECTORY_SEPARATOR . 'migrations';
$env = pc_migration_load_env([
    $rootDir . DIRECTORY_SEPARATOR . 'php_api' . DIRECTORY_SEPARATOR . '.env',
    $rootDir . DIRECTORY_SEPARATOR . '.env',
]);

$isCli = PHP_SAPI === 'cli';
if (!$isCli) {
    header('Content-Type: text/plain; charset=utf-8');
    $configuredToken = $env['MIGRATION_ADMIN_TOKEN'] ?? $env['ADMIN_API_KEY'] ?? '';
    $providedToken = $_SERVER['HTTP_X_ADMIN_TOKEN'] ?? $_GET['token'] ?? '';
    if ($configuredToken === '' || !hash_equals((string)$configuredToken, (string)$providedToken)) {
        http_response_code(403);
        echo "Forbidden. Set MIGRATION_ADMIN_TOKEN in php_api/.env and pass it as X-Admin-Token.\n";
        exit(1);
    }
}

$args = $isCli ? array_slice($argv, 1) : [];
$baseline = in_array('--baseline', $args, true) || (!$isCli && isset($_GET['baseline']));
$dryRun = in_array('--dry-run', $args, true) || (!$isCli && isset($_GET['dry_run']));

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
            PDO::MYSQL_ATTR_MULTI_STATEMENTS => false,
        ]
    );

    pc_migration_ensure_table($pdo);
    $files = glob($migrationDir . DIRECTORY_SEPARATOR . '*.sql') ?: [];
    sort($files, SORT_STRING);
    $applied = pc_migration_applied_versions($pdo);

    echo "PhotoConnect migration runner\n";
    echo "Database: {$dbName} @ {$dbHost}\n";
    echo $baseline ? "Mode: baseline existing files\n" : ($dryRun ? "Mode: dry run\n" : "Mode: apply pending\n");
    echo "\n";

    $pendingCount = 0;
    foreach ($files as $file) {
        $version = basename($file);
        if (isset($applied[$version])) {
            echo "SKIP {$version}\n";
            continue;
        }
        $pendingCount++;

        if ($baseline) {
            if (!$dryRun) {
                pc_migration_record($pdo, $version);
            }
            echo "BASELINE {$version}\n";
            continue;
        }

        if ($dryRun) {
            echo "PENDING {$version}\n";
            continue;
        }

        echo "APPLY {$version}\n";
        $sql = file_get_contents($file);
        if ($sql === false) {
            throw new RuntimeException("Could not read migration {$version}");
        }
        foreach (pc_migration_split_sql($sql) as $statement) {
            $pdo->exec($statement);
        }
        pc_migration_record($pdo, $version);
        echo "DONE {$version}\n";
    }

    echo "\n";
    echo $pendingCount === 0 ? "No pending migrations.\n" : "Processed {$pendingCount} migration(s).\n";
    echo "OK\n";
} catch (Throwable $e) {
    http_response_code(500);
    echo "FAILED: " . $e->getMessage() . "\n";
    exit(1);
}

function pc_migration_load_env(array $paths): array
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
            if ($key === '') {
                continue;
            }
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

function pc_migration_ensure_table(PDO $pdo): void
{
    $pdo->exec(
        "CREATE TABLE IF NOT EXISTS schema_migrations (
            version VARCHAR(191) NOT NULL PRIMARY KEY,
            applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );
}

function pc_migration_applied_versions(PDO $pdo): array
{
    $rows = $pdo->query('SELECT version FROM schema_migrations')->fetchAll(PDO::FETCH_COLUMN);
    $versions = [];
    foreach ($rows as $row) {
        $versions[(string)$row] = true;
    }
    return $versions;
}

function pc_migration_record(PDO $pdo, string $version): void
{
    $stmt = $pdo->prepare('INSERT INTO schema_migrations(version) VALUES(?)');
    $stmt->execute([$version]);
}

/**
 * Splits ordinary SQL migration files while respecting quotes and comments.
 * Stored procedures with custom DELIMITER are intentionally unsupported.
 */
function pc_migration_split_sql(string $sql): array
{
    $statements = [];
    $buffer = '';
    $quote = null;
    $length = strlen($sql);

    for ($i = 0; $i < $length; $i++) {
        $ch = $sql[$i];
        $next = $i + 1 < $length ? $sql[$i + 1] : '';

        if ($quote === null && $ch === '-' && $next === '-') {
            while ($i < $length && $sql[$i] !== "\n") {
                $i++;
            }
            $buffer .= "\n";
            continue;
        }
        if ($quote === null && $ch === '#') {
            while ($i < $length && $sql[$i] !== "\n") {
                $i++;
            }
            $buffer .= "\n";
            continue;
        }
        if ($quote === null && $ch === '/' && $next === '*') {
            $i += 2;
            while ($i + 1 < $length && !($sql[$i] === '*' && $sql[$i + 1] === '/')) {
                $i++;
            }
            $i++;
            continue;
        }

        if (($ch === "'" || $ch === '"') && ($i === 0 || $sql[$i - 1] !== '\\')) {
            if ($quote === null) {
                $quote = $ch;
            } elseif ($quote === $ch) {
                $quote = null;
            }
        }

        if ($ch === ';' && $quote === null) {
            $statement = trim($buffer);
            if ($statement !== '') {
                $statements[] = $statement;
            }
            $buffer = '';
            continue;
        }

        $buffer .= $ch;
    }

    $statement = trim($buffer);
    if ($statement !== '') {
        $statements[] = $statement;
    }
    return $statements;
}
