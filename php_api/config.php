<?php
// ============================================================
// config.php - PhotoConnect API Configuration
// Shared bootstrap for DB access, responses, auth and helpers.
// ============================================================

pc_load_env_files([
    __DIR__ . DIRECTORY_SEPARATOR . '.env',
    dirname(__DIR__) . DIRECTORY_SEPARATOR . '.env',
]);

define('DB_HOST', pc_env('DB_HOST', 'localhost'));
define('DB_USER', pc_env('DB_USER', 'root'));
define('DB_PASS', pc_env('DB_PASS', ''));
define('DB_NAME', pc_env('DB_NAME', 'photoconnect'));
define('DB_PERSISTENT_CONNECTIONS', pc_env('DB_PERSISTENT_CONNECTIONS', '0') === '1');
define('APP_DEBUG', pc_env('APP_DEBUG', '0') === '1');
define('PUBLIC_BASE_URL', pc_env('PUBLIC_BASE_URL', 'https://supriyadigitals.store/phpapp'));
define('JWT_SECRET', pc_env('JWT_SECRET', 'photoconnect-dev-secret-change-me'));
define('GOOGLE_SERVER_CLIENT_ID', pc_env('GOOGLE_SERVER_CLIENT_ID', ''));
define('FCM_SERVER_KEY', pc_env('FCM_SERVER_KEY', ''));
define('FCM_PROJECT_ID', pc_env('FCM_PROJECT_ID', 'photo-connect-abfe1'));
define('FCM_SERVICE_ACCOUNT_FILE', pc_env('FCM_SERVICE_ACCOUNT_FILE', ''));
define('FCM_SERVICE_ACCOUNT_JSON', pc_env('FCM_SERVICE_ACCOUNT_JSON', ''));
define('FCM_SERVICE_ACCOUNT_B64', pc_env('FCM_SERVICE_ACCOUNT_B64', ''));
define('FCM_CURL_CAINFO', pc_env('FCM_CURL_CAINFO', ''));
define('NOTIFICATION_DELIVERY_BATCH_SIZE', max(1, min(50, (int)pc_env('NOTIFICATION_DELIVERY_BATCH_SIZE', '10'))));
define('OTP_WINDOW_SECONDS', max(60, (int)pc_env('OTP_WINDOW_SECONDS', '180')));
define('OTP_SEND_COOLDOWN_SECONDS', max(15, (int)pc_env('OTP_SEND_COOLDOWN_SECONDS', '60')));
define('VERIFICATION_TOKEN_EXPIRE', max(300, (int)pc_env('VERIFICATION_TOKEN_EXPIRE', '900')));
define('SMTP_HOST', pc_env('SMTP_HOST', 'smtp.gmail.com'));
define('SMTP_PORT', max(1, (int)pc_env('SMTP_PORT', '587')));
define('SMTP_USERNAME', pc_env('SMTP_USERNAME', 'srinivasganji027@gmail.com'));
define('SMTP_PASSWORD', pc_env('SMTP_PASSWORD', ''));
define('SMTP_FROM_EMAIL', pc_env('SMTP_FROM_EMAIL', 'srinivasganji027@gmail.com'));
define('SMTP_FROM_NAME', pc_env('SMTP_FROM_NAME', 'PhotoConnect'));
define('ACCESS_TOKEN_EXPIRE', max(300, (int)pc_env('ACCESS_TOKEN_EXPIRE', '3600')));
define('REFRESH_TOKEN_EXPIRE', max(3600, (int)pc_env('REFRESH_TOKEN_EXPIRE', '604800')));
define('NOMINATIM_BASE_URL', pc_env('NOMINATIM_BASE_URL', 'https://nominatim.openstreetmap.org'));
define('NOMINATIM_USER_AGENT', pc_env('NOMINATIM_USER_AGENT', 'PhotoConnect/2.0 (' . PUBLIC_BASE_URL . ')'));
define('NOMINATIM_EMAIL', pc_env('NOMINATIM_EMAIL', ''));
define('ADMIN_API_KEY', pc_env('ADMIN_API_KEY', ''));
define('API_TIMING_LOG_ENABLED', pc_env('API_TIMING_LOG_ENABLED', '0') === '1');
define('API_TIMING_LOG_THRESHOLD_MS', max(0, (int)pc_env('API_TIMING_LOG_THRESHOLD_MS', '150')));
define('API_RESPONSE_GZIP_ENABLED', pc_env('API_RESPONSE_GZIP_ENABLED', '1') !== '0');
define('API_INDEX_BOOTSTRAP_ENABLED', pc_env('API_INDEX_BOOTSTRAP_ENABLED', '0') === '1');
define('API_INDEX_BOOTSTRAP_TTL_SECONDS', max(60, (int)pc_env('API_INDEX_BOOTSTRAP_TTL_SECONDS', '86400')));

ini_set('display_errors', APP_DEBUG ? '1' : '0');
ini_set('log_errors', '1');

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: ' . pc_env('CORS_ALLOW_ORIGIN', '*'));
header('Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Authorization, X-Admin-Token');
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
header('Pragma: no-cache');
header('Expires: 0');
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Referrer-Policy: no-referrer');
header('Permissions-Policy: geolocation=(), microphone=(), camera=()');
if (
    (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
    || strtolower((string)($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '')) === 'https'
) {
    header('Strict-Transport-Security: max-age=31536000; includeSubDomains');
}
if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'OPTIONS') {
    http_response_code(204);
    exit;
}

pc_bootstrap_performance();
pc_register_fatal_handler();

function pc_string_contains(string $haystack, string $needle): bool
{
    if ($needle === '') {
        return true;
    }
    return strpos($haystack, $needle) !== false;
}

function pc_string_starts_with(string $haystack, string $needle): bool
{
    if ($needle === '') {
        return true;
    }
    return substr($haystack, 0, strlen($needle)) === $needle;
}

function pc_string_ends_with(string $haystack, string $needle): bool
{
    if ($needle === '') {
        return true;
    }
    if (strlen($needle) > strlen($haystack)) {
        return false;
    }
    return substr($haystack, -strlen($needle)) === $needle;
}

function pc_load_env_files(array $paths): void
{
    foreach ($paths as $path) {
        if (!is_string($path) || $path === '' || !is_file($path)) {
            continue;
        }

        $lines = @file($path, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
        if (!is_array($lines)) {
            continue;
        }

        foreach ($lines as $line) {
            $line = trim($line);
            if ($line === '' || $line[0] === '#' || !pc_string_contains($line, '=')) {
                continue;
            }

            [$key, $value] = array_map('trim', explode('=', $line, 2));
            if ($key === '') {
                continue;
            }
            if (
                (pc_string_starts_with($value, '"') && pc_string_ends_with($value, '"')) ||
                (pc_string_starts_with($value, "'") && pc_string_ends_with($value, "'"))
            ) {
                $value = substr($value, 1, -1);
            }

            if (getenv($key) === false) {
                putenv($key . '=' . $value);
                $_ENV[$key] = $value;
                $_SERVER[$key] = $value;
            }
        }
    }
}

function pc_env(string $key, string $default = ''): string
{
    $value = $_ENV[$key] ?? $_SERVER[$key] ?? getenv($key);
    if ($value === false || $value === null || $value === '') {
        return $default;
    }
    return (string)$value;
}

/**
 * Parses sign-in identity: validated email or strict 10-digit phone.
 *
 * @return array{0: ?string, 1: ?string} [$emailOrNull, $phone10OrNull]
 */
function parse_login_identity(string $identity): array
{
    $identity = trim($identity);
    if ($identity === '') {
        return [null, null];
    }

    $emailValid = filter_var($identity, FILTER_VALIDATE_EMAIL);
    if ($emailValid !== false && is_string($emailValid) && $emailValid !== '') {
        return [$emailValid, null];
    }

    $digits = preg_replace('/\D/', '', $identity);
    if ($digits === '') {
        return [null, null];
    }
    return [null, strlen($digits) === 10 ? $digits : null];
}

function normalize_phone_digits_string(string $digitsOnly): string
{
    return preg_replace('/\D/', '', $digitsOnly);
}

function respond(bool $ok, string $msg, array $data = [], int $code = 200): void
{
    if (!$ok && $code >= 500 && !APP_DEBUG) {
        pc_log_runtime_error('Sanitized API error: ' . $msg);
        $msg = 'Internal server error';
    }
    http_response_code($code);
    echo json_encode(
        ['success' => $ok, 'message' => $msg, 'data' => $data],
        JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES
    );
    exit;
}

function pc_respond_then_flush_notifications(PDO $db, string $msg, array $data = [], int $code = 200): void
{
    if (function_exists('fastcgi_finish_request')) {
        http_response_code($code);
        echo json_encode(
            ['success' => true, 'message' => $msg, 'data' => $data],
            JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES
        );
        fastcgi_finish_request();
        pc_flush_deferred_notification_deliveries($db);
        exit;
    }

    pc_flush_deferred_notification_deliveries($db);
    respond(true, $msg, $data, $code);
}

function pc_runtime_log_path(): string
{
    return __DIR__ . DIRECTORY_SEPARATOR . 'runtime_error.log';
}

function pc_api_timing_log_path(): string
{
    return __DIR__ . DIRECTORY_SEPARATOR . 'api_timing.log';
}

function pc_log_runtime_error(string $message): void
{
    $line = '[' . date('Y-m-d H:i:s') . '] ' . trim($message) . PHP_EOL;
    @file_put_contents(pc_runtime_log_path(), $line, FILE_APPEND);
}

function pc_bootstrap_performance(): void
{
    static $bootstrapped = false;
    if ($bootstrapped) {
        return;
    }
    $bootstrapped = true;

    if (
        API_RESPONSE_GZIP_ENABLED
        && !headers_sent()
        && extension_loaded('zlib')
        && !ini_get('zlib.output_compression')
        && stripos((string)($_SERVER['HTTP_ACCEPT_ENCODING'] ?? ''), 'gzip') !== false
        && !pc_string_ends_with((string)($_SERVER['SCRIPT_NAME'] ?? ''), 'serveImage.php')
    ) {
        @ob_start('ob_gzhandler');
    }

    if (API_TIMING_LOG_ENABLED) {
        $start = microtime(true);
        register_shutdown_function(static function () use ($start): void {
            $elapsedMs = (int)round((microtime(true) - $start) * 1000);
            if ($elapsedMs < API_TIMING_LOG_THRESHOLD_MS) {
                return;
            }
            $status = function_exists('http_response_code') ? (int)http_response_code() : 0;
            $script = basename((string)($_SERVER['SCRIPT_NAME'] ?? 'unknown'));
            $method = (string)($_SERVER['REQUEST_METHOD'] ?? 'GET');
            $query = (string)($_SERVER['QUERY_STRING'] ?? '');
            $line = sprintf(
                "[%s] %s %s%s status=%d total_ms=%d memory_kb=%d\n",
                date('Y-m-d H:i:s'),
                $method,
                $script,
                $query !== '' ? '?' . $query : '',
                $status,
                $elapsedMs,
                (int)round(memory_get_peak_usage(true) / 1024)
            );
            @file_put_contents(pc_api_timing_log_path(), $line, FILE_APPEND);
        });
    }
}

function pc_register_fatal_handler(): void
{
    static $registered = false;
    if ($registered) {
        return;
    }
    $registered = true;

    register_shutdown_function(function (): void {
        $error = error_get_last();
        if (!$error || !in_array($error['type'], [E_ERROR, E_PARSE, E_CORE_ERROR, E_COMPILE_ERROR, E_USER_ERROR], true)) {
            return;
        }

        $message = sprintf(
            '%s in %s on line %d',
            (string)($error['message'] ?? 'Fatal error'),
            (string)($error['file'] ?? 'unknown file'),
            (int)($error['line'] ?? 0)
        );
        pc_log_runtime_error($message);

        if (!headers_sent()) {
            http_response_code(500);
            header('Content-Type: application/json; charset=utf-8');
            echo json_encode(
                [
                    'success' => false,
                    'message' => $message,
                    'data' => [],
                ],
                JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES
            );
        }
    });
}

function getDB(): PDO
{
    static $pdo = null;
    if ($pdo === null) {
        $dsn = 'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4';
        $pdo = new PDO($dsn, DB_USER, DB_PASS, [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
            PDO::ATTR_PERSISTENT => DB_PERSISTENT_CONNECTIONS,
        ]);
        if (API_INDEX_BOOTSTRAP_ENABLED) {
            pc_maybe_bootstrap_performance_indexes($pdo);
        }
    }
    return $pdo;
}



function tableHasColumn(PDO $db, string $tableName, string $columnName, bool $refresh = false): bool
{
    static $cache = [];
    $key = DB_NAME . ':' . $tableName . ':' . $columnName;
    if (!$refresh && array_key_exists($key, $cache)) {
        return $cache[$key];
    }

    try {
        $stmt = $db->prepare(
            'SELECT COUNT(*)
             FROM information_schema.columns
             WHERE table_schema = ? AND table_name = ? AND column_name = ?'
        );
        $stmt->execute([DB_NAME, $tableName, $columnName]);
        $cache[$key] = ((int)$stmt->fetchColumn()) > 0;
    } catch (Throwable $e) {
        $cache[$key] = false;
    }
    return $cache[$key];
}

function tableExists(PDO $db, string $tableName, bool $refresh = false): bool
{
    static $cache = [];
    $key = DB_NAME . ':' . $tableName;
    if (!$refresh && array_key_exists($key, $cache)) {
        return $cache[$key];
    }

    try {
        $stmt = $db->prepare(
            'SELECT COUNT(*)
             FROM information_schema.tables
             WHERE table_schema = ? AND table_name = ?'
        );
        $stmt->execute([DB_NAME, $tableName]);
        $cache[$key] = ((int)$stmt->fetchColumn()) > 0;
    } catch (Throwable $e) {
        $cache[$key] = false;
    }
    return $cache[$key];
}

function pc_config_index_exists(PDO $db, string $tableName, string $indexName): bool
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
        return true;
    }
}

