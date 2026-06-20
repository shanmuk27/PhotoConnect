<?php
require_once 'config.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    respond(false, 'Method not allowed', [], 405);
}

$takerId = (int)($_GET['takerId'] ?? 0);
if ($takerId <= 0) {
    respond(false, 'Missing takerId', [], 422);
}

$viewerRole = trim((string)($_GET['viewerRole'] ?? ''));
$viewerId = (int)($_GET['viewerId'] ?? 0);
if (!in_array($viewerRole, ['', 'client', 'taker'], true)) {
    respond(false, 'Invalid viewerRole', [], 422);
}

$page = max(1, (int)($_GET['page'] ?? 1));
$limit = min(24, max(1, (int)($_GET['limit'] ?? 12)));
$offset = ($page - 1) * $limit;

try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    rateLimit('posts_read_user:' . (int)$auth['user_id'], 'posts-read', 240, 60);
    $hasFavoritesTable = tableExists($db, 'taker_favorites');
    $check = $db->prepare('SELECT id FROM takers WHERE id=? AND is_active=1 LIMIT 1');
    $check->execute([$takerId]);
    if (!$check->fetch()) {
        respond(false, 'Taker not found', [], 404);
    }
    if ($viewerRole !== '' || $viewerId > 0) {
        if ($auth === null) {
            respond(false, 'Authentication required for viewer context', [], 401);
        }
        requireActor($db, $auth, $viewerRole, $viewerId);
    }

    $likeSelect = '0 AS viewer_has_liked';
    $saveSelect = '0 AS viewer_has_saved';
    $params = [$takerId];
    if ($viewerRole !== '' && $viewerId > 0) {
        $likeSelect = 'CASE WHEN EXISTS (
            SELECT 1 FROM taker_post_likes tpl
            WHERE tpl.post_id = p.id AND tpl.actor_role = ? AND tpl.actor_id = ?
        ) THEN 1 ELSE 0 END AS viewer_has_liked';
        $saveSelect = 'CASE WHEN EXISTS (
            SELECT 1 FROM taker_post_saves tps
            WHERE tps.post_id = p.id AND tps.actor_role = ? AND tps.actor_id = ?
        ) THEN 1 ELSE 0 END AS viewer_has_saved';
        $params = [$viewerRole, $viewerId, $viewerRole, $viewerId, $takerId];
    }

    $countStmt = $db->prepare('SELECT COUNT(*) FROM taker_posts WHERE taker_id = ?');
    $countStmt->execute([$takerId]);
    $total = (int)$countStmt->fetchColumn();

    $postStmt = $db->prepare(
        "SELECT p.id, p.taker_id, p.caption, p.like_count, p.view_count, p.created_at, p.updated_at, $likeSelect, $saveSelect
         FROM taker_posts p
         WHERE p.taker_id = ?
         ORDER BY p.created_at DESC, p.id DESC
         LIMIT $limit OFFSET $offset"
    );
    $postStmt->execute($params);
    $posts = $postStmt->fetchAll();

    $imagesByPost = [];
    if (!empty($posts)) {
        $postIds = array_map(fn($post) => (int)$post['id'], $posts);
        $placeholders = implode(',', array_fill(0, count($postIds), '?'));
        $imageViewerLikeExpr = '0 AS viewer_has_liked';
        $imageParams = $postIds;
        if ($viewerRole !== '' && $viewerId > 0) {
            $imageViewerLikeExpr = 'CASE WHEN EXISTS (
                SELECT 1 FROM taker_post_image_likes tpil
                WHERE tpil.image_id = i.id AND tpil.actor_role = ? AND tpil.actor_id = ?
            ) THEN 1 ELSE 0 END AS viewer_has_liked';
            $imageParams = array_merge([$viewerRole, $viewerId], $postIds);
        }
        $imageStmt = $db->prepare(
            "SELECT i.id, i.post_id, i.image_url, i.like_count, i.sort_order, $imageViewerLikeExpr
             FROM taker_post_images i
             WHERE i.post_id IN ($placeholders)
             ORDER BY i.sort_order ASC, i.id ASC"
        );
        $imageStmt->execute($imageParams);
        foreach ($imageStmt->fetchAll() as $imageRow) {
            $rawUrl = $imageRow['image_url'] ?? null;
            $imageRow['image_url'] = normalizeDeliveredImageUrl($rawUrl);
            // Optional progressive-loading hint for clients (keeps backward compatibility).
            $imageRow['thumb_url'] = normalizeDeliveredImageUrl($rawUrl, 'thumb');
            $imageRow['like_count'] = (int)($imageRow['like_count'] ?? 0);
            $imageRow['viewer_has_liked'] = (int)($imageRow['viewer_has_liked'] ?? 0) === 1;
            $imagesByPost[(int)$imageRow['post_id']][] = $imageRow;
        }
    }

    foreach ($posts as &$post) {
        $post['viewer_has_liked'] = (int)($post['viewer_has_liked'] ?? 0) === 1;
        $post['viewer_has_saved'] = (int)($post['viewer_has_saved'] ?? 0) === 1;
        $post['images'] = $imagesByPost[(int)$post['id']] ?? [];
    }
    unset($post);

    $favoriteSummaryExpr = $hasFavoritesTable
        ? '(SELECT COUNT(*) FROM taker_favorites tf WHERE tf.taker_id = t.id) AS favorite_count'
        : '0 AS favorite_count';
    $summaryStmt = $db->prepare(
        'SELECT
            (SELECT COUNT(*) FROM taker_posts WHERE taker_id = t.id) AS post_count,
            (SELECT COALESCE(SUM(like_count), 0) FROM taker_posts WHERE taker_id = t.id) AS total_likes,
            (SELECT COALESCE(SUM(view_count), 0) FROM taker_posts WHERE taker_id = t.id) AS total_views,
            ' . $favoriteSummaryExpr . ',
            COALESCE(t.avg_rating, 0) AS avg_rating,
            COALESCE(t.review_count, 0) AS review_count
         FROM takers t
         WHERE t.id = ?
         LIMIT 1'
    );
    $summaryStmt->execute([$takerId]);
    $summary = $summaryStmt->fetch() ?: ['post_count' => 0, 'total_likes' => 0, 'total_views' => 0];

    respond(true, 'OK', [
        'taker_id' => $takerId,
        'summary' => [
            'post_count' => (int)$summary['post_count'],
            'total_likes' => (int)$summary['total_likes'],
            'total_views' => (int)$summary['total_views'],
            'favorite_count' => (int)$summary['favorite_count'],
            'avg_rating' => (float)$summary['avg_rating'],
            'review_count' => (int)$summary['review_count'],
        ],
        'total' => $total,
        'page' => $page,
        'limit' => $limit,
        'total_pages' => (int)max(1, ceil($total / max(1, $limit))),
        'posts' => $posts,
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
