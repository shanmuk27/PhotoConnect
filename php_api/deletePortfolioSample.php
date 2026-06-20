<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'DELETE') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$takerId = (int)($body['taker_id'] ?? 0);
$sampleId = (int)($body['sample_id'] ?? 0);

if ($takerId <= 0 || $sampleId <= 0) {
    respond(false, 'Missing taker_id or sample_id', [], 422);
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    authorizeTaker($db, $auth, $takerId);
    $db->beginTransaction();

    $stmt = $db->prepare(
        'SELECT image_url
         FROM portfolio_samples
         WHERE id=? AND taker_id=?
         LIMIT 1
         FOR UPDATE'
    );
    $stmt->execute([$sampleId, $takerId]);
    $sample = $stmt->fetch();
    if (!$sample) {
        $db->rollBack();
        respond(false, 'Portfolio post not found', [], 404);
    }

    $delete = $db->prepare('DELETE FROM portfolio_samples WHERE id=? AND taker_id=?');
    $delete->execute([$sampleId, $takerId]);
    $db->commit();

    $filePath = projectFilePathFromUrl((string)$sample['image_url']);
    if ($filePath) {
        pc_delete_image_and_cache($filePath);
    }
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    respond(false, $e->getMessage(), [], 500);
}

respond(true, 'Portfolio post deleted', ['deleted' => true]);
