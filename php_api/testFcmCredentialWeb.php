<?php
require_once __DIR__ . '/config.php';

header('Content-Type: text/html; charset=utf-8');

session_start();

function fcm_web_e($value): string
{
    return htmlspecialchars((string)$value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

function fcm_web_csrf(): string
{
    if (empty($_SESSION['fcm_test_csrf'])) {
        $_SESSION['fcm_test_csrf'] = bin2hex(random_bytes(32));
    }
    return (string)$_SESSION['fcm_test_csrf'];
}

function fcm_web_check_csrf(): void
{
    $token = (string)($_POST['csrf'] ?? '');
    if ($token === '' || empty($_SESSION['fcm_test_csrf']) || !hash_equals((string)$_SESSION['fcm_test_csrf'], $token)) {
        throw new RuntimeException('Session expired. Refresh and try again.');
    }
}

function fcm_web_authorized(): bool
{
    $configured = trim((string)ADMIN_API_KEY);
    if ($configured === '') {
        return false;
    }
    if (!empty($_SESSION['fcm_test_admin_ok'])) {
        return true;
    }
    $provided = trim((string)($_POST['admin_key'] ?? $_GET['admin_key'] ?? ''));
    if ($provided !== '' && hash_equals($configured, $provided)) {
        $_SESSION['fcm_test_admin_ok'] = true;
        return true;
    }
    return false;
}

function fcm_web_mask_token(string $token): string
{
    if (strlen($token) <= 18) {
        return str_repeat('*', strlen($token));
    }
    return substr($token, 0, 10) . '...' . substr($token, -8);
}

function fcm_web_service_configured(): bool
{
    return trim((string)FCM_SERVICE_ACCOUNT_FILE) !== ''
        || trim((string)FCM_SERVICE_ACCOUNT_JSON) !== ''
        || trim((string)FCM_SERVICE_ACCOUNT_B64) !== ''
        || trim((string)FCM_SERVER_KEY) !== '';
}

$messages = [];
$error = '';
$authorized = fcm_web_authorized();

if (!$authorized && ($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST') {
    $error = 'Admin key is not correct.';
}

try {
    if ($authorized && ($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST' && isset($_POST['send_token_id'])) {
        fcm_web_check_csrf();
        $tokenId = (int)($_POST['send_token_id'] ?? 0);
        if ($tokenId <= 0) {
            throw new RuntimeException('Invalid token id.');
        }
        $db = getDB();
        ensureDeviceTokenSchema($db);
        $stmt = $db->prepare('SELECT token FROM device_tokens WHERE id = ? AND is_active = 1 LIMIT 1');
        $stmt->execute([$tokenId]);
        $token = trim((string)($stmt->fetchColumn() ?: ''));
        if ($token === '') {
            throw new RuntimeException('Device token not found.');
        }
        if (trim((string)FCM_SERVICE_ACCOUNT_FILE) !== '' || trim((string)FCM_SERVICE_ACCOUNT_JSON) !== '' || trim((string)FCM_SERVICE_ACCOUNT_B64) !== '') {
            pc_send_fcm_v1_message(
                $token,
                'PhotoConnect test',
                'This is a server web FCM test.',
                ['type' => 'web_terminal_test']
            );
        } elseif (trim((string)FCM_SERVER_KEY) !== '') {
            pc_send_fcm_legacy_message(
                (string)FCM_SERVER_KEY,
                $token,
                'PhotoConnect test',
                'This is a server web FCM test.',
                ['type' => 'web_terminal_test']
            );
        }
        $messages[] = 'Send request attempted. Check the phone and runtime_error.log if it does not arrive.';
    }
} catch (Throwable $e) {
    $error = $e->getMessage();
}

$statusRows = [];
$tokens = [];
if ($authorized) {
    $statusRows[] = ['FCM project', trim((string)FCM_PROJECT_ID) !== '' ? FCM_PROJECT_ID : 'missing'];
    $statusRows[] = ['Legacy server key', trim((string)FCM_SERVER_KEY) !== '' ? 'configured' : 'missing'];
    $statusRows[] = ['Service account', fcm_web_service_configured() ? 'configured' : 'missing'];
    $statusRows[] = ['curl extension', extension_loaded('curl') ? 'enabled' : 'missing'];
    $statusRows[] = ['openssl extension', extension_loaded('openssl') ? 'enabled' : 'missing'];

    if (fcm_web_service_configured() && (trim((string)FCM_SERVICE_ACCOUNT_FILE) !== '' || trim((string)FCM_SERVICE_ACCOUNT_JSON) !== '' || trim((string)FCM_SERVICE_ACCOUNT_B64) !== '')) {
        $accessToken = pc_fcm_access_token();
        $statusRows[] = ['OAuth token', $accessToken !== null ? 'OK' : 'FAILED'];
        if ($accessToken === null) {
            $messages[] = 'OAuth failed. Check php_api/runtime_error.log for the exact Firebase/cURL error.';
        }
    }

    try {
        $db = getDB();
        ensureDeviceTokenSchema($db);
        $tokens = $db->query(
            'SELECT id, user_id, role, client_id, taker_id, token, platform, is_active, last_seen_at
             FROM device_tokens
             ORDER BY last_seen_at DESC
             LIMIT 20'
        )->fetchAll() ?: [];
    } catch (Throwable $e) {
        $messages[] = 'Could not read device tokens: ' . $e->getMessage();
    }
}
?>
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>PhotoConnect FCM Test</title>
    <style>
        body { margin: 0; font-family: Arial, sans-serif; background: #0b1512; color: #ecf4ef; }
        main { max-width: 920px; margin: 0 auto; padding: 28px 18px 56px; }
        h1 { margin: 0 0 18px; font-size: 30px; }
        table { width: 100%; border-collapse: collapse; margin: 16px 0 24px; background: #13251f; }
        th, td { padding: 12px; border-bottom: 1px solid #29463c; text-align: left; vertical-align: top; }
        th { color: #86f4dd; font-size: 13px; text-transform: uppercase; letter-spacing: .04em; }
        input, button { border: 0; border-radius: 6px; padding: 11px 12px; font-size: 15px; }
        input { width: min(420px, 100%); background: #ecf4ef; color: #0b1512; }
        button { background: #58e6cf; color: #08201b; font-weight: 700; cursor: pointer; }
        .panel { background: #13251f; border: 1px solid #29463c; border-radius: 8px; padding: 18px; margin: 18px 0; }
        .ok { color: #75f0a2; }
        .bad { color: #ff8b8b; }
        .note { color: #b6c8c0; }
        .msg { background: #183f35; border: 1px solid #2e6d5f; padding: 12px; border-radius: 6px; margin: 10px 0; }
        .err { background: #4a1717; border-color: #8f3030; }
        form.inline { display: inline; }
    </style>
</head>
<body>
<main>
    <h1>PhotoConnect FCM Test</h1>

    <?php if (!$authorized): ?>
        <div class="panel">
            <p class="note">Enter ADMIN_API_KEY to open the FCM test page.</p>
            <?php if ($error !== ''): ?><div class="msg err"><?php echo fcm_web_e($error); ?></div><?php endif; ?>
            <form method="post">
                <input type="password" name="admin_key" placeholder="Admin API key" autocomplete="off">
                <button type="submit">Open Test</button>
            </form>
        </div>
    <?php else: ?>
        <?php foreach ($messages as $message): ?>
            <div class="msg"><?php echo fcm_web_e($message); ?></div>
        <?php endforeach; ?>
        <?php if ($error !== ''): ?><div class="msg err"><?php echo fcm_web_e($error); ?></div><?php endif; ?>

        <div class="panel">
            <h2>Credential Status</h2>
            <table>
                <tbody>
                <?php foreach ($statusRows as [$label, $value]): ?>
                    <tr>
                        <th><?php echo fcm_web_e($label); ?></th>
                        <td class="<?php echo in_array($value, ['OK', 'configured', 'enabled'], true) ? 'ok' : ($value === 'missing' || $value === 'FAILED' ? 'bad' : ''); ?>">
                            <?php echo fcm_web_e($value); ?>
                        </td>
                    </tr>
                <?php endforeach; ?>
                </tbody>
            </table>
        </div>

        <div class="panel">
            <h2>Recent Device Tokens</h2>
            <?php if (empty($tokens)): ?>
                <p class="note">No device tokens are saved yet. Install the latest APK, open the app, allow notifications, and log in once.</p>
            <?php else: ?>
                <table>
                    <thead>
                    <tr>
                        <th>ID</th>
                        <th>User</th>
                        <th>Client</th>
                        <th>Taker</th>
                        <th>Token</th>
                        <th>Last seen</th>
                        <th>Test</th>
                    </tr>
                    </thead>
                    <tbody>
                    <?php foreach ($tokens as $tokenRow): ?>
                        <tr>
                            <td><?php echo (int)$tokenRow['id']; ?></td>
                            <td><?php echo fcm_web_e($tokenRow['user_id']); ?></td>
                            <td><?php echo fcm_web_e($tokenRow['client_id'] ?: '-'); ?></td>
                            <td><?php echo fcm_web_e($tokenRow['taker_id'] ?: '-'); ?></td>
                            <td><?php echo fcm_web_e(fcm_web_mask_token((string)$tokenRow['token'])); ?></td>
                            <td><?php echo fcm_web_e($tokenRow['last_seen_at']); ?></td>
                            <td>
                                <form class="inline" method="post">
                                    <input type="hidden" name="csrf" value="<?php echo fcm_web_e(fcm_web_csrf()); ?>">
                                    <input type="hidden" name="send_token_id" value="<?php echo (int)$tokenRow['id']; ?>">
                                    <button type="submit">Send Test</button>
                                </form>
                            </td>
                        </tr>
                    <?php endforeach; ?>
                    </tbody>
                </table>
            <?php endif; ?>
        </div>
    <?php endif; ?>
</main>
</body>
</html>
