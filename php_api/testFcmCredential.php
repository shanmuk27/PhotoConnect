<?php
if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}

require_once __DIR__ . '/config.php';

$targetToken = trim((string)($argv[1] ?? ''));
$hasServerKey = trim((string)FCM_SERVER_KEY) !== '';
$hasServiceAccount = trim((string)FCM_SERVICE_ACCOUNT_FILE) !== ''
    || trim((string)FCM_SERVICE_ACCOUNT_JSON) !== ''
    || trim((string)FCM_SERVICE_ACCOUNT_B64) !== '';

echo "FCM project: " . (trim((string)FCM_PROJECT_ID) !== '' ? FCM_PROJECT_ID : '<empty>') . PHP_EOL;
echo "Legacy server key: " . ($hasServerKey ? 'configured' : 'missing') . PHP_EOL;
echo "Service account: " . ($hasServiceAccount ? 'configured' : 'missing') . PHP_EOL;

if (!$hasServerKey && !$hasServiceAccount) {
    echo "RESULT: FAIL - no FCM sender credential is configured." . PHP_EOL;
    echo "Add FCM_SERVICE_ACCOUNT_FILE, FCM_SERVICE_ACCOUNT_JSON, or FCM_SERVICE_ACCOUNT_B64 to php_api/.env, then rerun." . PHP_EOL;
    exit(2);
}

if ($hasServiceAccount) {
    $accessToken = pc_fcm_access_token();
    if ($accessToken === null || $accessToken === '') {
        echo "RESULT: FAIL - service account could not get a Firebase OAuth access token." . PHP_EOL;
        exit(3);
    }
    echo "OAuth token: OK" . PHP_EOL;

    if ($targetToken !== '') {
        pc_send_fcm_v1_message(
            $targetToken,
            'PhotoConnect test',
            'This is a local terminal FCM test.',
            ['type' => 'terminal_test']
        );
        echo "Send request: attempted with FCM HTTP v1. Check phone and PHP runtime logs for Firebase errors." . PHP_EOL;
    } else {
        echo "Send request: skipped because no device token argument was passed." . PHP_EOL;
    }

    echo "RESULT: OK - service account credential can authenticate." . PHP_EOL;
    exit(0);
}

if ($hasServerKey) {
    if ($targetToken === '') {
        echo "RESULT: PARTIAL - legacy server key is configured, but pass a device token to test sending." . PHP_EOL;
        exit(0);
    }
    pc_send_fcm_legacy_message(
        (string)FCM_SERVER_KEY,
        $targetToken,
        'PhotoConnect test',
        'This is a local terminal FCM test.',
        ['type' => 'terminal_test']
    );
    echo "RESULT: SENT - legacy FCM request attempted. Check phone and PHP runtime logs for Firebase errors." . PHP_EOL;
    exit(0);
}
