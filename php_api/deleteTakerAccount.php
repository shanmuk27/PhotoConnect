<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    respond(false, 'Method not allowed', [], 405);
}

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$takerId = (int)($body['taker_id'] ?? 0);

if ($takerId <= 0) {
    respond(false, 'Missing taker_id', [], 422);
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    authorizeTaker($db, $auth, $takerId);

    // 1. Find user_id
    $stmt = $db->prepare('SELECT id, user_id FROM takers WHERE id = ?');
    $stmt->execute([$takerId]);
    $taker = $stmt->fetch();
    
    if (!$taker || empty($taker['user_id'])) {
        respond(false, 'Account not found', [], 404);
    }
    
    $userId = (int)$taker['user_id'];
    
    // We will collect all image URLs to delete from the filesystem
    $imageUrls = [];
    
    // 2. Takers profile images
    $stmt = $db->prepare('SELECT profile_image_url, profile_thumb_url FROM takers WHERE user_id = ?');
    $stmt->execute([$userId]);
    foreach ($stmt->fetchAll() as $row) {
        if (!empty($row['profile_image_url'])) $imageUrls[] = $row['profile_image_url'];
        if (!empty($row['profile_thumb_url'])) $imageUrls[] = $row['profile_thumb_url'];
    }

    // 3. Taker posts images
    $stmt = $db->prepare('
        SELECT image_url, thumb_url 
        FROM taker_post_images 
        WHERE post_id IN (
            SELECT id FROM taker_posts WHERE taker_id IN (SELECT id FROM takers WHERE user_id = ?)
        )
    ');
    $stmt->execute([$userId]);
    foreach ($stmt->fetchAll() as $row) {
        if (!empty($row['image_url'])) $imageUrls[] = $row['image_url'];
        if (!empty($row['thumb_url'])) $imageUrls[] = $row['thumb_url'];
    }

    // 4. Client profile images (if any in the future)
    // Client table does not currently store profile_image_url, but if it does, it will cascade nicely anyway.

    // 5. Unlink all physical files and their cache variants
    foreach ($imageUrls as $url) {
        $filePath = projectFilePathFromUrl((string)$url);
        if ($filePath) {
            pc_delete_image_and_cache($filePath);
        }
    }
    
    // Clean up empty taker post directories
    $stmt = $db->prepare('SELECT taker_id, id AS post_id FROM taker_posts WHERE taker_id IN (SELECT id FROM takers WHERE user_id = ?)');
    $stmt->execute([$userId]);
    foreach ($stmt->fetchAll() as $row) {
        $originalDir = getProjectRootPath() . '/PhotoConnectImages/photos/original/taker_posts/' . $row['taker_id'] . '/' . $row['post_id'];
        $cacheDir = getProjectRootPath() . '/PhotoConnectImages/photos/cache/taker_posts/' . $row['taker_id'] . '/' . $row['post_id'];
        if (is_dir($originalDir)) @rmdir($originalDir);
        if (is_dir($cacheDir)) @rmdir($cacheDir);
    }

    // 6. Delete User AND ALL dependencies in a transaction
    $db->beginTransaction();

    // Delete bookings (has ON DELETE RESTRICT so must be manually deleted)
    $db->prepare('
        DELETE FROM bookings 
        WHERE taker_id IN (SELECT id FROM takers WHERE user_id = ?) 
           OR client_id IN (SELECT id FROM clients WHERE user_id = ?)
    ')->execute([$userId, $userId]);

    // Delete events (no cascade so must be manually deleted to avoid orphans)
    $db->prepare('
        DELETE FROM events 
        WHERE taker_id IN (SELECT id FROM takers WHERE user_id = ?) 
           OR client_id IN (SELECT id FROM clients WHERE user_id = ?) 
           OR (created_by_role = "taker" AND created_by_id IN (SELECT id FROM takers WHERE user_id = ?)) 
           OR (created_by_role = "client" AND created_by_id IN (SELECT id FROM clients WHERE user_id = ?))
    ')->execute([$userId, $userId, $userId, $userId]);

    // Delete notifications (no cascade)
    $db->prepare('
        DELETE FROM notifications 
        WHERE (recipient_role = "taker" AND recipient_id IN (SELECT id FROM takers WHERE user_id = ?)) 
           OR (recipient_role = "client" AND recipient_id IN (SELECT id FROM clients WHERE user_id = ?))
    ')->execute([$userId, $userId]);

    // Delete help tickets
    $db->prepare('DELETE FROM help_tickets WHERE user_id = ?')->execute([$userId]);

    // Finally, nuke the user. Cascade handles `takers`, `clients`, `taker_posts`, `availability`, etc.
    $db->prepare('DELETE FROM users WHERE id = ?')->execute([$userId]);

    $db->commit();
    
    respond(true, 'Account permanently deleted', ['deleted' => true]);

} catch (PDOException $e) {
    if (isset($db) && $db->inTransaction()) {
        $db->rollBack();
    }
    pc_log_runtime_error('Delete Account Error: ' . $e->getMessage());
    respond(false, 'Database error during account deletion', [], 500);
}