function pc_maybe_bootstrap_performance_indexes(PDO $db): void
{
    static $done = false;
    if ($done) {
        return;
    }
    $done = true;

    $marker = __DIR__ . DIRECTORY_SEPARATOR . '.performance_indexes_checked';
    $last = is_file($marker) ? (int)@filemtime($marker) : 0;
    if ($last > 0 && (time() - $last) < API_INDEX_BOOTSTRAP_TTL_SECONDS) {
        return;
    }

    try {
        $indexes = [
            ['bookings', 'idx_booking_taker_date_status_part', ['taker_id', 'booking_date', 'status', 'day_part']],
            ['bookings', 'idx_bookings_client_date_status', ['client_id', 'booking_date', 'status']],
            ['events', 'idx_events_actor_date', ['created_by_role', 'created_by_id', 'event_date']],
            ['events', 'idx_events_client_date', ['client_id', 'event_date']],
            ['events', 'idx_events_taker_date', ['taker_id', 'event_date']],
            ['events', 'idx_events_status_date', ['status', 'event_date']],
            ['availability', 'idx_availability_taker_date_part_status', ['taker_id', 'date', 'day_part', 'status']],
            ['availability', 'idx_availability_date_status_taker', ['date', 'status', 'taker_id']],
            ['taker_favorites', 'idx_tf_actor_taker', ['actor_role', 'actor_id', 'taker_id']],
            ['taker_favorites', 'idx_tf_taker_actor', ['taker_id', 'actor_role', 'actor_id']],
            ['reviews', 'idx_reviews_taker_created', ['taker_id', 'created_at']],
            ['studio_reviews', 'idx_studio_reviews_taker_created', ['taker_id', 'created_at']],
            ['notifications', 'idx_notifications_recipient_fast', ['recipient_role', 'recipient_id', 'is_read', 'created_at']],
            ['notification_delivery_queue', 'idx_ndq_ready_fast', ['delivered_at', 'locked_at', 'attempts', 'created_at']],
            ['search_events', 'idx_search_events_actor_type_created', ['actor_role', 'actor_id', 'event_type', 'created_at']],
            ['taker_posts', 'idx_taker_posts_taker_status_created', ['taker_id', 'status', 'created_at']],
            ['taker_post_images', 'idx_post_images_post_sort', ['post_id', 'sort_order']],
        ];

        foreach ($indexes as [$table, $name, $columns]) {
            pc_create_index_if_possible($db, $table, $name, $columns);
        }
        @touch($marker);
    } catch (Throwable $e) {
        pc_log_runtime_error('Performance index bootstrap skipped: ' . $e->getMessage());
        @touch($marker);
    }
}

function pc_create_index_if_possible(PDO $db, string $tableName, string $indexName, array $columns): void
{
    if (!tableExists($db, $tableName) || pc_config_index_exists($db, $tableName, $indexName)) {
        return;
    }
    foreach ($columns as $column) {
        if (!tableHasColumn($db, $tableName, (string)$column)) {
            return;
        }
    }
    $quotedColumns = implode(', ', array_map(static fn($column) => '`' . str_replace('`', '``', (string)$column) . '`', $columns));
    $sql = 'ALTER TABLE `' . str_replace('`', '``', $tableName) . '` ADD INDEX `' .
        str_replace('`', '``', $indexName) . '` (' . $quotedColumns . ')';
    try {
        $db->exec($sql);
    } catch (Throwable $e) {
        pc_log_runtime_error("Could not create index {$tableName}.{$indexName}: " . $e->getMessage());
    }
}

function validServiceTypes(): array
{
    return [
        'candid_photography',
        'candid_videography',
        'traditional_photography',
        'traditional_videography',
        'wedding_photography',
        'pre_wedding',
        'engagement_photography',
        'birthday_photography',
        'event_photography',
        'corporate_photography',
        'baby_shoot',
        'maternity_shoot',
        'album_design',
        'photo_editing',
        'live_streaming',
        'drone',
        'led_wall',
        'other',
    ];
}

function isAllowedServiceType(string $service): bool
{
    if (in_array($service, validServiceTypes(), true)) {
        return true;
    }
    return (bool)preg_match('/^[a-z0-9_]{2,64}$/', $service);
}

function firstExistingColumn(PDO $db, string $tableName, array $candidates): ?string
{
    foreach ($candidates as $candidate) {
        if (tableHasColumn($db, $tableName, $candidate)) {
            return $candidate;
        }
    }
    return null;
}

function parseServiceTypes(array $body): array
{
    $raw = $body['service_types'] ?? null;
    if (!is_array($raw)) {
        $single = trim((string)($body['service_type'] ?? ''));
        $raw = $single !== '' ? [$single] : [];
    }

    $valid = validServiceTypes();
    $services = [];
    foreach ($raw as $service) {
        $service = trim((string)$service);
        if (!in_array($service, $valid, true) && !isAllowedServiceType($service)) {
            respond(false, 'Invalid service type: ' . $service, [], 422);
        }
        if (!in_array($service, $services, true)) {
            $services[] = $service;
        }
    }

    if (empty($services)) {
        respond(false, 'Select at least one service type', [], 422);
    }
    return $services;
}

function replaceTakerServiceTypes(PDO $db, int $takerId, array $services): void
{
    if (!tableExists($db, 'taker_service_types')) {
        return;
    }

    $delete = $db->prepare('DELETE FROM taker_service_types WHERE taker_id=?');
    $delete->execute([$takerId]);

    $insert = $db->prepare('INSERT INTO taker_service_types(taker_id, service_type) VALUES(?, ?)');
    foreach ($services as $service) {
        $insert->execute([$takerId, $service]);
    }
}

function hydrateServiceTypes(PDO $db, array $rows): array
{
    if (empty($rows)) {
        return $rows;
    }

    if (!tableExists($db, 'taker_service_types')) {
        foreach ($rows as &$row) {
            $legacy = isset($row['service_type']) ? trim((string)$row['service_type']) : '';
            $row['service_types'] = $legacy !== '' ? [$legacy] : [];
        }
        unset($row);
        return $rows;
    }

    $ids = [];
    foreach ($rows as $row) {
        $ids[] = (int)$row['id'];
    }
    $ids = array_values(array_unique($ids));
    $placeholders = implode(',', array_fill(0, count($ids), '?'));
    $stmt = $db->prepare(
        "SELECT taker_id, service_type
         FROM taker_service_types
         WHERE taker_id IN ($placeholders)
         ORDER BY service_type ASC"
    );
    $stmt->execute($ids);

    $serviceMap = [];
    foreach ($stmt->fetchAll() as $row) {
        $serviceMap[(int)$row['taker_id']][] = $row['service_type'];
    }

    foreach ($rows as &$row) {
        $legacy = $row['service_type'] ?? null;
        $types = $serviceMap[(int)$row['id']] ?? [];
        if (empty($types) && $legacy) {
            $types = [$legacy];
        }
        $row['service_types'] = array_values(array_unique($types));
    }
    unset($row);

    return $rows;
}

