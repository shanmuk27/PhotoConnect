<?php
require_once 'config.php';

$isCli = PHP_SAPI === 'cli';
$token = trim((string)pc_env('NOTIFICATION_QUEUE_TOKEN', ''));
if (!$isCli) {
    $given = trim((string)($_GET['token'] ?? $_POST['token'] ?? ''));
    if ($token === '' || !hash_equals($token, $given)) {
        respond(false, 'Not found', [], 404);
    }
}

$limit = max(1, min(50, (int)($_GET['limit'] ?? $_POST['limit'] ?? NOTIFICATION_DELIVERY_BATCH_SIZE)));
$db = getDB();
ensureNotificationDeliveryQueueSchema($db);
pc_process_notification_delivery_queue($db, $limit);

respond(true, 'Notification queue processed', ['limit' => $limit]);
