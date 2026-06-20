<?php
require_once 'config.php';
if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(false, 'Method not allowed', [], 405);

$b = json_decode(file_get_contents('php://input'), true);
$takerId = (int)($b['taker_id'] ?? 0);
if ($takerId <= 0) respond(false, 'Missing taker_id', [], 422);

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    authorizeTaker($db, $auth, $takerId);

    $db->beginTransaction();
    $old = $db->prepare(
        'SELECT profile_image_url, profile_thumb_url
         FROM takers
         WHERE id = ? AND is_active = 1
         LIMIT 1
         FOR UPDATE'
    );
    $old->execute([$takerId]);
    $oldUrls = $old->fetch() ?: [];

    $update = $db->prepare(
        'UPDATE takers
         SET profile_image_url = NULL,
             profile_thumb_url = NULL,
             profile_image_scope = ?
         WHERE id = ? AND is_active = 1'
    );
    $update->execute(['public', $takerId]);
    $db->commit();

    foreach (['profile_image_url', 'profile_thumb_url'] as $column) {
        $oldPath = projectFilePathFromUrl((string)($oldUrls[$column] ?? ''));
        if ($oldPath !== null) {
            pc_delete_image_and_cache($oldPath);
        }
    }

    respond(true, 'Profile photo removed', ['deleted' => true]);
} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    respond(false, $e->getMessage(), [], 500);
}