function pc_starts_with(string $haystack, string $needle): bool
{
    if ($needle === '') {
        return true;
    }
    return pc_string_starts_with($haystack, $needle);
}



function getBaseUrl(): string
{
    if (defined('PUBLIC_BASE_URL') && PUBLIC_BASE_URL !== '') {
        return rtrim(PUBLIC_BASE_URL, '/');
    }

    $forwardedProto = strtolower((string)($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? ''));
    $forwardedSsl = strtolower((string)($_SERVER['HTTP_X_FORWARDED_SSL'] ?? ''));
    $requestScheme = strtolower((string)($_SERVER['REQUEST_SCHEME'] ?? ''));
    $httpsFlag = strtolower((string)($_SERVER['HTTPS'] ?? ''));
    $proto = (
        $forwardedProto === 'https' ||
        $forwardedSsl === 'on' ||
        $requestScheme === 'https' ||
        ($httpsFlag !== '' && $httpsFlag !== 'off')
    ) ? 'https' : 'http';
    $host = $_SERVER['HTTP_HOST'] ?? 'localhost';
    $script = $_SERVER['SCRIPT_FILENAME'] ?? __FILE__;
    $docRoot = realpath($_SERVER['DOCUMENT_ROOT'] ?? dirname(__DIR__));
    $baseDir = realpath(__DIR__ . '/..');
    $subPath = str_replace(str_replace('\\', '/', $docRoot), '', str_replace('\\', '/', $baseDir));
    return $proto . '://' . $host . $subPath;
}

function getProjectRootPath(): string
{
    return realpath(__DIR__ . '/..') ?: dirname(__DIR__);
}

function managedImagePathPattern(): string
{
    return '#^(PhotoConnectImages/photos/original/(?:taker_posts|profile_images|portfolio_samples)/|PhotoConnectImages/photos/cache/(?:taker_posts|profile_images|portfolio_samples)/)#';
}

function isManagedImageRelativePath(string $relativePath): bool
{
    $relativePath = ltrim(trim($relativePath), '/');
    return $relativePath !== '' && preg_match(managedImagePathPattern(), $relativePath) === 1;
}

function extractManagedImageRelativePath(string $value): ?string
{
    $value = trim($value);
    if ($value === '' || preg_match('/\.\./', $value)) {
        return null;
    }

    $directPath = ltrim(str_replace('\\', '/', $value), '/');
    if (isManagedImageRelativePath($directPath)) {
        return $directPath;
    }

    $query = parse_url($value, PHP_URL_QUERY);
    if (is_string($query) && $query !== '') {
        parse_str($query, $params);
        $pathFromQuery = ltrim((string)($params['path'] ?? ''), '/');
        if (isManagedImageRelativePath($pathFromQuery)) {
            return $pathFromQuery;
        }
    }

    $parsedPath = parse_url($value, PHP_URL_PATH);
    if (!is_string($parsedPath) || $parsedPath === '') {
        return null;
    }

    $normalizedPath = ltrim(str_replace('\\', '/', $parsedPath), '/');
    if (preg_match('#(?:^|/)((?:PhotoConnectImages/photos/original/(?:taker_posts|profile_images|portfolio_samples)/|PhotoConnectImages/photos/cache/(?:taker_posts|profile_images|portfolio_samples)/).*)$#', $normalizedPath, $matches) === 1) {
        $candidate = $matches[1];
        return isManagedImageRelativePath($candidate) ? $candidate : null;
    }

    return null;
}

function getRelativeProjectPathFromUrl(string $url): ?string
{
    $url = trim($url);
    if ($url === '') {
        return null;
    }

    $managedRelativePath = extractManagedImageRelativePath($url);
    if ($managedRelativePath !== null) {
        return $managedRelativePath;
    }

    $baseUrl = rtrim(getBaseUrl(), '/');
    if (!pc_starts_with($url, $baseUrl . '/')) {
        return null;
    }

    $relativePath = ltrim(substr($url, strlen($baseUrl)), '/');
    if ($relativePath === '') {
        return null;
    }

    return rawurldecode(strtok($relativePath, '?') ?: $relativePath);
}

function projectFilePathFromUrl(string $url): ?string
{
    $relativePath = getRelativeProjectPathFromUrl($url);
    if ($relativePath === null || $relativePath === '') {
        return null;
    }

    $fullPath = getProjectRootPath() . DIRECTORY_SEPARATOR . str_replace('/', DIRECTORY_SEPARATOR, $relativePath);
    $realParent = realpath(dirname($fullPath));
    $projectRoot = getProjectRootPath();

    if ($realParent === false || !pc_starts_with($realParent, $projectRoot)) {
        return null;
    }

    return $fullPath;
}

function buildServedImageUrl(string $relativePath, string $quality = 'full'): string
{
    $relativePath = ltrim($relativePath, '/');
    $quality = in_array($quality, ['thumb', 'medium', 'full'], true) ? $quality : 'full';
    return rtrim(getBaseUrl(), '/') . '/serveImage.php?path=' . rawurlencode($relativePath) . '&q=' . rawurlencode($quality);
}

function normalizeDeliveredImageUrl(?string $url, string $quality = 'full'): ?string
{
    $url = trim((string)$url);
    if ($url === '') {
        return null;
    }

    $relativePath = extractManagedImageRelativePath($url);
    if ($relativePath !== null) {
        return buildServedImageUrl($relativePath, $quality);
    }

    return normalizePublicUrl($url);
}

function normalizePublicUrl(?string $url): ?string
{
    $url = trim((string)$url);
    if ($url === '') {
        return null;
    }

    $currentBase = rtrim(getBaseUrl(), '/');
    $currentHost = parse_url($currentBase, PHP_URL_HOST);
    $currentScheme = parse_url($currentBase, PHP_URL_SCHEME) ?: 'https';
    $parsedHost = parse_url($url, PHP_URL_HOST);
    $parsedScheme = parse_url($url, PHP_URL_SCHEME);

    if ($currentHost && $parsedHost && strcasecmp($currentHost, $parsedHost) === 0 && $parsedScheme && strtolower($parsedScheme) !== strtolower($currentScheme)) {
        $url = preg_replace('#^https?://#i', $currentScheme . '://', $url) ?: $url;
    }

    return $url;
}

function getRequestHeadersNormalized(): array
{
    static $headers = null;
    if ($headers !== null) {
        return $headers;
    }

    $raw = function_exists('getallheaders') ? getallheaders() : [];
    if (!is_array($raw) && function_exists('apache_request_headers')) {
        $raw = apache_request_headers();
    }
    if (!is_array($raw)) {
        $raw = [];
    }

    $headers = [];
    foreach ($raw as $key => $value) {
        $headers[strtolower((string)$key)] = (string)$value;
    }
    if (isset($_SERVER['HTTP_AUTHORIZATION']) && !isset($headers['authorization'])) {
        $headers['authorization'] = (string)$_SERVER['HTTP_AUTHORIZATION'];
    }
    return $headers;
}

function getBearerToken(): ?string
{
    $headers = getRequestHeadersNormalized();
    $authHeader = trim((string)($headers['authorization'] ?? ''));
    if ($authHeader === '' || !preg_match('/^Bearer\s+(.+)$/i', $authHeader, $matches)) {
        return null;
    }
    return trim($matches[1]);
}

function pc_base64url_encode(string $data): string
{
    return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
}

function pc_base64url_decode(string $data)
{
    $padding = strlen($data) % 4;
    if ($padding > 0) {
        $data .= str_repeat('=', 4 - $padding);
    }
    return base64_decode(strtr($data, '-_', '+/'), true);
}

function pc_jwt_encode(array $payload): string
{
    $header = ['alg' => 'HS256', 'typ' => 'JWT'];
    $segments = [
        pc_base64url_encode(json_encode($header, JSON_UNESCAPED_SLASHES)),
        pc_base64url_encode(json_encode($payload, JSON_UNESCAPED_SLASHES)),
    ];
    $signingInput = implode('.', $segments);
    $signature = hash_hmac('sha256', $signingInput, JWT_SECRET, true);
    $segments[] = pc_base64url_encode($signature);
    return implode('.', $segments);
}

function pc_jwt_decode(string $token): array
{
    $parts = explode('.', $token);
    if (count($parts) !== 3) {
        throw new RuntimeException('Malformed token');
    }

    [$encodedHeader, $encodedPayload, $encodedSignature] = $parts;
    $signingInput = $encodedHeader . '.' . $encodedPayload;
    $expected = pc_base64url_encode(hash_hmac('sha256', $signingInput, JWT_SECRET, true));
    if (!hash_equals($expected, $encodedSignature)) {
        throw new RuntimeException('Invalid token signature');
    }

    $payloadJson = pc_base64url_decode($encodedPayload);
    if ($payloadJson === false) {
        throw new RuntimeException('Invalid token payload');
    }

    $payload = json_decode($payloadJson, true);
    if (!is_array($payload)) {
        throw new RuntimeException('Invalid token payload');
    }
    return $payload;
}

function createAccessToken(int $userId, string $role): string
{
    $now = time();
    $tokenVersion = 0;
    try {
        $tokenVersion = pc_current_token_version(getDB(), $userId);
    } catch (Throwable $e) {
        $tokenVersion = 0;
    }
    return pc_jwt_encode([
        'iss' => 'photoconnect',
        'sub' => $userId,
        'role' => $role,
        'tv' => $tokenVersion,
        'iat' => $now,
        'exp' => $now + ACCESS_TOKEN_EXPIRE,
    ]);
}

function pc_current_token_version(PDO $db, int $userId): int
{
    if ($userId <= 0 || !tableHasColumn($db, 'users', 'token_version')) {
        return 0;
    }
    $stmt = $db->prepare('SELECT token_version FROM users WHERE id=? LIMIT 1');
    $stmt->execute([$userId]);
    $value = $stmt->fetchColumn();
    return $value === false ? -1 : max(0, (int)$value);
}

