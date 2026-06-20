<?php
require_once 'config.php';
require_once 'trustHelpers.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    respond(false, 'Method not allowed', [], 405);
}

$takerId = (int)($_GET['takerId'] ?? 0);
if ($takerId <= 0) {
    respond(false, 'Invalid takerId', [], 422);
}

try {
    $db = getDB();
    ensureTrustSchema($db);

    $auth = requireAuthenticatedUser();
    rateLimit('profile_read_user:' . (int)$auth['user_id'], 'profile-read', 240, 60);
    $viewerRole = null;
    $viewerId = null;
    $isOwner = false;
    $canExposeTakerContact = false;

    $ownerTakerId = resolveProfileIdForRole($db, $auth, 'taker');
    $isOwner = $ownerTakerId !== null && (int)$ownerTakerId === $takerId;

    $favoriteClientId = resolveProfileIdForRole($db, $auth, 'client');
    if ($favoriteClientId !== null) {
        $viewerRole = 'client';
        $viewerId = (int)$favoriteClientId;
    } elseif (in_array($auth['role'], ['client', 'taker'], true)) {
        $resolvedViewerId = resolveProfileIdForRole($db, $auth, $auth['role']);
        if ($resolvedViewerId !== null) {
            $viewerRole = $auth['role'];
            $viewerId = (int)$resolvedViewerId;
        }
    }

    $contactClientId = resolveProfileIdForRole($db, $auth, 'client');
    if ($contactClientId !== null) {
        $studioTrust = pc_studio_trust_summary($db, (int)$contactClientId);
        $canExposeTakerContact = !empty($studioTrust['can_book']);
    }

    $hasIsActive = tableHasColumn($db, 'takers', 'is_active');
    $hasAvgRating = tableHasColumn($db, 'takers', 'avg_rating');
    $hasReviewCount = tableHasColumn($db, 'takers', 'review_count');
    $hasIsFeatured = tableHasColumn($db, 'takers', 'is_featured');
    $hasProfileImageUrl = tableHasColumn($db, 'takers', 'profile_image_url');
    $hasProfileThumbUrl = tableHasColumn($db, 'takers', 'profile_thumb_url');
    $hasProfileImageScope = tableHasColumn($db, 'takers', 'profile_image_scope');
    $hasFavoritesTable = tableExists($db, 'taker_favorites');
    $hasPostsTable = tableExists($db, 'taker_posts');
    $takerNameColumn = firstExistingColumn($db, 'takers', ['full_name', 'name']) ?: 'full_name';
    $safeTakerNameColumn = '`' . str_replace('`', '``', $takerNameColumn) . '`';
    $contactPhoneSelect = ($isOwner || $canExposeTakerContact)
        ? "COALESCE(NULLIF(t.phone, ''), u.phone)"
        : 'NULL';
    $contactEmailSelect = $isOwner
        ? "COALESCE(NULLIF(t.email, ''), u.email)"
        : "''";

    $profileImageSelect = $hasProfileImageUrl ? 't.profile_image_url AS profile_image_url' : 'NULL AS profile_image_url';
    $profileThumbSelect = $hasProfileThumbUrl ? 't.profile_thumb_url AS profile_thumb_url' : 'NULL AS profile_thumb_url';
    $profileScopeSelect = $hasProfileImageScope ? 't.profile_image_scope AS profile_image_scope' : "'public' AS profile_image_scope";

    $favoriteStatsSelect = $hasFavoritesTable
        ? "(SELECT COUNT(*) FROM taker_favorites tf WHERE tf.taker_id=t.id) AS favorite_count"
        : "0 AS favorite_count";
    $viewerFavoriteSelect = ($hasFavoritesTable && $viewerRole !== null && $viewerId !== null)
        ? "EXISTS(SELECT 1 FROM taker_favorites vf WHERE vf.taker_id=t.id AND vf.actor_role=? AND vf.actor_id=?) AS viewer_has_favorited"
        : "0 AS viewer_has_favorited";
    $postStatsSelect = $hasPostsTable
        ? "(SELECT COUNT(*) FROM taker_posts tp WHERE tp.taker_id=t.id) AS post_count,
           (SELECT COUNT(*) FROM taker_posts tp WHERE tp.taker_id=t.id AND tp.created_at >= DATE_SUB(NOW(), INTERVAL 60 DAY)) AS active_post_count,
           (SELECT COALESCE(SUM(view_count), 0) FROM taker_posts tp WHERE tp.taker_id=t.id) AS total_view_count,
           (SELECT COALESCE(SUM(like_count), 0) FROM taker_posts tp WHERE tp.taker_id=t.id) AS total_like_count"
        : "0 AS post_count, 0 AS active_post_count, 0 AS total_view_count, 0 AS total_like_count";

    $where = ['t.id = ?'];
    $params = [];
    if ($hasFavoritesTable && $viewerRole !== null && $viewerId !== null) {
        $params[] = $viewerRole;
        $params[] = $viewerId;
    }
    $params[] = $takerId;
    if ($hasIsActive && !$isOwner) {
        $where[] = 't.is_active = 1';
    }
    if (!$isOwner && tableExists($db, 'admin_user_blocks')) {
        $where[] = 'NOT EXISTS (SELECT 1 FROM admin_user_blocks aub WHERE aub.user_id = t.user_id)';
    }

    $sql = "SELECT
                t.id,
                t.$safeTakerNameColumn AS full_name,
                $contactPhoneSelect AS phone,
                $contactEmailSelect AS email,
                t.pincode,
                " . ($isOwner ? 't.area' : "''") . " AS area,
                t.city,
                t.state,
                t.service_type,
                t.years_experience,
                t.languages,
                " . ($isOwner ? 't.instagram_url' : 'NULL') . " AS instagram_url,
                " . ($isOwner ? 't.youtube_url' : 'NULL') . " AS youtube_url,
                " . ($isOwner ? 't.portfolio_url' : 'NULL') . " AS portfolio_url,
                " . ($isOwner ? 't.social_link_additional1' : 'NULL') . " AS social_link_additional1,
                " . ($isOwner ? 't.social_link_additional2' : 'NULL') . " AS social_link_additional2,
                $profileImageSelect,
                $profileThumbSelect,
                $profileScopeSelect,
                " . ($hasAvgRating ? 't.avg_rating' : '0') . " AS avg_rating,
                " . ($hasReviewCount ? 't.review_count' : '0') . " AS review_count,
                " . ($hasIsFeatured ? 't.is_featured' : '0') . " AS is_featured,
                $favoriteStatsSelect,
                $viewerFavoriteSelect,
                $postStatsSelect
            FROM takers t
            LEFT JOIN users u ON u.id = t.user_id
            WHERE " . implode(' AND ', $where) . "
            LIMIT 1";

    $stmt = $db->prepare($sql);
    $stmt->execute($params);
    $rows = hydrateServiceTypes($db, $stmt->fetchAll());
    if (empty($rows)) {
        respond(false, 'Taker not found', [], 404);
    }

    $row = $rows[0];
    $scope = (string)($row['profile_image_scope'] ?? 'public');
    if ($scope !== 'public' && !$isOwner) {
        $row['profile_image_url'] = null;
        $row['profile_thumb_url'] = null;
    } else {
        $row['profile_image_url'] = normalizeDeliveredImageUrl($row['profile_image_url'] ?? null);
        $row['profile_thumb_url'] = normalizeDeliveredImageUrl($row['profile_thumb_url'] ?? null, 'thumb');
    }

    $trust = pc_taker_trust_summary($db, $takerId, $viewerRole, $viewerId);
    $row['trust_stage'] = $trust['stage'];
    $row['trust_label'] = $trust['label'];
    $row['identity_verified'] = $trust['identity_verified'];
    $row['portfolio_verified'] = $trust['portfolio_verified'];
    $row['social_verified'] = $trust['social_verified'];
    $row['completed_booking_count'] = (int)($trust['completed_booking_count'] ?? 0);
    $row['endorsement_count'] = (int)($trust['endorsement_count'] ?? 0);
    $row['favorite_count'] = (int)($row['favorite_count'] ?? 0);
    $row['viewer_has_favorited'] = (int)($row['viewer_has_favorited'] ?? 0) === 1;
    $row['post_count'] = (int)($row['post_count'] ?? 0);
    $row['active_post_count'] = (int)($row['active_post_count'] ?? 0);
    $row['post_reach'] = (int)($row['total_view_count'] ?? 0) + ((int)($row['total_like_count'] ?? 0) * 3);
    $row['is_top_taker'] = false;

    respond(true, 'OK', $row);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
