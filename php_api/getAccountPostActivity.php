<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    respond(false, 'Method not allowed', [], 405);
}

$actorRole = trim((string)($_GET['actorRole'] ?? ''));
$actorId = (int)($_GET['actorId'] ?? 0);
$collection = trim((string)($_GET['collection'] ?? ''));
$page = max(1, (int)($_GET['page'] ?? 1));
$limit = min(24, max(1, (int)($_GET['limit'] ?? 12)));
$offset = ($page - 1) * $limit;

if ($actorId <= 0 || !in_array($actorRole, ['client', 'taker'], true)) {
    respond(false, 'Missing or invalid actor', [], 422);
}
if (!in_array($collection, ['', 'saved', 'liked'], true)) {
    respond(false, 'Invalid collection', [], 422);
}

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    requireActor($db, $auth, $actorRole, $actorId);

    $shouldFetchSaved = $collection === '' || $collection === 'saved';
    $shouldFetchLiked = $collection === '' || $collection === 'liked';

    $savedIds = [];
    $savedTotal = 0;
    if ($shouldFetchSaved) {
        $savedCountStmt = $db->prepare(
            "SELECT COUNT(DISTINCT p.id)
             FROM taker_post_saves s
             INNER JOIN taker_posts p ON p.id = s.post_id
             INNER JOIN takers t ON t.id = p.taker_id
             WHERE s.actor_role = ? AND s.actor_id = ? AND t.is_active = 1"
        );
        $savedCountStmt->execute([$actorRole, $actorId]);
        $savedTotal = (int)$savedCountStmt->fetchColumn();

        $savedStmt = $db->prepare(
            "SELECT DISTINCT p.id
             FROM taker_post_saves s
             INNER JOIN taker_posts p ON p.id = s.post_id
             INNER JOIN takers t ON t.id = p.taker_id
             WHERE s.actor_role = ? AND s.actor_id = ? AND t.is_active = 1
             ORDER BY s.created_at DESC
             LIMIT $limit OFFSET $offset"
        );
        $savedStmt->execute([$actorRole, $actorId]);
        $savedIds = array_map('intval', $savedStmt->fetchAll(PDO::FETCH_COLUMN));
    }

    $likedIds = [];
    $likedTotal = 0;
    if ($shouldFetchLiked) {
        $likedSources = [];
        $likedParams = [];
        if (tableExists($db, 'taker_post_likes')) {
            $likedSources[] = "SELECT tpl.post_id AS post_id, tpl.created_at AS activity_at
                               FROM taker_post_likes tpl
                               WHERE tpl.actor_role = ? AND tpl.actor_id = ?";
            array_push($likedParams, $actorRole, $actorId);
        }
        $likedSources[] = "SELECT i.post_id AS post_id, tpil.created_at AS activity_at
                           FROM taker_post_image_likes tpil
                           INNER JOIN taker_post_images i ON i.id = tpil.image_id
                           WHERE tpil.actor_role = ? AND tpil.actor_id = ?";
        array_push($likedParams, $actorRole, $actorId);

        $likedSql = implode(' UNION ALL ', $likedSources);
        $likedBase = "FROM ($likedSql) activity
             INNER JOIN taker_posts p ON p.id = activity.post_id
             INNER JOIN takers t ON t.id = p.taker_id
             WHERE t.is_active = 1
             GROUP BY activity.post_id";

        $likedCountStmt = $db->prepare("SELECT COUNT(*) FROM (SELECT activity.post_id $likedBase) liked_group");
        $likedCountStmt->execute($likedParams);
        $likedTotal = (int)$likedCountStmt->fetchColumn();

        $likedStmt = $db->prepare(
            "SELECT activity.post_id
             $likedBase
             ORDER BY MAX(activity.activity_at) DESC
             LIMIT $limit OFFSET $offset"
        );
        $likedStmt->execute($likedParams);
        $likedIds = array_map('intval', $likedStmt->fetchAll(PDO::FETCH_COLUMN));
    }

    respond(true, 'OK', [
        'collection' => $collection === '' ? null : $collection,
        'saved_posts' => pc_fetch_activity_posts($db, $savedIds, $actorRole, $actorId),
        'liked_posts' => pc_fetch_activity_posts($db, $likedIds, $actorRole, $actorId),
        'saved_total' => $savedTotal,
        'saved_page' => $page,
        'saved_limit' => $limit,
        'saved_total_pages' => (int)max(1, ceil($savedTotal / max(1, $limit))),
        'liked_total' => $likedTotal,
        'liked_page' => $page,
        'liked_limit' => $limit,
        'liked_total_pages' => (int)max(1, ceil($likedTotal / max(1, $limit))),
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}

function pc_fetch_activity_posts(PDO $db, array $postIds, string $viewerRole, int $viewerId): array
{
    if (empty($postIds)) {
        return [];
    }
    $postIds = array_values(array_unique(array_map('intval', $postIds)));
    $placeholders = implode(',', array_fill(0, count($postIds), '?'));
    $orderExpr = 'FIELD(p.id,' . implode(',', array_fill(0, count($postIds), '?')) . ')';

    $likeSelect = tableExists($db, 'taker_post_likes')
        ? 'CASE WHEN EXISTS (
            SELECT 1 FROM taker_post_likes tpl
            WHERE tpl.post_id = p.id AND tpl.actor_role = ? AND tpl.actor_id = ?
        ) THEN 1 ELSE 0 END AS viewer_has_liked'
        : '0 AS viewer_has_liked';
    $params = tableExists($db, 'taker_post_likes')
        ? array_merge([$viewerRole, $viewerId], $postIds, $postIds)
        : array_merge($postIds, $postIds);

    $saveSelect = 'CASE WHEN EXISTS (
        SELECT 1 FROM taker_post_saves tps
        WHERE tps.post_id = p.id AND tps.actor_role = ? AND tps.actor_id = ?
    ) THEN 1 ELSE 0 END AS viewer_has_saved';
    $params = tableExists($db, 'taker_post_likes')
        ? array_merge([$viewerRole, $viewerId], [$viewerRole, $viewerId], $postIds, $postIds)
        : array_merge([$viewerRole, $viewerId], $postIds, $postIds);

    $stmt = $db->prepare(
        "SELECT p.id, p.taker_id, p.caption, p.like_count, p.view_count, p.created_at, p.updated_at,
                $likeSelect, $saveSelect
         FROM taker_posts p
         INNER JOIN takers t ON t.id = p.taker_id
         WHERE p.id IN ($placeholders) AND t.is_active = 1
         ORDER BY $orderExpr"
    );
    $stmt->execute($params);
    $posts = $stmt->fetchAll();
    if (empty($posts)) {
        return [];
    }

    $ids = array_map(fn($post) => (int)$post['id'], $posts);
    $imagePlaceholders = implode(',', array_fill(0, count($ids), '?'));
    $imageStmt = $db->prepare(
        "SELECT i.id, i.post_id, i.image_url, i.like_count, i.sort_order,
                CASE WHEN EXISTS (
                    SELECT 1 FROM taker_post_image_likes tpil
                    WHERE tpil.image_id = i.id AND tpil.actor_role = ? AND tpil.actor_id = ?
                ) THEN 1 ELSE 0 END AS viewer_has_liked
         FROM taker_post_images i
         WHERE i.post_id IN ($imagePlaceholders)
         ORDER BY i.sort_order ASC, i.id ASC"
    );
    $imageStmt->execute(array_merge([$viewerRole, $viewerId], $ids));
    $imagesByPost = [];
    foreach ($imageStmt->fetchAll() as $imageRow) {
        $rawUrl = $imageRow['image_url'] ?? null;
        $imageRow['image_url'] = normalizeDeliveredImageUrl($rawUrl);
        $imageRow['thumb_url'] = normalizeDeliveredImageUrl($rawUrl, 'thumb');
        $imageRow['like_count'] = (int)($imageRow['like_count'] ?? 0);
        $imageRow['viewer_has_liked'] = (int)($imageRow['viewer_has_liked'] ?? 0) === 1;
        $imagesByPost[(int)$imageRow['post_id']][] = $imageRow;
    }

    foreach ($posts as &$post) {
        $post['viewer_has_liked'] = (int)($post['viewer_has_liked'] ?? 0) === 1;
        $post['viewer_has_saved'] = (int)($post['viewer_has_saved'] ?? 0) === 1;
        $post['images'] = $imagesByPost[(int)$post['id']] ?? [];
    }
    unset($post);
    return $posts;
}