function pc_revoke_user_sessions(PDO $db, int $userId): void
{
    if ($userId <= 0) {
        return;
    }
    $setParts = ['refresh_token_hash=NULL', 'refresh_token_expires_at=NULL'];
    if (tableHasColumn($db, 'users', 'token_version')) {
        $setParts[] = 'token_version=token_version+1';
    }
    $stmt = $db->prepare('UPDATE users SET ' . implode(', ', $setParts) . ' WHERE id=?');
    $stmt->execute([$userId]);
}

function pc_auth_token_version_is_current(array $payload, int $userId): bool
{
    try {
        $db = getDB();
        if (!tableHasColumn($db, 'users', 'token_version')) {
            return true;
        }
        $current = pc_current_token_version($db, $userId);
        if ($current < 0) {
            return false;
        }
        return (int)($payload['tv'] ?? -1) === $current;
    } catch (Throwable $e) {
        pc_log_runtime_error('Token version check failed: ' . $e->getMessage());
        return false;
    }
}

function pc_is_user_blocked(PDO $db, int $userId): bool
{
    if ($userId <= 0 || !tableExists($db, 'admin_user_blocks')) {
        return false;
    }
    try {
        $stmt = $db->prepare('SELECT 1 FROM admin_user_blocks WHERE user_id=? LIMIT 1');
        $stmt->execute([$userId]);
        return (bool)$stmt->fetchColumn();
    } catch (Throwable $e) {
        pc_log_runtime_error('User block check failed: ' . $e->getMessage());
        return false;
    }
}

function pc_verification_identity(string $type, string $identity): string
{
    if ($type === 'email') {
        return pc_normalize_email_for_otp($identity);
    }
    if ($type === 'phone') {
        return normalize_phone_digits_string($identity);
    }
    return trim($identity);
}

function pc_create_verification_token(string $type, string $identity, ?int $ttlSeconds = null): string
{
    $now = time();
    $ttl = $ttlSeconds ?? VERIFICATION_TOKEN_EXPIRE;
    return pc_jwt_encode([
        'iss' => 'photoconnect',
        'purpose' => 'registration_verification',
        'type' => $type,
        'identity' => pc_verification_identity($type, $identity),
        'iat' => $now,
        'exp' => $now + $ttl,
    ]);
}

function pc_verify_verification_token(string $token, string $type, string $identity): bool
{
    $token = trim($token);
    if ($token === '') {
        return false;
    }

    try {
        $payload = pc_jwt_decode($token);
    } catch (Throwable $e) {
        return false;
    }

    return ($payload['iss'] ?? '') === 'photoconnect'
        && ($payload['purpose'] ?? '') === 'registration_verification'
        && ($payload['type'] ?? '') === $type
        && hash_equals((string)($payload['identity'] ?? ''), pc_verification_identity($type, $identity))
        && (int)($payload['exp'] ?? 0) >= time();
}

function pc_generate_stateless_otp(string $phone, ?int $timestamp = null): string
{
    $digits = preg_replace('/\D/', '', $phone);
    $bucket = intdiv($timestamp ?? time(), OTP_WINDOW_SECONDS);
    $hash = hash_hmac('sha256', 'otp|' . $digits . '|' . $bucket, JWT_SECRET);
    $number = hexdec(substr($hash, 0, 8)) % 1000000;
    return str_pad((string)$number, 6, '0', STR_PAD_LEFT);
}

function pc_verify_stateless_otp(string $phone, string $otp): bool
{
    $otp = trim($otp);
    if (!preg_match('/^\d{6}$/', $otp)) {
        return false;
    }

    $now = time();
    foreach ([0, -OTP_WINDOW_SECONDS] as $offset) {
        if (hash_equals(pc_generate_stateless_otp($phone, $now + $offset), $otp)) {
            return true;
        }
    }
    return false;
}

function pc_normalize_email_for_otp(string $email): string
{
    return strtolower(trim($email));
}

function pc_generate_email_otp(string $email, ?int $timestamp = null): string
{
    $normalized = pc_normalize_email_for_otp($email);
    $bucket = intdiv($timestamp ?? time(), OTP_WINDOW_SECONDS);
    $hash = hash_hmac('sha256', 'email-otp|' . $normalized . '|' . $bucket, JWT_SECRET);
    $number = hexdec(substr($hash, 0, 8)) % 1000000;
    return str_pad((string)$number, 6, '0', STR_PAD_LEFT);
}

function pc_verify_email_otp(string $email, string $otp): bool
{
    $otp = trim($otp);
    if (!preg_match('/^\d{6}$/', $otp)) {
        return false;
    }

    $now = time();
    foreach ([0, -OTP_WINDOW_SECONDS] as $offset) {
        if (hash_equals(pc_generate_email_otp($email, $now + $offset), $otp)) {
            return true;
        }
    }
    return false;
}

function pc_send_email(string $toEmail, string $subject, string $htmlBody, string $textBody = ''): bool
{
    $toEmail = trim($toEmail);
    if (!filter_var($toEmail, FILTER_VALIDATE_EMAIL)) {
        return false;
    }
    $textBody = $textBody !== '' ? $textBody : trim(strip_tags(str_replace(['<br>', '<br/>', '<br />'], "\n", $htmlBody)));

    if (SMTP_PASSWORD !== '' && SMTP_USERNAME !== '') {
        return pc_send_smtp_email($toEmail, $subject, $htmlBody, $textBody);
    }

    $boundary = 'pc_' . bin2hex(random_bytes(12));
    $headers = [
        'From: ' . SMTP_FROM_NAME . ' <' . SMTP_FROM_EMAIL . '>',
        'MIME-Version: 1.0',
        'Content-Type: multipart/alternative; boundary="' . $boundary . '"',
    ];
    $body = "--$boundary\r\n"
        . "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
        . $textBody . "\r\n"
        . "--$boundary\r\n"
        . "Content-Type: text/html; charset=UTF-8\r\n\r\n"
        . $htmlBody . "\r\n"
        . "--$boundary--";

    return @mail($toEmail, $subject, $body, implode("\r\n", $headers));
}

function pc_send_smtp_email(string $toEmail, string $subject, string $htmlBody, string $textBody): bool
{
    $host = SMTP_HOST;
    $port = SMTP_PORT;
    $socket = @stream_socket_client("tcp://{$host}:{$port}", $errno, $errstr, 20);
    if (!$socket) {
        pc_log_runtime_error("SMTP connect failed: {$errno} {$errstr}");
        return false;
    }
    stream_set_timeout($socket, 20);

    $read = function () use ($socket): string {
        $data = '';
        while (($line = fgets($socket, 515)) !== false) {
            $data .= $line;
            if (strlen($line) < 4 || $line[3] !== '-') {
                break;
            }
        }
        return $data;
    };
    $write = function (string $command) use ($socket): void {
        fwrite($socket, $command . "\r\n");
    };
    $expect = function (array $codes) use ($read): bool {
        $response = $read();
        foreach ($codes as $code) {
            if (strncmp($response, (string)$code, 3) === 0) {
                return true;
            }
        }
        if (strncmp($response, '535', 3) === 0) {
            pc_log_runtime_error('SMTP authentication failed. Gmail SMTP needs a 16-character App Password in SMTP_PASSWORD, not the normal account password.');
        }
        pc_log_runtime_error('SMTP unexpected response: ' . trim($response));
        return false;
    };

    if (!$expect([220])) { fclose($socket); return false; }
    $write('EHLO ' . ($_SERVER['SERVER_NAME'] ?? 'localhost'));
    if (!$expect([250])) { fclose($socket); return false; }
    $write('STARTTLS');
    if (!$expect([220])) { fclose($socket); return false; }
    if (!stream_socket_enable_crypto($socket, true, STREAM_CRYPTO_METHOD_TLS_CLIENT)) {
        pc_log_runtime_error('SMTP TLS enable failed');
        fclose($socket);
        return false;
    }
    $write('EHLO ' . ($_SERVER['SERVER_NAME'] ?? 'localhost'));
    if (!$expect([250])) { fclose($socket); return false; }
    $write('AUTH LOGIN');
    if (!$expect([334])) { fclose($socket); return false; }
    $write(base64_encode(SMTP_USERNAME));
    if (!$expect([334])) { fclose($socket); return false; }
    $write(base64_encode(SMTP_PASSWORD));
    if (!$expect([235])) { fclose($socket); return false; }

    $from = SMTP_FROM_EMAIL;
    $boundary = 'pc_' . bin2hex(random_bytes(12));
    $headers = [
        'From: ' . SMTP_FROM_NAME . ' <' . $from . '>',
        'To: <' . $toEmail . '>',
        'Subject: ' . $subject,
        'MIME-Version: 1.0',
        'Content-Type: multipart/alternative; boundary="' . $boundary . '"',
    ];
    $message = implode("\r\n", $headers)
        . "\r\n\r\n--$boundary\r\n"
        . "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
        . $textBody . "\r\n"
        . "--$boundary\r\n"
        . "Content-Type: text/html; charset=UTF-8\r\n\r\n"
        . $htmlBody . "\r\n"
        . "--$boundary--\r\n.";

    $write('MAIL FROM:<' . $from . '>');
    if (!$expect([250])) { fclose($socket); return false; }
    $write('RCPT TO:<' . $toEmail . '>');
    if (!$expect([250, 251])) { fclose($socket); return false; }
    $write('DATA');
    if (!$expect([354])) { fclose($socket); return false; }
    fwrite($socket, $message . "\r\n");
    if (!$expect([250])) { fclose($socket); return false; }
    $write('QUIT');
    fclose($socket);
    return true;
}

function requireAuthenticatedUser(bool $require = true): ?array
{
    $token = getBearerToken();
    if ($token === null) {
        if ($require) {
            respond(false, 'Missing authorization token', [], 401);
        }
        return null;
    }

    try {
        $payload = pc_jwt_decode($token);
    } catch (Throwable $e) {
        if ($require) {
            respond(false, 'Invalid authorization token', [], 401);
        }
        return null;
    }

    $role = trim((string)($payload['role'] ?? ''));
    $userId = (int)($payload['sub'] ?? 0);
    $exp = (int)($payload['exp'] ?? 0);

    if (!in_array($role, ['client', 'taker', 'auto', 'user'], true) || $userId <= 0) {
        if ($require) {
            respond(false, 'Invalid authorization token', [], 401);
        }
        return null;
    }
    if ($exp <= time()) {
        if ($require) {
            respond(false, 'Authorization token expired', [], 401);
        }
        return null;
    }
    if (!pc_auth_token_version_is_current($payload, $userId)) {
        if ($require) {
            respond(false, 'Session expired. Please log in again.', [], 401);
        }
        return null;
    }
    try {
        if (pc_is_user_blocked(getDB(), $userId)) {
            if ($require) {
                respond(false, 'This account has been blocked. Please contact support.', [], 403);
            }
            return null;
        }
    } catch (Throwable $e) {
        if ($require) {
            respond(false, 'Could not verify account status', [], 500);
        }
        return null;
    }

    return [
        'role' => $role,
        'user_id' => $userId,
        'exp' => $exp,
    ];
}

