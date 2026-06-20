<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    respond(false, 'Method not allowed', [], 405);
}

$takerId = (int)($_GET['takerId'] ?? 0);
if ($takerId <= 0) {
    respond(false, 'Missing takerId', [], 422);
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    rateLimit('portfolio_read_user:' . (int)$auth['user_id'], 'portfolio-read', 240, 60);
    $stmt = $db->prepare(
        'SELECT id, taker_id, image_url, caption
         FROM portfolio_samples
         WHERE taker_id=?
         ORDER BY created_at DESC, id DESC'
    );
    $stmt->execute([$takerId]);
    $samples = $stmt->fetchAll();
    foreach ($samples as &$sample) {
        $sample['image_url'] = normalizeDeliveredImageUrl($sample['image_url'] ?? null);
    }
    unset($sample);
    respond(true, 'OK', [
        'taker_id' => $takerId,
        'samples' => $samples,
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