function requireAdminRequest(): void
{
    $configured = trim((string)ADMIN_API_KEY);
    if ($configured === '' || pc_is_weak_admin_key($configured)) {
        respond(false, 'Admin verification is not configured', [], 503);
    }

    $provided = trim((string)($_SERVER['HTTP_X_ADMIN_TOKEN'] ?? ''));
    if ($provided === '') {
        $bearer = getBearerToken();
        $provided = $bearer !== null ? trim($bearer) : '';
    }

    if ($provided === '' || !hash_equals($configured, $provided)) {
        respond(false, 'Admin authorization required', [], 401);
    }
    pc_audit_log('admin_access', null, 'admin', 'script', basename((string)($_SERVER['SCRIPT_NAME'] ?? 'unknown')));
}

function pc_is_weak_admin_key(string $key): bool
{
    $normalized = strtolower(trim($key));
    return strlen($key) < 24 || in_array($normalized, [
        'admin',
        'password',
        'changeme',
        'change-me',
        'secret',
        'test',
        '123456',
    ], true);
}

function getOptionalAuthenticatedUser(): ?array
{
    $token = getBearerToken();
    if ($token === null) {
        return null;
    }

    try {
        $payload = pc_jwt_decode($token);
    } catch (Throwable $e) {
        return null;
    }

    $role = trim((string)($payload['role'] ?? ''));
    $userId = (int)($payload['sub'] ?? 0);
    $exp = (int)($payload['exp'] ?? 0);
    if (!in_array($role, ['client', 'taker'], true) || $userId <= 0 || $exp <= time()) {
        return null;
    }
    if (!pc_auth_token_version_is_current($payload, $userId)) {
        return null;
    }
    try {
        if (pc_is_user_blocked(getDB(), $userId)) {
            return null;
        }
    } catch (Throwable $e) {
        return null;
    }

    return [
        'role' => $role,
        'user_id' => $userId,
        'exp' => $exp,
    ];
}

function issueRefreshToken(): string
{
    return bin2hex(random_bytes(32));
}

function storeRefreshToken(PDO $db, string $role, int $userId, string $refreshToken): void
{
    if (!tableHasColumn($db, 'users', 'refresh_token_hash') || !tableHasColumn($db, 'users', 'refresh_token_expires_at')) {
        return;
    }

    $hash = password_hash($refreshToken, PASSWORD_BCRYPT);
    $expiresAt = date('Y-m-d H:i:s', time() + REFRESH_TOKEN_EXPIRE);
    $stmt = $db->prepare(
        "UPDATE users
         SET refresh_token_hash=?, refresh_token_expires_at=?
         WHERE id=?"
    );
    $stmt->execute([$hash, $expiresAt, $userId]);
}

function verifyRefreshToken(PDO $db, string $role, int $userId, string $refreshToken): bool
{
    if (!tableHasColumn($db, 'users', 'refresh_token_hash') || !tableHasColumn($db, 'users', 'refresh_token_expires_at')) {
        return false;
    }

    $stmt = $db->prepare(
        "SELECT refresh_token_hash, refresh_token_expires_at
         FROM users
         WHERE id=?
         LIMIT 1"
    );
    $stmt->execute([$userId]);
    $row = $stmt->fetch();
    if (!$row) {
        return false;
    }
    if (pc_is_user_blocked($db, $userId)) {
        return false;
    }
    $hash = (string)($row['refresh_token_hash'] ?? '');
    $expiresAt = strtotime((string)($row['refresh_token_expires_at'] ?? '')) ?: 0;
    if ($hash === '' || $expiresAt <= time()) {
        return false;
    }
    return password_verify($refreshToken, $hash);
}

function clearRefreshToken(PDO $db, string $role, int $userId): void
{
    if (!tableHasColumn($db, 'users', 'refresh_token_hash') || !tableHasColumn($db, 'users', 'refresh_token_expires_at')) {
        return;
    }

    $stmt = $db->prepare(
        "UPDATE users
         SET refresh_token_hash=NULL, refresh_token_expires_at=NULL
         WHERE id=?"
    );
    $stmt->execute([$userId]);
}

function currentActionName(): string
{
    return basename((string)($_SERVER['SCRIPT_NAME'] ?? 'unknown'));
}

function clientIpIdentifier(): string
{
    $forwarded = trim((string)($_SERVER['HTTP_X_FORWARDED_FOR'] ?? ''));
    if ($forwarded !== '') {
        $parts = array_map('trim', explode(',', $forwarded));
        if (!empty($parts[0])) {
            return $parts[0];
        }
    }
    return trim((string)($_SERVER['REMOTE_ADDR'] ?? 'unknown'));
}

function rateLimit(string $identifier, string $action, int $maxAttempts, int $decaySeconds): void
{
    if ($identifier === '' || $action === '' || $maxAttempts <= 0 || $decaySeconds <= 0) {
        return;
    }

    $db = getDB();
    $db->beginTransaction();
    try {
        $select = $db->prepare(
            'SELECT attempts, first_attempt
             FROM rate_limits
             WHERE identifier=? AND action=?
             LIMIT 1
             FOR UPDATE'
        );
        $select->execute([$identifier, $action]);
        $row = $select->fetch();

        $now = time();
        if ($row) {
            $firstAttempt = strtotime((string)$row['first_attempt']) ?: $now;
            $elapsed = $now - $firstAttempt;
            if ($elapsed >= $decaySeconds) {
                $reset = $db->prepare(
                    'UPDATE rate_limits
                     SET attempts=1, first_attempt=NOW(), last_attempt=NOW()
                     WHERE identifier=? AND action=?'
                );
                $reset->execute([$identifier, $action]);
                $db->commit();
                return;
            }

            $attempts = (int)$row['attempts'] + 1;
            $update = $db->prepare(
                'UPDATE rate_limits
                 SET attempts=?, last_attempt=NOW()
                 WHERE identifier=? AND action=?'
            );
            $update->execute([$attempts, $identifier, $action]);
            $db->commit();

            if ($attempts > $maxAttempts) {
                respond(false, 'Too many requests, please try again later', [], 429);
            }
            return;
        }

        $insert = $db->prepare(
            'INSERT INTO rate_limits(identifier, action, attempts, first_attempt, last_attempt)
             VALUES(?, ?, 1, NOW(), NOW())'
        );
        $insert->execute([$identifier, $action]);
        $db->commit();
    } catch (Throwable $e) {
        if ($db->inTransaction()) {
            $db->rollBack();
        }
    }
}

function pc_audit_log(
    string $action,
    ?int $userId = null,
    ?string $actorRole = null,
    ?string $subjectType = null,
    $subjectId = null,
    array $metadata = []
): void {
    try {
        $db = getDB();
        if (!tableExists($db, 'security_audit_logs')) {
            return;
        }
        $stmt = $db->prepare(
            'INSERT INTO security_audit_logs
             (user_id, actor_role, action, subject_type, subject_id, ip_address, user_agent, metadata_json)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
        );
        $stmt->execute([
            $userId,
            $actorRole,
            substr($action, 0, 80),
            $subjectType !== null ? substr($subjectType, 0, 80) : null,
            $subjectId !== null ? substr((string)$subjectId, 0, 80) : null,
            substr(clientIpIdentifier(), 0, 64),
            substr((string)($_SERVER['HTTP_USER_AGENT'] ?? ''), 0, 255),
            !empty($metadata) ? json_encode($metadata, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) : null,
        ]);
    } catch (Throwable $e) {
        pc_log_runtime_error('Audit log failed: ' . $e->getMessage());
    }
}

function pc_sanitize_profile_payload(array $row): array
{
    foreach ([
        'password_hash',
        'refresh_token_hash',
        'refresh_token_expires_at',
        'google_id',
    ] as $key) {
        unset($row[$key]);
    }
    return $row;
}

function pc_sanitize_profile_payloads(array $rows): array
{
    return array_map('pc_sanitize_profile_payload', $rows);
}

function requireActor(PDO $db, array $auth, string $actorRole, int $actorId): void
{
    if (!in_array($actorRole, ['client', 'taker'], true) || $actorId <= 0) {
        respond(false, 'Invalid actor identity', [], 422);
    }

    if ($actorRole === 'client') {
        authorizeClientProfile($db, $auth, $actorId);
        return;
    }

    if ($actorRole === 'taker') {
        authorizeTaker($db, $auth, $actorId);
        return;
    }

    respond(false, 'You are not allowed to act for this account', [], 403);
}

function resolveProfileIdForRole(PDO $db, array $auth, string $role): ?int
{
    $userId = (int)($auth['user_id'] ?? 0);
    if ($userId <= 0 || !in_array($role, ['client', 'taker'], true)) {
        return null;
    }

    $table = $role === 'client' ? 'clients' : 'takers';
    $activeClause = tableHasColumn($db, $table, 'is_active') ? ' AND is_active=1' : '';
    if (tableHasColumn($db, $table, 'user_id')) {
        $stmt = $db->prepare("SELECT id FROM {$table} WHERE user_id=?{$activeClause} LIMIT 1");
        $stmt->execute([$userId]);
        $profileId = $stmt->fetchColumn();
        if ($profileId !== false) {
            return (int)$profileId;
        }
    }

    $stmt = $db->prepare("SELECT id FROM {$table} WHERE id=?{$activeClause} LIMIT 1");
    $stmt->execute([$userId]);
    $profileId = $stmt->fetchColumn();
    return $profileId !== false ? (int)$profileId : null;
}

function authorizeTaker(PDO $db, array $auth, int $takerId): void
{
    if ($takerId <= 0) {
        respond(false, 'Invalid taker account', [], 422);
    }
    
    // Validate if the authenticated unified user_id owns this taker profile
    $userId = (int)$auth['user_id'];
    
    $stmt = $db->prepare('SELECT id FROM takers WHERE id=? AND user_id=?' . (tableHasColumn($db, 'takers', 'is_active') ? ' AND is_active=1' : '') . ' LIMIT 1');
    $stmt->execute([$takerId, $userId]);
    if (!$stmt->fetch()) {
        respond(false, 'You are not allowed to modify this taker account or it does not exist', [], 403);
    }
}

function authorizeClientProfile(PDO $db, array $auth, int $clientId): void
{
    if ($clientId <= 0) {
        respond(false, 'Invalid client account', [], 422);
    }
    
    // Validate if the authenticated unified user_id owns this client profile
    $userId = (int)$auth['user_id'];
    
    $stmt = $db->prepare('SELECT id FROM clients WHERE id=? AND user_id=?' . (tableHasColumn($db, 'clients', 'is_active') ? ' AND is_active=1' : '') . ' LIMIT 1');
    $stmt->execute([$clientId, $userId]);
    if (!$stmt->fetch()) {
        respond(false, 'You are not allowed to modify this client profile or it does not exist', [], 403);
    }
}

function ensureNotificationSchema(PDO $db): void
{
    if (tableExists($db, 'notifications')) {
        return;
    }

    $db->exec(
        "CREATE TABLE IF NOT EXISTS notifications (
            id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            recipient_role ENUM('client','taker') NOT NULL,
            recipient_id INT UNSIGNED NOT NULL,
            type VARCHAR(64) NOT NULL,
            title VARCHAR(160) NOT NULL,
            message VARCHAR(500) NOT NULL,
            payload_json JSON DEFAULT NULL,
            is_read TINYINT(1) NOT NULL DEFAULT 0,
            read_at TIMESTAMP NULL DEFAULT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_notifications_recipient(recipient_role, recipient_id, is_read, created_at),
            INDEX idx_notifications_created(created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );
}

function ensureNotificationDeliveryQueueSchema(PDO $db): void
{
    if (tableExists($db, 'notification_delivery_queue')) {
        return;
    }

    $db->exec(
        "CREATE TABLE IF NOT EXISTS notification_delivery_queue (
            id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            notification_id BIGINT UNSIGNED NOT NULL,
            recipient_role ENUM('client','taker') NOT NULL,
            recipient_id INT UNSIGNED NOT NULL,
            type VARCHAR(80) NOT NULL,
            title VARCHAR(160) NOT NULL,
            message TEXT NOT NULL,
            payload_json TEXT DEFAULT NULL,
            attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
            locked_at TIMESTAMP NULL DEFAULT NULL,
            locked_by VARCHAR(64) DEFAULT NULL,
            delivered_at TIMESTAMP NULL DEFAULT NULL,
            last_error VARCHAR(255) DEFAULT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_ndq_ready(delivered_at, locked_at, attempts, created_at),
            INDEX idx_ndq_lock(locked_by, locked_at),
            INDEX idx_ndq_notification(notification_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );
}

function createNotification(
    PDO $db,
    string $recipientRole,
    int $recipientId,
    string $type,
    string $title,
    string $message,
    array $payload = []
): void {
    if (!in_array($recipientRole, ['client', 'taker'], true) || $recipientId <= 0) {
        return;
    }
    try {
        if ($db->inTransaction() && !tableExists($db, 'notifications', true)) {
            pc_log_runtime_error('Notification skipped because schema is missing during an active transaction');
            return;
        }
        ensureNotificationSchema($db);
    } catch (Throwable $e) {
        pc_log_runtime_error('Notification schema error: ' . $e->getMessage());
        return;
    }

    $stmt = $db->prepare(
        'INSERT INTO notifications (recipient_role, recipient_id, type, title, message, payload_json)
         VALUES (?, ?, ?, ?, ?, ?)'
    );
    $stmt->execute([
        $recipientRole,
        $recipientId,
        $type,
        trim($title),
        trim($message),
        !empty($payload) ? json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) : null,
    ]);
    $notificationId = (int)$db->lastInsertId();

    $delivery = [
        'recipient_role' => $recipientRole,
        'recipient_id' => $recipientId,
        'notification_id' => $notificationId,
        'type' => $type,
        'title' => trim($title),
        'message' => trim($message),
        'payload' => $payload,
    ];
    if ($db->inTransaction()) {
        try {
            if (tableExists($db, 'notification_delivery_queue', true)) {
                pc_enqueue_notification_delivery($db, $delivery);
                return;
            }
        } catch (Throwable $e) {
            pc_log_runtime_error('Notification queue check error: ' . $e->getMessage());
        }
        pc_defer_notification_delivery($delivery);
        return;
    }

    pc_enqueue_notification_delivery($db, $delivery);
    pc_process_notification_delivery_queue($db);
}

function pc_enqueue_notification_delivery(PDO $db, array $delivery): void
{
    try {
        if ($db->inTransaction() && !tableExists($db, 'notification_delivery_queue', true)) {
            pc_defer_notification_delivery($delivery);
            return;
        }
        ensureNotificationDeliveryQueueSchema($db);
        $stmt = $db->prepare(
            'INSERT INTO notification_delivery_queue
                (notification_id, recipient_role, recipient_id, type, title, message, payload_json)
             VALUES (?, ?, ?, ?, ?, ?, ?)'
        );
        $stmt->execute([
            (int)($delivery['notification_id'] ?? 0),
            (string)($delivery['recipient_role'] ?? ''),
            (int)($delivery['recipient_id'] ?? 0),
            (string)($delivery['type'] ?? ''),
            (string)($delivery['title'] ?? ''),
            (string)($delivery['message'] ?? ''),
            !empty($delivery['payload']) && is_array($delivery['payload'])
                ? json_encode($delivery['payload'], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)
                : null,
        ]);
    } catch (Throwable $e) {
        pc_log_runtime_error('Notification queue enqueue error: ' . $e->getMessage());
        if ($db->inTransaction()) {
            pc_defer_notification_delivery($delivery);
        } else {
            pc_deliver_notification($db, $delivery);
        }
    }
}

function pc_defer_notification_delivery(array $delivery): void
{
    if (!isset($GLOBALS['pc_deferred_notification_deliveries']) || !is_array($GLOBALS['pc_deferred_notification_deliveries'])) {
        $GLOBALS['pc_deferred_notification_deliveries'] = [];
    }
    $GLOBALS['pc_deferred_notification_deliveries'][] = $delivery;
}

function pc_clear_deferred_notification_deliveries(): void
{
    $GLOBALS['pc_deferred_notification_deliveries'] = [];
}

function pc_flush_deferred_notification_deliveries(PDO $db): void
{
    $deliveries = $GLOBALS['pc_deferred_notification_deliveries'] ?? [];
    pc_clear_deferred_notification_deliveries();
    foreach ($deliveries as $delivery) {
        pc_deliver_notification($db, $delivery);
    }
    pc_process_notification_delivery_queue($db);
}

function pc_process_notification_delivery_queue(PDO $db, ?int $limit = null): void
{
    $limit = max(1, min(50, $limit ?? NOTIFICATION_DELIVERY_BATCH_SIZE));
    try {
        ensureNotificationDeliveryQueueSchema($db);
        $lockToken = bin2hex(random_bytes(8));
        $claim = $db->prepare(
            "UPDATE notification_delivery_queue
             SET locked_at = NOW(), locked_by = ?
             WHERE delivered_at IS NULL
               AND attempts < 5
               AND (locked_at IS NULL OR locked_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE))
             ORDER BY id ASC
             LIMIT $limit"
        );
        $claim->execute([$lockToken]);

        $select = $db->prepare(
            'SELECT id, notification_id, recipient_role, recipient_id, type, title, message, payload_json
             FROM notification_delivery_queue
             WHERE locked_by = ? AND delivered_at IS NULL
             ORDER BY id ASC'
        );
        $select->execute([$lockToken]);
        $rows = $select->fetchAll();

        $markDelivered = $db->prepare('UPDATE notification_delivery_queue SET delivered_at=NOW(), locked_at=NULL, locked_by=NULL, last_error=NULL WHERE id=?');
        $markFailed = $db->prepare(
            'UPDATE notification_delivery_queue
             SET attempts=attempts+1, locked_at=NULL, locked_by=NULL, last_error=?
             WHERE id=?'
        );

        foreach ($rows as $row) {
            try {
                $payload = json_decode((string)($row['payload_json'] ?? ''), true);
                $payload = is_array($payload) ? $payload : [];
                pc_deliver_notification($db, [
                    'recipient_role' => (string)$row['recipient_role'],
                    'recipient_id' => (int)$row['recipient_id'],
                    'notification_id' => (int)$row['notification_id'],
                    'type' => (string)$row['type'],
                    'title' => (string)$row['title'],
                    'message' => (string)$row['message'],
                    'payload' => $payload,
                ]);
                $markDelivered->execute([(int)$row['id']]);
            } catch (Throwable $e) {
                $markFailed->execute([substr($e->getMessage(), 0, 255), (int)$row['id']]);
                pc_log_runtime_error('Notification queue delivery error: ' . $e->getMessage());
            }
        }
    } catch (Throwable $e) {
        pc_log_runtime_error('Notification queue worker error: ' . $e->getMessage());
    }
}

function pc_deliver_notification(PDO $db, array $delivery): void
{
    pc_send_notification_email(
        $db,
        (string)($delivery['recipient_role'] ?? ''),
        (int)($delivery['recipient_id'] ?? 0),
        (string)($delivery['type'] ?? ''),
        (string)($delivery['title'] ?? ''),
        (string)($delivery['message'] ?? '')
    );
    pc_send_push_notification(
        $db,
        (string)($delivery['recipient_role'] ?? ''),
        (int)($delivery['recipient_id'] ?? 0),
        (int)($delivery['notification_id'] ?? 0),
        (string)($delivery['type'] ?? ''),
        (string)($delivery['title'] ?? ''),
        (string)($delivery['message'] ?? ''),
        is_array($delivery['payload'] ?? null) ? $delivery['payload'] : []
    );
}

function ensureDeviceTokenSchema(PDO $db): void
{
    if (tableExists($db, 'device_tokens')) {
        return;
    }

    $db->exec(
        "CREATE TABLE IF NOT EXISTS device_tokens (
            id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            user_id INT UNSIGNED NOT NULL,
            role ENUM('client','taker','auto','user') NOT NULL DEFAULT 'client',
            client_id INT UNSIGNED DEFAULT NULL,
            taker_id INT UNSIGNED DEFAULT NULL,
            token VARCHAR(255) NOT NULL,
            platform VARCHAR(32) NOT NULL DEFAULT 'android',
            device_name VARCHAR(160) DEFAULT NULL,
            is_active TINYINT(1) NOT NULL DEFAULT 1,
            last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            UNIQUE KEY uq_device_token (token),
            INDEX idx_device_tokens_client (client_id, is_active),
            INDEX idx_device_tokens_taker (taker_id, is_active),
            INDEX idx_device_tokens_user (user_id, is_active)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );
}

function pc_send_push_notification(
    PDO $db,
    string $recipientRole,
    int $recipientId,
    int $notificationId,
    string $type,
    string $title,
    string $message,
    array $payload = []
): void {
    $serverKey = trim((string)FCM_SERVER_KEY);
    $hasServiceAccount = trim((string)FCM_SERVICE_ACCOUNT_FILE) !== ''
        || trim((string)FCM_SERVICE_ACCOUNT_JSON) !== ''
        || trim((string)FCM_SERVICE_ACCOUNT_B64) !== '';
    if (($serverKey === '' && !$hasServiceAccount) || $recipientId <= 0 || !in_array($recipientRole, ['client', 'taker'], true)) {
        return;
    }

    try {
        if ($db->inTransaction() && !tableExists($db, 'device_tokens', true)) {
            return;
        }
        ensureDeviceTokenSchema($db);
        $column = $recipientRole === 'taker' ? 'taker_id' : 'client_id';
        $stmt = $db->prepare(
            "SELECT token
             FROM device_tokens
             WHERE $column = ? AND is_active = 1 AND platform = 'android'
             ORDER BY last_seen_at DESC
             LIMIT 20"
        );
        $stmt->execute([$recipientId]);
        $tokens = array_values(array_filter(array_map('strval', $stmt->fetchAll(PDO::FETCH_COLUMN) ?: [])));
        if (empty($tokens)) {
            return;
        }

        $data = array_merge($payload, [
            'notification_id' => (string)$notificationId,
            'type' => $type,
            'title' => $title,
            'body' => $message,
        ]);
        foreach ($tokens as $token) {
            if ($hasServiceAccount) {
                pc_send_fcm_v1_message($token, $title, $message, $data);
            } elseif ($serverKey !== '') {
                pc_send_fcm_legacy_message($serverKey, $token, $title, $message, $data);
            }
        }
    } catch (Throwable $e) {
        pc_log_runtime_error('Push notification error: ' . $e->getMessage());
    }
}

function pc_fcm_service_account(): ?array
{
    $json = trim((string)FCM_SERVICE_ACCOUNT_JSON);
    if ($json === '') {
        $encoded = preg_replace('/\s+/', '', trim((string)FCM_SERVICE_ACCOUNT_B64));
        if ($encoded !== '') {
            $decoded = base64_decode($encoded, true);
            if ($decoded === false) {
                pc_log_runtime_error('FCM service account base64 is invalid');
                return null;
            }
            $json = $decoded;
        }
    }
    if ($json === '') {
        $file = trim((string)FCM_SERVICE_ACCOUNT_FILE);
        if ($file === '') {
            return null;
        }
        if (!is_file($file) || !is_readable($file)) {
            pc_log_runtime_error('FCM service account file is not readable');
            return null;
        }
        $json = (string)file_get_contents($file);
    }

    $account = json_decode($json, true);
    if (!is_array($account) || empty($account['client_email']) || empty($account['private_key'])) {
        pc_log_runtime_error('FCM service account JSON is invalid');
        return null;
    }
    return $account;
}

function pc_apply_fcm_curl_options($ch): void
{
    $caInfo = trim((string)FCM_CURL_CAINFO);
    if ($caInfo !== '' && is_file($caInfo) && is_readable($caInfo)) {
        curl_setopt($ch, CURLOPT_CAINFO, $caInfo);
    }
}

function pc_fcm_access_token(): ?string
{
    static $cachedToken = null;
    static $expiresAt = 0;
    if (is_string($cachedToken) && $cachedToken !== '' && $expiresAt > time() + 60) {
        return $cachedToken;
    }

    $account = pc_fcm_service_account();
    if ($account === null) {
        return null;
    }

    $now = time();
    $tokenUri = trim((string)($account['token_uri'] ?? 'https://oauth2.googleapis.com/token'));
    $claims = [
        'iss' => (string)$account['client_email'],
        'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
        'aud' => $tokenUri,
        'iat' => $now,
        'exp' => $now + 3600,
    ];
    $signingInput = pc_base64url_encode(json_encode(['alg' => 'RS256', 'typ' => 'JWT']))
        . '.'
        . pc_base64url_encode(json_encode($claims));
    $signature = '';
    if (!openssl_sign($signingInput, $signature, (string)$account['private_key'], OPENSSL_ALGO_SHA256)) {
        pc_log_runtime_error('Unable to sign FCM OAuth assertion');
        return null;
    }

    $assertion = $signingInput . '.' . pc_base64url_encode($signature);
    $ch = curl_init($tokenUri);
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER => ['Content-Type: application/x-www-form-urlencoded'],
        CURLOPT_POSTFIELDS => http_build_query([
            'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
            'assertion' => $assertion,
        ]),
        CURLOPT_CONNECTTIMEOUT => 3,
        CURLOPT_TIMEOUT => 8,
    ]);
    pc_apply_fcm_curl_options($ch);
    $response = curl_exec($ch);
    $curlError = curl_error($ch);
    $httpCode = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    $body = json_decode((string)$response, true);
    if ($httpCode !== 200 || !is_array($body) || empty($body['access_token'])) {
        pc_log_runtime_error(
            'FCM OAuth token request failed HTTP ' . $httpCode
            . ($curlError !== '' ? ' curl_error=' . $curlError : '')
            . ': ' . substr((string)$response, 0, 300)
        );
        return null;
    }

    $cachedToken = (string)$body['access_token'];
    $expiresAt = $now + max(300, (int)($body['expires_in'] ?? 3600));
    return $cachedToken;
}

function pc_send_fcm_v1_message(string $token, string $title, string $message, array $data): void
{
    $projectId = trim((string)FCM_PROJECT_ID);
    $accessToken = pc_fcm_access_token();
    if ($projectId === '' || $accessToken === null) {
        return;
    }

    $body = [
        'message' => [
            'token' => $token,
            'notification' => [
                'title' => $title,
                'body' => $message,
            ],
            'data' => array_map(static fn($value) => is_scalar($value) ? (string)$value : json_encode($value), $data),
            'android' => [
                'priority' => 'HIGH',
                'notification' => [
                    'channel_id' => 'booking_updates',
                    'sound' => 'default',
                ],
            ],
        ],
    ];

    $ch = curl_init('https://fcm.googleapis.com/v1/projects/' . rawurlencode($projectId) . '/messages:send');
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER => [
            'Authorization: Bearer ' . $accessToken,
            'Content-Type: application/json',
        ],
        CURLOPT_POSTFIELDS => json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        CURLOPT_CONNECTTIMEOUT => 3,
        CURLOPT_TIMEOUT => 8,
    ]);
    pc_apply_fcm_curl_options($ch);
    $response = curl_exec($ch);
    $curlError = curl_error($ch);
    $httpCode = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($httpCode >= 400) {
        pc_log_runtime_error(
            'FCM v1 send failed HTTP ' . $httpCode
            . ($curlError !== '' ? ' curl_error=' . $curlError : '')
            . ': ' . substr((string)$response, 0, 300)
        );
    }
}

function pc_send_fcm_legacy_message(string $serverKey, string $token, string $title, string $message, array $data): void
{
    $body = [
        'to' => $token,
        'priority' => 'high',
        'notification' => [
            'title' => $title,
            'body' => $message,
            'sound' => 'default',
        ],
        'data' => array_map(static fn($value) => is_scalar($value) ? (string)$value : json_encode($value), $data),
    ];

    $ch = curl_init('https://fcm.googleapis.com/fcm/send');
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER => [
            'Authorization: key=' . $serverKey,
            'Content-Type: application/json',
        ],
        CURLOPT_POSTFIELDS => json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        CURLOPT_CONNECTTIMEOUT => 2,
        CURLOPT_TIMEOUT => 4,
    ]);
    pc_apply_fcm_curl_options($ch);
    $response = curl_exec($ch);
    $curlError = curl_error($ch);
    $httpCode = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($httpCode >= 400) {
        pc_log_runtime_error(
            'FCM send failed HTTP ' . $httpCode
            . ($curlError !== '' ? ' curl_error=' . $curlError : '')
            . ': ' . substr((string)$response, 0, 300)
        );
    }
}

function pc_notification_recipient_email(PDO $db, string $recipientRole, int $recipientId): string
{
    if ($recipientId <= 0) {
        return '';
    }

    if ($recipientRole === 'taker') {
        $stmt = $db->prepare(
            'SELECT COALESCE(NULLIF(u.email, \'\'), NULLIF(t.email, \'\')) AS email
             FROM takers t
             LEFT JOIN users u ON u.id = t.user_id
             WHERE t.id = ?
             LIMIT 1'
        );
        $stmt->execute([$recipientId]);
        return trim((string)($stmt->fetchColumn() ?: ''));
    }

    if ($recipientRole === 'client') {
        $stmt = $db->prepare(
            'SELECT COALESCE(NULLIF(u.email, \'\'), NULLIF(c.email, \'\')) AS email
             FROM clients c
             LEFT JOIN users u ON u.id = c.user_id
             WHERE c.id = ?
             LIMIT 1'
        );
        $stmt->execute([$recipientId]);
        return trim((string)($stmt->fetchColumn() ?: ''));
    }

    return '';
}

function pc_send_notification_email(
    PDO $db,
    string $recipientRole,
    int $recipientId,
    string $type,
    string $title,
    string $message
): void {
    try {
        $email = pc_notification_recipient_email($db, $recipientRole, $recipientId);
        if ($email === '' || !filter_var($email, FILTER_VALIDATE_EMAIL)) {
            return;
        }

        $safeTitle = htmlspecialchars($title, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
        $safeMessage = nl2br(htmlspecialchars($message, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8'));
        $safeType = htmlspecialchars($type, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
        $subject = 'PhotoConnect: ' . ($title !== '' ? $title : 'New notification');
        $html = '<div style="font-family:Arial,sans-serif;line-height:1.5;color:#10201b">'
            . '<h2 style="margin:0 0 12px">' . $safeTitle . '</h2>'
            . '<p style="margin:0 0 14px">' . $safeMessage . '</p>'
            . '<p style="margin:16px 0 0;color:#60736b;font-size:13px">Notification type: ' . $safeType . '</p>'
            . '<p style="margin:18px 0 0;color:#60736b;font-size:13px">Open PhotoConnect to view details and reply if needed.</p>'
            . '</div>';
        $text = ($title !== '' ? $title . "\n\n" : '') . $message . "\n\nOpen PhotoConnect to view details.";

        if (!pc_send_email($email, $subject, $html, $text)) {
            pc_log_runtime_error("Notification email send failed for {$recipientRole}:{$recipientId}");
        }
    } catch (Throwable $e) {
        pc_log_runtime_error('Notification email error: ' . $e->getMessage());
    }
}

function pc_mb_lower(string $value): string
{
    $value = trim($value);
    if ($value === '') {
        return '';
    }
    return function_exists('mb_strtolower') ? mb_strtolower($value, 'UTF-8') : strtolower($value);
}

function pc_normalize_search_key(string $value): string
{
    $value = pc_mb_lower($value);
    $value = preg_replace('/\s+/', ' ', $value) ?? $value;
    return trim($value);
}

function pc_location_tokens(string ...$values): array
{
    $tokens = [];
    foreach ($values as $value) {
        $normalized = pc_normalize_search_key($value);
        if ($normalized !== '') {
            $tokens[] = $normalized;
        }
    }
    return array_values(array_unique($tokens));
}

function pc_location_matches_any(array $haystack, array $needles): bool
{
    if (empty($haystack) || empty($needles)) {
        return false;
    }

    foreach ($needles as $needle) {
        $normalizedNeedle = pc_normalize_search_key((string)$needle);
        if ($normalizedNeedle === '') {
            continue;
        }
        foreach ($haystack as $item) {
            $normalizedItem = pc_normalize_search_key((string)$item);
            if ($normalizedItem === '') {
                continue;
            }
            if (
                $normalizedItem === $normalizedNeedle ||
                pc_string_contains($normalizedItem, $normalizedNeedle) ||
                pc_string_contains($normalizedNeedle, $normalizedItem)
            ) {
                return true;
            }
        }
    }

    return false;
}

function pc_reverse_geocode(float $lat, float $lon): ?array
{
    $cacheKey = sprintf('reverse:%0.3f:%0.3f', round($lat, 3), round($lon, 3));
    $queryText = sprintf('%0.6f,%0.6f', $lat, $lon);
    $url = rtrim(NOMINATIM_BASE_URL, '/') . '/reverse?format=jsonv2&addressdetails=1&zoom=15'
        . '&lat=' . rawurlencode((string)$lat)
        . '&lon=' . rawurlencode((string)$lon);
    return pc_nominatim_cached_lookup($cacheKey, $queryText, $url);
}

function pc_geocode_search(string $query): ?array
{
    $query = trim($query);
    if ($query === '') {
        return null;
    }

    $cacheKey = 'search:' . pc_normalize_search_key($query);
    $url = rtrim(NOMINATIM_BASE_URL, '/') . '/search?format=jsonv2&addressdetails=1&limit=1'
        . '&q=' . rawurlencode($query);
    if (NOMINATIM_EMAIL !== '') {
        $url .= '&email=' . rawurlencode(NOMINATIM_EMAIL);
    }
    return pc_nominatim_cached_lookup($cacheKey, $query, $url, true);
}

function pc_nominatim_cached_lookup(string $cacheKey, string $queryText, string $url, bool $searchResponseIsList = false): ?array
{
    $db = getDB();
    $cacheKey = trim($cacheKey);
    if ($cacheKey === '') {
        return null;
    }

    if (tableExists($db, 'location_geocode_cache')) {
        $cachedStmt = $db->prepare(
            'SELECT lat, lon, display_name, address_json
             FROM location_geocode_cache
             WHERE cache_key = ?
             LIMIT 1'
        );
        $cachedStmt->execute([$cacheKey]);
        $cached = $cachedStmt->fetch();
        if ($cached) {
            $address = json_decode((string)($cached['address_json'] ?? ''), true);
            $address = is_array($address) ? $address : [];
            $lat = isset($cached['lat']) ? (float)$cached['lat'] : null;
            $lon = isset($cached['lon']) ? (float)$cached['lon'] : null;
            $touchStmt = $db->prepare(
                'UPDATE location_geocode_cache
                 SET hit_count = hit_count + 1, updated_at = NOW()
                 WHERE cache_key = ?'
            );
            $touchStmt->execute([$cacheKey]);
            if ($lat !== null && $lon !== null) {
                return [
                    'lat' => $lat,
                    'lon' => $lon,
                    'display_name' => trim((string)($cached['display_name'] ?? '')),
                    'address' => $address,
                ];
            }
            return null;
        }
    }

    $response = pc_http_json_request($url);
    if (!is_array($response)) {
        pc_cache_geocode_result($db, $cacheKey, $queryText, null, null, null, []);
        return null;
    }

    $payload = $searchResponseIsList ? ($response[0] ?? null) : $response;
    if (!is_array($payload)) {
        pc_cache_geocode_result($db, $cacheKey, $queryText, null, null, null, []);
        return null;
    }

    $lat = isset($payload['lat']) ? (float)$payload['lat'] : null;
    $lon = isset($payload['lon']) ? (float)$payload['lon'] : null;
    $displayName = trim((string)($payload['display_name'] ?? ''));
    $address = is_array($payload['address'] ?? null) ? $payload['address'] : [];
    pc_cache_geocode_result($db, $cacheKey, $queryText, $lat, $lon, $displayName, $address);

    if ($lat === null || $lon === null) {
        return null;
    }

    return [
        'lat' => $lat,
        'lon' => $lon,
        'display_name' => $displayName,
        'address' => $address,
    ];
}

function pc_cache_geocode_result(
    PDO $db,
    string $cacheKey,
    string $queryText,
    ?float $lat,
    ?float $lon,
    ?string $displayName,
    array $address
): void {
    if (!tableExists($db, 'location_geocode_cache')) {
        return;
    }

    $stmt = $db->prepare(
        'INSERT INTO location_geocode_cache (cache_key, query_text, lat, lon, display_name, address_json, provider, hit_count, resolved_at)
         VALUES (?, ?, ?, ?, ?, ?, \'nominatim\', 1, NOW())
         ON DUPLICATE KEY UPDATE
             lat = VALUES(lat),
             lon = VALUES(lon),
             display_name = VALUES(display_name),
             address_json = VALUES(address_json),
             hit_count = hit_count + 1,
             resolved_at = NOW(),
             updated_at = NOW()'
    );
    $stmt->execute([
        $cacheKey,
        $queryText,
        $lat,
        $lon,
        $displayName !== null ? trim($displayName) : null,
        !empty($address) ? json_encode($address, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) : null,
    ]);
}

function pc_http_json_request(string $url): ?array
{
    $headers = [
        'Accept: application/json',
        'User-Agent: ' . NOMINATIM_USER_AGENT,
    ];

    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_TIMEOUT => 10,
            CURLOPT_CONNECTTIMEOUT => 5,
            CURLOPT_HTTPHEADER => $headers,
        ]);
        $raw = curl_exec($ch);
        $status = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);
        if (!is_string($raw) || $raw === '' || $status < 200 || $status >= 300) {
            return null;
        }
    } else {
        $context = stream_context_create([
            'http' => [
                'method' => 'GET',
                'timeout' => 10,
                'header' => implode("\r\n", $headers),
            ],
        ]);
        $raw = @file_get_contents($url, false, $context);
        if (!is_string($raw) || $raw === '') {
            return null;
        }
    }

    $decoded = json_decode($raw, true);
    return is_array($decoded) ? $decoded : null;
}

function pc_path_is_same_or_inside(string $path, string $root): bool
{
    $path = rtrim(str_replace('\\', '/', $path), '/');
    $root = rtrim(str_replace('\\', '/', $root), '/');
    if ($path === '' || $root === '') {
        return false;
    }
    if (DIRECTORY_SEPARATOR === '\\') {
        $path = strtolower($path);
        $root = strtolower($root);
    }
    return $path === $root || pc_starts_with($path, $root . '/');
}

function pc_remove_empty_directories_up_to(string $startDir, string $stopDir): void
{
    $stopReal = realpath($stopDir);
    $current = realpath($startDir);
    if ($stopReal === false || $current === false) {
        return;
    }

    while ($current !== $stopReal && pc_path_is_same_or_inside($current, $stopReal)) {
        $entries = @scandir($current);
        if ($entries === false || count(array_diff($entries, ['.', '..'])) > 0) {
            break;
        }
        if (!@rmdir($current)) {
            break;
        }
        $next = realpath(dirname($current));
        if ($next === false || $next === $current) {
            break;
        }
        $current = $next;
    }
}

function pc_delete_image_and_cache(string $originalPath): void
{
    $photosRoot = getProjectRootPath() . DIRECTORY_SEPARATOR . 'PhotoConnectImages' . DIRECTORY_SEPARATOR . 'photos';
    $originalDir = dirname($originalPath);
    $normalizedOriginalPath = str_replace('\\', '/', $originalPath);
    if (file_exists($originalPath)) {
        @unlink($originalPath);
    }
    pc_remove_empty_directories_up_to($originalDir, $photosRoot);
    
    // Also delete any derived cache files
    if (strpos($normalizedOriginalPath, '/original/') !== false) {
        $cacheDir = str_replace('/', DIRECTORY_SEPARATOR, str_replace('/original/', '/cache/', str_replace('\\', '/', dirname($originalPath))));
        $base = basename($originalPath);
        
        $prefixLength = 0;
        if (pc_starts_with($base, 'original_')) $prefixLength = strlen('original_');
        elseif (pc_starts_with($base, 'sample_')) $prefixLength = strlen('sample_');
        elseif (pc_starts_with($base, 'image_')) $prefixLength = strlen('image_');
        
        if ($prefixLength > 0) {
            $hash = substr($base, $prefixLength, -strlen('.jpg'));
            $thumb = $cacheDir . '/thumb_' . $hash . '.jpg';
            $medium = $cacheDir . '/medium_' . $hash . '.jpg';
            
            if (file_exists($thumb)) @unlink($thumb);
            if (file_exists($medium)) @unlink($medium);
            pc_remove_empty_directories_up_to($cacheDir, $photosRoot);
        }
    }
}

