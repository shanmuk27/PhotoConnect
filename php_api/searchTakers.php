<?php
require_once 'config.php';
require_once 'trustHelpers.php';

const PC_SEARCH_MAX_RADIUS_KM = 100.0;
const PC_SEARCH_MIN_RADIUS_RESULTS = 5;

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    respond(false, 'Method not allowed', [], 405);
}

$location = trim((string)($_GET['location'] ?? ''));
$date = trim((string)($_GET['date'] ?? ''));
$serviceType = trim((string)($_GET['serviceType'] ?? ''));
$serviceTypes = pc_parse_service_types((string)($_GET['serviceTypes'] ?? ''), $serviceType);
$serviceMatchMode = strtolower(trim((string)($_GET['serviceMatchMode'] ?? 'smart')));
$trustFilter = strtolower(trim((string)($_GET['trustFilter'] ?? '')));
$respondsFastOnly = filter_var($_GET['respondsFastOnly'] ?? ($_GET['openNow'] ?? false), FILTER_VALIDATE_BOOLEAN);
$explainResults = filter_var($_GET['explain'] ?? true, FILTER_VALIDATE_BOOLEAN);
$availableOnly = filter_var($_GET['availableOnly'] ?? false, FILTER_VALIDATE_BOOLEAN);
$page = max(1, (int)($_GET['page'] ?? 1));
$limit = min(50, max(1, (int)($_GET['limit'] ?? 20)));
$offset = ($page - 1) * $limit;
$deviceLat = isset($_GET['lat']) && is_numeric($_GET['lat']) ? (float)$_GET['lat'] : null;
$deviceLon = isset($_GET['lon']) && is_numeric($_GET['lon']) ? (float)$_GET['lon'] : null;
$requestedRadiusKm = isset($_GET['radiusKm']) && is_numeric($_GET['radiusKm']) ? (float)$_GET['radiusKm'] : PC_SEARCH_MAX_RADIUS_KM;
$requestedSearchRadiusKm = min(PC_SEARCH_MAX_RADIUS_KM, max(1.0, $requestedRadiusKm));
$searchRadiusKm = $requestedSearchRadiusKm;

try {
    $db = getDB();
    ensureTrustSchema($db);
    $auth = requireAuthenticatedUser();
    rateLimit('search_user:' . (int)$auth['user_id'], 'search', 120, 60);
    rateLimit('search_ip:' . clientIpIdentifier(), 'search-ip', 300, 60);
    $viewerFavoriteRole = null;
    $viewerFavoriteId = null;
    $canExposeTakerContact = false;
    if ($auth) {
        $contactClientId = resolveProfileIdForRole($db, $auth, 'client');
        if ($contactClientId !== null) {
            $studioTrust = pc_studio_trust_summary($db, (int)$contactClientId);
            $canExposeTakerContact = !empty($studioTrust['can_book']);
        }
        $favoriteClientId = resolveProfileIdForRole($db, $auth, 'client');
        if ($favoriteClientId !== null) {
            $viewerFavoriteRole = 'client';
            $viewerFavoriteId = (int)$favoriteClientId;
        } elseif (in_array($auth['role'], ['client', 'taker'], true)) {
            $resolvedViewerId = resolveProfileIdForRole($db, $auth, $auth['role']);
            if ($resolvedViewerId !== null) {
                $viewerFavoriteRole = $auth['role'];
                $viewerFavoriteId = (int)$resolvedViewerId;
            }
        }
    }

    $hasIsActive = tableHasColumn($db, 'takers', 'is_active');
    $hasIsFeatured = tableHasColumn($db, 'takers', 'is_featured');
    $hasAvgRating = tableHasColumn($db, 'takers', 'avg_rating');
    $hasReviewCount = tableHasColumn($db, 'takers', 'review_count');
    $hasProfileImageUrl = tableHasColumn($db, 'takers', 'profile_image_url');
    $hasProfileThumbUrl = tableHasColumn($db, 'takers', 'profile_thumb_url');
    $hasProfileImageScope = tableHasColumn($db, 'takers', 'profile_image_scope');
    $hasLatitude = tableHasColumn($db, 'takers', 'latitude');
    $hasLongitude = tableHasColumn($db, 'takers', 'longitude');
    $hasServiceTypesTable = tableExists($db, 'taker_service_types');
    $hasLegacyServiceType = tableHasColumn($db, 'takers', 'service_type');
    $hasFavoritesTable = tableExists($db, 'taker_favorites');
    $hasPostsTable = tableExists($db, 'taker_posts');
    $takerNameColumn = firstExistingColumn($db, 'takers', ['full_name', 'name']) ?: 'full_name';
    $safeTakerNameColumn = '`' . str_replace('`', '``', $takerNameColumn) . '`';
    $contactPhoneSelect = $canExposeTakerContact
        ? "COALESCE(NULLIF(t.phone, ''), u.phone)"
        : 'NULL';
    pc_ensure_search_indexes($db, $hasLatitude && $hasLongitude, $hasServiceTypesTable, $hasLegacyServiceType, $hasAvgRating, $hasReviewCount);

    if ($date !== '' && !preg_match('/^\d{4}-\d{2}-\d{2}$/', $date)) {
        respond(false, 'Invalid date format', [], 422);
    }
    foreach ($serviceTypes as $candidateService) {
        if (!isAllowedServiceType($candidateService)) {
            respond(false, 'Invalid serviceType', [], 422);
        }
    }

    $searchGeo = $location !== '' ? pc_geocode_search($location) : null;
    $searchGeoContext = pc_extract_location_context($searchGeo);
    $hasSearchGeoContext = pc_location_context_has_values($searchGeoContext);
    $searchCenter = pc_search_center($searchGeo, $location);
    $queryRadiusKm = $searchCenter !== null ? PC_SEARCH_MAX_RADIUS_KM : $searchRadiusKm;

    $profileImageSelect = $hasProfileImageUrl
        ? ($hasProfileImageScope
            ? "CASE WHEN t.profile_image_scope='public' THEN t.profile_image_url ELSE NULL END AS profile_image_url"
            : 't.profile_image_url AS profile_image_url')
        : 'NULL AS profile_image_url';
    $profileThumbSelect = $hasProfileThumbUrl
        ? ($hasProfileImageScope
            ? "CASE WHEN t.profile_image_scope='public' THEN t.profile_thumb_url ELSE NULL END AS profile_thumb_url"
            : 't.profile_thumb_url AS profile_thumb_url')
        : 'NULL AS profile_thumb_url';

    $where = [];
    $joinParams = [];
    $whereParams = [];
    if ($hasIsActive) {
        $where[] = 't.is_active = 1';
    }

    $availabilitySelect = 'NULL AS is_available, NULL AS availability_status';
    $joinClauses = [];
    if ($date !== '') {
        $joinClauses[] = "LEFT JOIN availability a ON a.taker_id = t.id AND a.date = ?";
        $joinClauses[] = "LEFT JOIN bookings ba ON ba.taker_id = t.id AND ba.booking_date = ? AND ba.status IN ('Confirmed','Completed')";
        $joinParams[] = $date;
        $joinParams[] = $date;
        $availabilitySelect = "CASE
                                   WHEN (CASE
                                            WHEN a.status='Booked' AND ba.id IS NULL THEN 'Available'
                                            ELSE COALESCE(a.status, 'Not Available')
                                         END) = 'Available' THEN 1
                                   ELSE 0
                               END AS is_available,
                               (CASE
                                    WHEN a.status='Booked' AND ba.id IS NULL THEN 'Available'
                                    ELSE COALESCE(a.status, 'Not Available')
                                END) AS availability_status";
        if ($availableOnly) {
            $where[] = "(CASE
                            WHEN a.status='Booked' AND ba.id IS NULL THEN 'Available'
                            ELSE COALESCE(a.status, 'Not Available')
                         END) = 'Available'";
        }
    }

    if (!empty($serviceTypes)) {
        $servicePlaceholders = implode(',', array_fill(0, count($serviceTypes), '?'));
        if ($hasServiceTypesTable) {
            $where[] = 'EXISTS (
                SELECT 1
                FROM taker_service_types tst
                WHERE tst.taker_id = t.id AND tst.service_type IN (' . $servicePlaceholders . ')
            )';
        } elseif ($hasLegacyServiceType) {
            $where[] = 't.service_type IN (' . $servicePlaceholders . ')';
        }
        foreach ($serviceTypes as $candidateService) {
            $whereParams[] = $candidateService;
        }
    }

    // Keep the candidate set broad for location searches. Text and radius ranking happen
    // after hydration so misspelled or empty-area place searches can still fall back.
    if ($searchCenter !== null && $hasLatitude && $hasLongitude) {
        $box = pc_bounding_box($searchCenter['lat'], $searchCenter['lon'], $queryRadiusKm);
        $where[] = '((t.latitude IS NULL OR t.longitude IS NULL) OR (t.latitude BETWEEN ? AND ? AND t.longitude BETWEEN ? AND ?))';
        $whereParams[] = $box['min_lat'];
        $whereParams[] = $box['max_lat'];
        $whereParams[] = $box['min_lon'];
        $whereParams[] = $box['max_lon'];
    }

    if ($hasPostsTable) {
        $joinClauses[] = "LEFT JOIN (
            SELECT
                taker_id,
                COUNT(*) AS post_count,
                SUM(CASE WHEN created_at >= DATE_SUB(NOW(), INTERVAL 60 DAY) THEN 1 ELSE 0 END) AS active_post_count,
                COALESCE(SUM(view_count), 0) AS total_view_count,
                COALESCE(SUM(like_count), 0) AS total_like_count,
                MAX(created_at) AS last_post_at
            FROM taker_posts
            GROUP BY taker_id
        ) ps ON ps.taker_id = t.id";
    }

    if (tableExists($db, 'admin_user_blocks')) {
        $joinClauses[] = "LEFT JOIN admin_user_blocks aub ON aub.user_id = t.user_id";
        $where[] = 'aub.user_id IS NULL';
    }
    $joinClauses[] = "LEFT JOIN taker_verifications tv ON tv.taker_id = t.id";
    $joinClauses[] = "LEFT JOIN (
        SELECT taker_id, COUNT(*) AS completed_booking_count
        FROM bookings
        WHERE status='Completed'
        GROUP BY taker_id
    ) bcs ON bcs.taker_id = t.id";
    $joinClauses[] = "LEFT JOIN (
        SELECT taker_id, COUNT(*) AS endorsement_count
        FROM taker_endorsements
        GROUP BY taker_id
    ) tes ON tes.taker_id = t.id";
    $joinClauses[] = "LEFT JOIN (
        SELECT
            taker_id,
            COUNT(*) AS responded_booking_count,
            AVG(GREATEST(0, TIMESTAMPDIFF(MINUTE, created_at, updated_at))) AS avg_response_minutes
        FROM bookings
        WHERE status IN ('Confirmed','Cancelled','Completed')
        GROUP BY taker_id
    ) brs ON brs.taker_id = t.id";

    if ($hasFavoritesTable) {
        $joinClauses[] = "LEFT JOIN (
            SELECT taker_id, COUNT(*) AS favorite_count
            FROM taker_favorites
            GROUP BY taker_id
        ) fs ON fs.taker_id = t.id";
        if ($viewerFavoriteRole !== null && $viewerFavoriteId !== null) {
            $joinClauses[] = "LEFT JOIN taker_favorites vf
                              ON vf.taker_id = t.id
                             AND vf.actor_role = ?
                             AND vf.actor_id = ?";
            $joinParams[] = $viewerFavoriteRole;
            $joinParams[] = $viewerFavoriteId;
        }
    }

    if ($viewerFavoriteRole !== null && $viewerFavoriteId !== null && tableExists($db, 'search_events')) {
        $joinClauses[] = "LEFT JOIN (
            SELECT
                taker_id,
                SUM(CASE WHEN event_type='click' THEN 1 ELSE 0 END) AS viewer_search_click_count,
                SUM(CASE WHEN event_type='favorite' THEN 1 ELSE 0 END) AS viewer_search_favorite_count,
                SUM(CASE WHEN event_type='booking' THEN 1 ELSE 0 END) AS viewer_search_booking_count,
                MAX(created_at) AS viewer_last_search_interaction_at
            FROM search_events
            WHERE actor_role = ? AND actor_id = ? AND taker_id IS NOT NULL
            GROUP BY taker_id
        ) ses ON ses.taker_id = t.id";
        $joinParams[] = $viewerFavoriteRole;
        $joinParams[] = $viewerFavoriteId;
    }

    $postStatsSelect = $hasPostsTable
        ? 'COALESCE(ps.post_count, 0) AS post_count,
           COALESCE(ps.active_post_count, 0) AS active_post_count,
           COALESCE(ps.total_view_count, 0) AS total_view_count,
           COALESCE(ps.total_like_count, 0) AS total_like_count,
           ps.last_post_at AS last_post_at'
        : '0 AS post_count,
           0 AS active_post_count,
           0 AS total_view_count,
           0 AS total_like_count,
           NULL AS last_post_at';
    $favoriteStatsSelect = $hasFavoritesTable
        ? 'COALESCE(fs.favorite_count, 0) AS favorite_count,
           ' . ($viewerFavoriteRole !== null && $viewerFavoriteId !== null ? 'CASE WHEN vf.id IS NULL THEN 0 ELSE 1 END' : '0') . ' AS viewer_has_favorited'
        : '0 AS favorite_count, 0 AS viewer_has_favorited';
    $personalizationSelect = ($viewerFavoriteRole !== null && $viewerFavoriteId !== null && tableExists($db, 'search_events'))
        ? 'COALESCE(ses.viewer_search_click_count, 0) AS viewer_search_click_count,
           COALESCE(ses.viewer_search_favorite_count, 0) AS viewer_search_favorite_count,
           COALESCE(ses.viewer_search_booking_count, 0) AS viewer_search_booking_count,
           ses.viewer_last_search_interaction_at AS viewer_last_search_interaction_at'
        : '0 AS viewer_search_click_count,
           0 AS viewer_search_favorite_count,
           0 AS viewer_search_booking_count,
           NULL AS viewer_last_search_interaction_at';

    $latitudeSelect = $hasLatitude ? 't.latitude' : 'NULL';
    $longitudeSelect = $hasLongitude ? 't.longitude' : 'NULL';
    $legacyServiceSelect = $hasLegacyServiceType ? 't.service_type' : "'other'";
    $profileImageScopeSelect = $hasProfileImageScope ? 't.profile_image_scope' : "'public'";

    $sql = "SELECT
                t.id,
                t.$safeTakerNameColumn AS full_name,
                $contactPhoneSelect AS phone,
                '' AS email,
                t.pincode,
                t.area,
                t.city,
                t.state,
                $latitudeSelect AS latitude,
                $longitudeSelect AS longitude,
                $legacyServiceSelect AS service_type,
                t.years_experience,
                t.languages,
                NULL AS instagram_url,
                NULL AS youtube_url,
                NULL AS portfolio_url,
                NULL AS social_link_additional1,
                NULL AS social_link_additional2,
                $profileImageScopeSelect AS profile_image_scope,
                $profileImageSelect,
                $profileThumbSelect,
                " . ($hasAvgRating ? 't.avg_rating' : '0') . " AS avg_rating,
                " . ($hasReviewCount ? 't.review_count' : '0') . " AS review_count,
                " . ($hasIsFeatured ? 't.is_featured' : '0') . " AS is_featured,
                $availabilitySelect,
                $postStatsSelect,
                $favoriteStatsSelect,
                $personalizationSelect,
                COALESCE(tv.aadhaar_status, 'not_submitted') AS aadhaar_status,
                COALESCE(tv.portfolio_status, 'not_submitted') AS portfolio_status,
                COALESCE(tv.social_status, 'not_submitted') AS social_status,
                COALESCE(bcs.completed_booking_count, 0) AS completed_booking_count,
                COALESCE(tes.endorsement_count, 0) AS endorsement_count,
                COALESCE(brs.responded_booking_count, 0) AS responded_booking_count,
                COALESCE(brs.avg_response_minutes, 999999) AS avg_response_minutes
            FROM takers t
            LEFT JOIN users u ON u.id = t.user_id
            " . implode("\n", $joinClauses) . '
            ' . (!empty($where) ? 'WHERE ' . implode(' AND ', $where) : '');

    $stmt = $db->prepare($sql);
    $stmt->execute(array_merge($joinParams, $whereParams));
    $rows = hydrateServiceTypes($db, $stmt->fetchAll());

    $deviceGeo = ($deviceLat !== null && $deviceLon !== null) ? pc_reverse_geocode($deviceLat, $deviceLon) : null;
    $searchContext = pc_merge_location_context(
        $searchGeoContext,
        pc_extract_text_location_context($rows, $location)
    );
    $deviceContext = pc_merge_location_context(
        pc_extract_location_context($deviceGeo),
        ($deviceLat !== null && $deviceLon !== null) ? pc_coordinate_location_context($deviceLat, $deviceLon) : []
    );

    $scoredRows = [];
    foreach ($rows as $row) {
        $row['profile_image_url'] = normalizeDeliveredImageUrl($row['profile_image_url'] ?? null);
        $row['profile_thumb_url'] = normalizeDeliveredImageUrl($row['profile_thumb_url'] ?? null, 'thumb');
        if (!$canExposeTakerContact) {
            $row['phone'] = null;
        }
        $textScore = pc_score_search_text($row, $location);
        $contextSearchTier = $location !== '' ? pc_location_proximity_tier($row, $searchContext) : 99;
        $contextDeviceTier = !empty($deviceContext) ? pc_location_proximity_tier($row, $deviceContext) : 99;
        $coords = null;
        if (
            ($deviceLat !== null && $deviceLon !== null) ||
            ($location !== '' && is_array($searchGeo) && isset($searchGeo['lat'], $searchGeo['lon']))
        ) {
            $coords = pc_taker_coordinates($db, $row, $hasLatitude && $hasLongitude);
        }
        $deviceDistanceKm = null;
        if ($coords !== null && $deviceLat !== null && $deviceLon !== null) {
            $deviceDistanceKm = pc_haversine_km($deviceLat, $deviceLon, $coords['lat'], $coords['lon']);
        }
        $searchDistanceKm = null;
        if ($coords !== null && $location !== '' && is_array($searchGeo) && isset($searchGeo['lat'], $searchGeo['lon'])) {
            $searchDistanceKm = pc_haversine_km((float)$searchGeo['lat'], (float)$searchGeo['lon'], $coords['lat'], $coords['lon']);
        }
        $activeDistanceKm = $location !== '' ? $searchDistanceKm : $deviceDistanceKm;
        if ($searchCenter !== null) {
            if ($activeDistanceKm === null || $activeDistanceKm > $queryRadiusKm) {
                continue;
            }
        }
        $deviceDistanceTier = pc_distance_proximity_tier($deviceDistanceKm);
        $searchDistanceTier = pc_distance_proximity_tier($searchDistanceKm);
        $searchTier = min($contextSearchTier, $searchDistanceTier);
        $deviceTier = min($contextDeviceTier, $deviceDistanceTier);
        $trust = pc_taker_trust_from_row($row);
        $row['trust_stage'] = $trust['stage'];
        $row['trust_label'] = $trust['label'];
        $row['identity_verified'] = $trust['identity_verified'];
        $row['portfolio_verified'] = $trust['portfolio_verified'];
        $row['social_verified'] = $trust['social_verified'];
        $row['completed_booking_count'] = $trust['completed_booking_count'];
        $row['endorsement_count'] = $trust['endorsement_count'];

        $row['favorite_count'] = (int)($row['favorite_count'] ?? 0);
        $row['viewer_has_favorited'] = (int)($row['viewer_has_favorited'] ?? 0) === 1;
        $row['post_count'] = (int)($row['post_count'] ?? 0);
        $row['active_post_count'] = (int)($row['active_post_count'] ?? 0);
        $row['total_view_count'] = (int)($row['total_view_count'] ?? 0);
        $row['total_like_count'] = (int)($row['total_like_count'] ?? 0);
        $row['post_reach'] = $row['total_view_count'] + ($row['total_like_count'] * 3);
        $row['viewer_search_click_count'] = (int)($row['viewer_search_click_count'] ?? 0);
        $row['viewer_search_favorite_count'] = (int)($row['viewer_search_favorite_count'] ?? 0);
        $row['viewer_search_booking_count'] = (int)($row['viewer_search_booking_count'] ?? 0);
        $row['matched_service_count'] = pc_matched_service_count($row, $serviceTypes);
        $row['service_match_mode'] = pc_effective_service_match_mode($serviceMatchMode, $location, $serviceTypes);
        $row['service_match_label'] = pc_service_match_label($row['matched_service_count'], count($serviceTypes));
        if (!pc_row_matches_service_mode($row, $serviceTypes, $serviceMatchMode, $location)) {
            continue;
        }
        $row['search_text_score'] = $textScore;
        $row['search_proximity_tier'] = $searchTier;
        $row['device_proximity_tier'] = $deviceTier;
        $row['distance_km'] = $activeDistanceKm !== null
            ? round((float)$activeDistanceKm, 1)
            : null;
        $row['search_distance_km'] = $searchDistanceKm !== null ? round($searchDistanceKm, 1) : null;
        $row['device_distance_km'] = $deviceDistanceKm !== null ? round($deviceDistanceKm, 1) : null;
        $row['proximity_label'] = pc_proximity_label(
            $location !== '' ? $searchTier : $deviceTier,
            $activeDistanceKm
        );
        $row['responded_booking_count'] = (int)($row['responded_booking_count'] ?? 0);
        $row['avg_response_minutes'] = (float)($row['avg_response_minutes'] ?? 999999);
        $row['responds_fast'] = pc_responds_fast($row);
        if ($respondsFastOnly && !$row['responds_fast']) {
            continue;
        }
        if ($trustFilter !== '' && !pc_row_matches_trust_filter($row, $trustFilter)) {
            continue;
        }
        $rankingScore = pc_compute_taker_ranking_score($row, $deviceTier, $searchTier, $textScore, $activeDistanceKm);
        $row['ranking_score'] = round($rankingScore, 2);
        if ($explainResults) {
            $row['search_explanation'] = pc_search_explanation($row, $serviceTypes, $date !== '', $location !== '');
        }
        $scoredRows[] = $row;
    }

    $searchRadiusKm = pc_choose_search_radius($scoredRows, $searchCenter !== null, $requestedSearchRadiusKm);
    $autoExpanded = $searchRadiusKm > ($requestedSearchRadiusKm + 0.1);
    $radiusRows = $searchCenter !== null
        ? array_values(array_filter($scoredRows, static function (array $row) use ($searchRadiusKm): bool {
            $distance = $row['search_distance_km'] ?? $row['distance_km'] ?? null;
            return is_numeric($distance) && (float)$distance <= $searchRadiusKm;
        }))
        : $scoredRows;

    $candidateRows = pc_filter_search_candidates($radiusRows, $location, $limit, $searchCenter !== null);
    usort($candidateRows, 'pc_compare_taker_rows');

    foreach ($candidateRows as &$row) {
        $nearTier = min((int)($row['search_proximity_tier'] ?? 99), (int)($row['device_proximity_tier'] ?? 99));
        $hasTrustSignal = ((int)($row['review_count'] ?? 0) > 0) || ((int)($row['post_count'] ?? 0) > 0);
        $row['is_top_taker'] = $hasTrustSignal && $nearTier <= 2 && (float)($row['ranking_score'] ?? 0.0) >= 45.0;
    }
    unset($row);

    $total = count($candidateRows);
    $pagedRows = array_slice($candidateRows, $offset, $limit);
    $featured = array_slice($candidateRows, 0, min(10, count($candidateRows)));
    $nearbyAlternatives = pc_nearby_alternatives($scoredRows, $candidateRows, $location);
    $explanation = pc_result_explanation($location, $total, $requestedSearchRadiusKm, $searchRadiusKm, $autoExpanded, $serviceTypes, $trustFilter, $respondsFastOnly);
    respond(true, 'OK', [
        'takers' => array_values(array_map('pc_sanitize_taker_search_row', $pagedRows)),
        'featured' => array_values(array_map('pc_sanitize_taker_search_row', $featured)),
        'total' => $total,
        'page' => $page,
        'total_pages' => (int)max(1, ceil($total / max(1, $limit))),
        'search_radius_km' => $searchRadiusKm,
        'requested_radius_km' => $requestedSearchRadiusKm,
        'max_radius_km' => PC_SEARCH_MAX_RADIUS_KM,
        'auto_expanded_radius' => $autoExpanded,
        'result_explanation' => $explanation,
        'nearby_alternatives' => $nearbyAlternatives,
    ]);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}

function pc_sanitize_taker_search_row(array $row): array
{
    foreach ([
        'user_id',
        'password_hash',
        'refresh_token_hash',
        'refresh_token_expires_at',
        'google_id',
        'latitude',
        'longitude',
        'geo_updated_at',
        'geo_source',
        'aadhaar_status',
        'portfolio_status',
        'social_status',
        'viewer_search_click_count',
        'viewer_search_favorite_count',
        'viewer_search_booking_count',
        'viewer_last_search_interaction_at',
        'total_view_count',
        'total_like_count',
        'last_post_at',
    ] as $key) {
        unset($row[$key]);
    }

    $row['email'] = '';
    $row['area'] = '';
    if (array_key_exists('phone', $row) && !is_string($row['phone'])) {
        $row['phone'] = null;
    }
    return $row;
}

function pc_ensure_search_indexes(
    PDO $db,
    bool $hasGeoColumns,
    bool $hasServiceTypesTable,
    bool $hasLegacyServiceType,
    bool $hasAvgRating,
    bool $hasReviewCount
): void {
    static $done = false;
    if (!API_INDEX_BOOTSTRAP_ENABLED) {
        return;
    }
    if ($done) {
        return;
    }
    $done = true;

    if ($hasGeoColumns) {
        pc_create_index_if_missing($db, 'takers', 'idx_takers_geo_search', ['latitude', 'longitude']);
    }
    pc_create_index_if_missing($db, 'takers', 'idx_takers_city_area_pin', ['city', 'area', 'pincode']);
    if (tableHasColumn($db, 'takers', 'is_active')) {
        pc_create_index_if_missing($db, 'takers', 'idx_takers_active_city', ['is_active', 'city']);
    }
    if ($hasAvgRating && $hasReviewCount) {
        pc_create_index_if_missing($db, 'takers', 'idx_takers_rating_popularity', ['avg_rating', 'review_count']);
    }
    if ($hasLegacyServiceType) {
        pc_create_index_if_missing($db, 'takers', 'idx_takers_service_type', ['service_type']);
    }
    if ($hasServiceTypesTable) {
        pc_create_index_if_missing($db, 'taker_service_types', 'idx_taker_services_lookup', ['service_type', 'taker_id']);
    }
}

function pc_parse_service_types(string $rawServices, string $legacyService): array
{
    $parts = [];
    if (trim($rawServices) !== '') {
        $parts = preg_split('/[,\s]+/', $rawServices) ?: [];
    }
    if ($legacyService !== '') {
        $parts[] = $legacyService;
    }
    return array_values(array_unique(array_filter(array_map(static function ($service): string {
        return trim((string)$service);
    }, $parts))));
}

function pc_effective_service_match_mode(string $mode, string $location, array $serviceTypes): string
{
    if (count($serviceTypes) <= 1) {
        return 'any';
    }
    if (in_array($mode, ['any', 'all'], true)) {
        return $mode;
    }
    $normalized = pc_normalize_search_key($location);
    return preg_match('/\b(and|with|plus|\+)\b/', $normalized) === 1 ? 'all' : 'smart';
}

function pc_matched_service_count(array $row, array $serviceTypes): int
{
    if (empty($serviceTypes)) {
        return 0;
    }
    $offered = [];
    if (isset($row['service_types']) && is_array($row['service_types'])) {
        $offered = $row['service_types'];
    } elseif (!empty($row['service_type'])) {
        $offered = [(string)$row['service_type']];
    }
    return count(array_intersect($serviceTypes, $offered));
}

function pc_row_matches_service_mode(array $row, array $serviceTypes, string $mode, string $location): bool
{
    if (empty($serviceTypes)) {
        return true;
    }
    $matched = (int)($row['matched_service_count'] ?? 0);
    if ($matched <= 0) {
        return false;
    }
    return pc_effective_service_match_mode($mode, $location, $serviceTypes) === 'all'
        ? $matched >= count($serviceTypes)
        : true;
}

function pc_service_match_label(int $matched, int $requested): string
{
    if ($requested <= 0) {
        return '';
    }
    if ($matched >= $requested) {
        return $requested === 1 ? 'Matches service' : 'Matches all services';
    }
    return 'Matches ' . $matched . ' of ' . $requested . ' services';
}

function pc_responds_fast(array $row): bool
{
    $count = (int)($row['responded_booking_count'] ?? 0);
    $avgMinutes = (float)($row['avg_response_minutes'] ?? 999999);
    return $count >= 1 && $avgMinutes <= 24 * 60;
}

function pc_row_matches_trust_filter(array $row, string $trustFilter): bool
{
    $stage = strtolower((string)($row['trust_stage'] ?? 'unverified'));
    return match ($trustFilter) {
        'verified' => in_array($stage, ['verified', 'trusted', 'pro_verified'], true),
        'trusted' => in_array($stage, ['trusted', 'pro_verified'], true),
        'pro_verified' => $stage === 'pro_verified',
        default => true,
    };
}

function pc_choose_search_radius(array $rows, bool $hasSearchCenter, float $requestedRadiusKm): float
{
    if (!$hasSearchCenter) {
        return $requestedRadiusKm;
    }
    foreach ([10.0, 25.0, 50.0, 75.0, PC_SEARCH_MAX_RADIUS_KM] as $radius) {
        if ($radius + 0.1 < $requestedRadiusKm) {
            continue;
        }
        $count = 0;
        foreach ($rows as $row) {
            $distance = $row['search_distance_km'] ?? $row['distance_km'] ?? null;
            if (is_numeric($distance) && (float)$distance <= $radius) {
                $count++;
                if ($count >= PC_SEARCH_MIN_RADIUS_RESULTS) {
                    return $radius;
                }
            }
        }
    }
    return PC_SEARCH_MAX_RADIUS_KM;
}

function pc_search_explanation(array $row, array $serviceTypes, bool $hasDateFilter, bool $hasLocation): string
{
    $parts = [];
    $serviceLabel = (string)($row['service_match_label'] ?? '');
    if ($serviceLabel !== '') {
        $parts[] = $serviceLabel;
    } elseif (!empty($serviceTypes)) {
        $parts[] = 'Relevant service match';
    }
    if ($hasLocation && isset($row['distance_km']) && is_numeric($row['distance_km'])) {
        $parts[] = round((float)$row['distance_km']) . ' km away';
    } elseif (!empty($row['proximity_label'])) {
        $parts[] = (string)$row['proximity_label'];
    }
    if (in_array((string)($row['trust_stage'] ?? ''), ['verified', 'trusted', 'pro_verified'], true)) {
        $parts[] = (string)($row['trust_label'] ?? 'Verified');
    }
    if ((float)($row['avg_rating'] ?? 0) >= 4.0) {
        $parts[] = 'highly rated';
    }
    if (!empty($row['responds_fast'])) {
        $parts[] = 'responds fast';
    }
    if ((int)($row['viewer_search_click_count'] ?? 0) > 0 || !empty($row['viewer_has_favorited'])) {
        $parts[] = 'matches your activity';
    }
    if ($hasDateFilter && (int)($row['is_available'] ?? 0) === 1) {
        $parts[] = 'available on selected date';
    }
    return ucfirst(implode(', ', array_slice(array_filter($parts), 0, 4))) . '.';
}

function pc_nearby_alternatives(array $allRows, array $candidateRows, string $location): array
{
    if ($location === '') {
        return [];
    }
    $candidateIds = [];
    foreach ($candidateRows as $row) {
        $candidateIds[(int)($row['id'] ?? 0)] = true;
    }
    $groups = [];
    foreach ($allRows as $row) {
        $id = (int)($row['id'] ?? 0);
        $city = trim((string)($row['city'] ?? ''));
        if ($city === '' || isset($candidateIds[$id]) || strcasecmp($city, $location) === 0) {
            continue;
        }
        $distance = $row['search_distance_km'] ?? $row['distance_km'] ?? null;
        if (!is_numeric($distance)) {
            continue;
        }
        $key = strtolower($city);
        if (!isset($groups[$key])) {
            $groups[$key] = [
                'title' => $city,
                'subtitle' => trim((string)($row['state'] ?? '')),
                'distance_km' => round((float)$distance, 1),
                'creator_count' => 0,
                'query' => $city,
            ];
        }
        $groups[$key]['creator_count']++;
        $groups[$key]['distance_km'] = min((float)$groups[$key]['distance_km'], round((float)$distance, 1));
    }
    usort($groups, static fn(array $a, array $b): int => ((float)$a['distance_km']) <=> ((float)$b['distance_km']));
    return array_slice(array_values($groups), 0, 5);
}

function pc_result_explanation(
    string $location,
    int $total,
    float $requestedRadiusKm,
    float $appliedRadiusKm,
    bool $autoExpanded,
    array $serviceTypes,
    string $trustFilter,
    bool $respondsFastOnly
): string {
    $place = trim($location);
    $parts = [];
    if ($place !== '') {
        $parts[] = 'Showing ' . $total . ' creator' . ($total === 1 ? '' : 's') . ' near ' . $place . ' within ' . round($appliedRadiusKm) . ' km';
        if ($autoExpanded) {
            $parts[] = 'expanded from ' . round($requestedRadiusKm) . ' km to find enough useful options';
        }
    } else {
        $parts[] = 'Showing recommended creators ranked by relevance';
    }
    if (!empty($serviceTypes)) {
        $parts[] = count($serviceTypes) === 1 ? 'filtered by service' : 'matched across multiple services';
    }
    if ($trustFilter !== '') {
        $parts[] = 'trust filter applied';
    }
    if ($respondsFastOnly) {
        $parts[] = 'responds fast filter applied';
    }
    return implode('; ', $parts) . '.';
}

function pc_create_index_if_missing(PDO $db, string $tableName, string $indexName, array $columns): void
{
    if (empty($columns) || !tableExists($db, $tableName) || pc_index_exists($db, $tableName, $indexName)) {
        return;
    }

    foreach ($columns as $column) {
        if (!tableHasColumn($db, $tableName, $column)) {
            return;
        }
    }

    $columnSql = implode(', ', array_map(static fn(string $column): string => '`' . str_replace('`', '``', $column) . '`', $columns));
    $tableSql = '`' . str_replace('`', '``', $tableName) . '`';
    $indexSql = '`' . str_replace('`', '``', $indexName) . '`';
    try {
        $db->exec("CREATE INDEX $indexSql ON $tableSql ($columnSql)");
    } catch (Throwable $e) {
        pc_log_runtime_error('Search index creation skipped: ' . $e->getMessage());
    }
}

function pc_index_exists(PDO $db, string $tableName, string $indexName): bool
{
    try {
        $stmt = $db->prepare(
            'SELECT COUNT(*)
             FROM information_schema.statistics
             WHERE table_schema = ? AND table_name = ? AND index_name = ?'
        );
        $stmt->execute([DB_NAME, $tableName, $indexName]);
        return ((int)$stmt->fetchColumn()) > 0;
    } catch (Throwable $e) {
        return true;
    }
}

function pc_search_center(?array $searchGeo, string $location): ?array
{
    if ($location !== '') {
        if (is_array($searchGeo) && isset($searchGeo['lat'], $searchGeo['lon'])) {
            return ['lat' => (float)$searchGeo['lat'], 'lon' => (float)$searchGeo['lon']];
        }
        return null;
    }
    return null;
}

function pc_bounding_box(float $lat, float $lon, float $radiusKm): array
{
    $latDelta = $radiusKm / 111.32;
    $lonDelta = $radiusKm / max(1.0, 111.32 * cos(deg2rad($lat)));
    return [
        'min_lat' => max(-90.0, $lat - $latDelta),
        'max_lat' => min(90.0, $lat + $latDelta),
        'min_lon' => max(-180.0, $lon - $lonDelta),
        'max_lon' => min(180.0, $lon + $lonDelta),
    ];
}

function pc_extract_location_context(?array $geo): array
{
    if (!is_array($geo)) {
        return [];
    }

    $address = is_array($geo['address'] ?? null) ? $geo['address'] : [];
    return [
        'pincodes' => pc_location_tokens((string)($address['postcode'] ?? '')),
        'areas' => pc_location_tokens(
            (string)($address['suburb'] ?? ''),
            (string)($address['neighbourhood'] ?? ''),
            (string)($address['quarter'] ?? ''),
            (string)($address['residential'] ?? ''),
            (string)($address['hamlet'] ?? ''),
            (string)($address['city_district'] ?? ''),
            (string)($address['locality'] ?? ''),
            (string)($address['road'] ?? '')
        ),
        'cities' => pc_location_tokens(
            (string)($address['city'] ?? ''),
            (string)($address['town'] ?? ''),
            (string)($address['village'] ?? ''),
            (string)($address['municipality'] ?? '')
        ),
        'districts' => pc_location_tokens(
            (string)($address['county'] ?? ''),
            (string)($address['state_district'] ?? ''),
            (string)($address['district'] ?? '')
        ),
        'states' => pc_location_tokens((string)($address['state'] ?? '')),
    ];
}

function pc_location_context_has_values(array $context): bool
{
    foreach (['pincodes', 'areas', 'cities', 'districts', 'states'] as $key) {
        if (!empty($context[$key])) {
            return true;
        }
    }
    return false;
}

function pc_extract_text_location_context(array $rows, string $query): array
{
    $query = pc_normalize_search_key($query);
    $queryVariants = pc_location_query_variants($query);
    $context = [
        'pincodes' => [],
        'areas' => [],
        'cities' => [],
        'districts' => [],
        'states' => [],
    ];
    if ($query === '') {
        return $context;
    }

    foreach ($rows as $row) {
        $name = pc_normalize_search_key((string)($row['full_name'] ?? ''));
        $area = pc_normalize_search_key((string)($row['area'] ?? ''));
        $city = pc_normalize_search_key((string)($row['city'] ?? ''));
        $state = pc_normalize_search_key((string)($row['state'] ?? ''));
        $pincode = pc_normalize_search_key((string)($row['pincode'] ?? ''));

        $locationMatched = false;
        foreach ($queryVariants as $variant) {
            $locationMatched = $locationMatched ||
                ($pincode !== '' && $pincode === $variant) ||
                ($area !== '' && (pc_string_contains($area, $variant) || pc_string_contains($variant, $area))) ||
                ($city !== '' && (pc_string_contains($city, $variant) || pc_string_contains($variant, $city))) ||
                ($state !== '' && $state === $variant);
        }

        // A name-only search should not pull in unrelated creators from the same city.
        if (!$locationMatched || ($name !== '' && pc_any_contains($name, $queryVariants) && !in_array($area, $queryVariants, true) && !in_array($city, $queryVariants, true) && !in_array($pincode, $queryVariants, true))) {
            continue;
        }

        $context['pincodes'] = array_merge($context['pincodes'], pc_location_tokens((string)($row['pincode'] ?? '')));
        $context['areas'] = array_merge($context['areas'], pc_location_tokens((string)($row['area'] ?? '')));
        $context['cities'] = array_merge($context['cities'], pc_location_tokens((string)($row['city'] ?? '')));
        $context['states'] = array_merge($context['states'], pc_location_tokens((string)($row['state'] ?? '')));
    }

    foreach ($context as $key => $values) {
        $context[$key] = array_values(array_unique(array_filter($values)));
    }
    return $context;
}

function pc_merge_location_context(array ...$contexts): array
{
    $merged = [
        'pincodes' => [],
        'areas' => [],
        'cities' => [],
        'districts' => [],
        'states' => [],
    ];
    foreach ($contexts as $context) {
        foreach ($merged as $key => $values) {
            $merged[$key] = array_merge($merged[$key], $context[$key] ?? []);
        }
    }
    foreach ($merged as $key => $values) {
        $merged[$key] = array_values(array_unique(array_filter($values)));
    }
    return $merged;
}

function pc_coordinate_location_context(float $lat, float $lon): array
{
    $context = [
        'pincodes' => [],
        'areas' => [],
        'cities' => [],
        'districts' => [],
        'states' => [],
    ];

    if ($lat >= 16.20 && $lat <= 16.65 && $lon >= 80.30 && $lon <= 80.85) {
        $context['areas'] = pc_location_tokens('Mangalagiri', 'Tadepalli', 'Undavalli');
        $context['cities'] = pc_location_tokens('Mangalagiri', 'Tadepalli', 'Vijayawada', 'Guntur');
        $context['districts'] = pc_location_tokens('Guntur', 'NTR');
        $context['states'] = pc_location_tokens('Andhra Pradesh');
    } elseif ($lat >= 17.55 && $lat <= 17.95 && $lon >= 83.05 && $lon <= 83.55) {
        $context['areas'] = pc_location_tokens('Visakhapatnam', 'Vizag', 'Waltair');
        $context['cities'] = pc_location_tokens('Visakhapatnam', 'Vizag', 'Visakapatnam', 'Vishakhapatnam');
        $context['districts'] = pc_location_tokens('Visakhapatnam');
        $context['states'] = pc_location_tokens('Andhra Pradesh');
    }

    return $context;
}

function pc_score_search_text(array $row, string $query): int
{
    $query = pc_normalize_search_key($query);
    if ($query === '') {
        return 0;
    }
    
    $tokens = array_filter(explode(' ', $query));
    if (empty($tokens)) {
        return 0;
    }
    
    $totalScore = 0;

    $name = pc_normalize_search_key((string)($row['full_name'] ?? ''));
    $area = pc_normalize_search_key((string)($row['area'] ?? ''));
    $city = pc_normalize_search_key((string)($row['city'] ?? ''));
    $state = pc_normalize_search_key((string)($row['state'] ?? ''));
    $pincode = pc_normalize_search_key((string)($row['pincode'] ?? ''));

    $serviceTypes = [];
    if (isset($row['service_types']) && is_array($row['service_types'])) {
        foreach ($row['service_types'] as $st) {
            $serviceTypes[] = pc_normalize_search_key(str_replace('_', ' ', $st));
        }
    } elseif (!empty($row['service_type'])) {
        $serviceTypes[] = pc_normalize_search_key(str_replace('_', ' ', $row['service_type']));
    }

    foreach ($tokens as $token) {
        $tokenVariants = pc_location_query_variants($token);
        $tokenScore = 0;
        
        foreach ($tokenVariants as $variant) {
            if ($name === $variant) {
                $tokenScore = max($tokenScore, 140);
            } elseif ($name !== '' && pc_string_starts_with($name, $variant)) {
                $tokenScore = max($tokenScore, 110);
            } elseif ($name !== '' && pc_string_contains($name, $variant)) {
                $tokenScore = max($tokenScore, 85);
            }

            if ($pincode !== '' && $pincode === $variant) {
                $tokenScore = max($tokenScore, 130);
            }

            foreach ([$area, $city, $state] as $value) {
                if ($value === '') {
                    continue;
                }
                if ($value === $variant) {
                    $tokenScore = max($tokenScore, 120);
                } elseif (pc_string_starts_with($value, $variant)) {
                    $tokenScore = max($tokenScore, 90);
                } elseif (pc_string_contains($value, $variant)) {
                    $tokenScore = max($tokenScore, 70);
                }
            }
            
            foreach ($serviceTypes as $st) {
                if ($st === $variant) {
                    $tokenScore = max($tokenScore, 100);
                } elseif (pc_string_contains($st, $variant)) {
                    $tokenScore = max($tokenScore, 80);
                }
            }
        }
        $totalScore += $tokenScore;
    }

    return $totalScore;
}

function pc_location_query_variants(string $query): array
{
    $query = pc_normalize_search_key($query);
    if ($query === '') {
        return [];
    }
    $variants = [$query];
    $groups = [
        ['vizag', 'visakhapatnam', 'visakapatnam', 'vishakhapatnam', 'waltair'],
        ['mangalagiri', 'mangalagiti', 'mangalagir'],
    ];
    foreach ($groups as $group) {
        if (in_array($query, $group, true)) {
            $variants = array_merge($variants, $group);
            break;
        }
    }
    return array_values(array_unique(array_filter($variants)));
}

function pc_any_contains(string $haystack, array $needles): bool
{
    foreach ($needles as $needle) {
        if ($needle !== '' && pc_string_contains($haystack, $needle)) {
            return true;
        }
    }
    return false;
}

function pc_location_proximity_tier(array $row, array $context): int
{
    if (empty($context)) {
        return 99;
    }

    $rowPincodes = pc_location_tokens((string)($row['pincode'] ?? ''));
    $rowAreas = pc_location_tokens((string)($row['area'] ?? ''));
    $rowCities = pc_location_tokens((string)($row['city'] ?? ''));
    $rowStates = pc_location_tokens((string)($row['state'] ?? ''));

    if (pc_location_matches_any($rowPincodes, $context['pincodes'] ?? [])) {
        return 0;
    }
    if (pc_location_matches_any($rowAreas, $context['areas'] ?? [])) {
        return 1;
    }
    if (
        pc_location_matches_any($rowCities, $context['cities'] ?? []) ||
        pc_location_matches_any($rowCities, $context['districts'] ?? [])
    ) {
        return 2;
    }
    if (
        pc_location_matches_any($rowStates, $context['states'] ?? []) ||
        pc_location_matches_any($rowStates, $context['districts'] ?? [])
    ) {
        return 3;
    }
    return 99;
}

function pc_taker_coordinates(PDO $db, array $row, bool $hasGeoColumns): ?array
{
    if ($hasGeoColumns && isset($row['latitude'], $row['longitude']) && is_numeric($row['latitude']) && is_numeric($row['longitude'])) {
        return [
            'lat' => (float)$row['latitude'],
            'lon' => (float)$row['longitude'],
        ];
    }
    if (!$hasGeoColumns) {
        return null;
    }

    static $geocodeWrites = 0;
    if ($geocodeWrites >= 12) {
        return null;
    }

    $parts = array_filter([
        trim((string)($row['area'] ?? '')),
        trim((string)($row['city'] ?? '')),
        trim((string)($row['state'] ?? '')),
        trim((string)($row['pincode'] ?? '')),
        'India',
    ]);
    $query = implode(', ', array_values(array_unique($parts)));
    if ($query === 'India') {
        return null;
    }

    $geo = pc_geocode_search($query);
    if (!$geo || !isset($geo['lat'], $geo['lon'])) {
        return null;
    }

    $geocodeWrites++;
    try {
        $stmt = $db->prepare(
            "UPDATE takers
             SET latitude = ?, longitude = ?, geo_updated_at = NOW(), geo_source = 'nominatim'
             WHERE id = ?"
        );
        $stmt->execute([(float)$geo['lat'], (float)$geo['lon'], (int)($row['id'] ?? 0)]);
    } catch (Throwable $e) {
        pc_log_runtime_error('Taker geocode update failed: ' . $e->getMessage());
    }

    return [
        'lat' => (float)$geo['lat'],
        'lon' => (float)$geo['lon'],
    ];
}

function pc_haversine_km(float $lat1, float $lon1, float $lat2, float $lon2): float
{
    $earthRadiusKm = 6371.0088;
    $dLat = deg2rad($lat2 - $lat1);
    $dLon = deg2rad($lon2 - $lon1);
    $a = sin($dLat / 2) ** 2
        + cos(deg2rad($lat1)) * cos(deg2rad($lat2)) * sin($dLon / 2) ** 2;
    return $earthRadiusKm * 2 * atan2(sqrt($a), sqrt(max(0.0, 1 - $a)));
}

function pc_distance_proximity_tier(?float $distanceKm): int
{
    if ($distanceKm === null) {
        return 99;
    }
    return match (true) {
        $distanceKm <= 10.0 => 0,
        $distanceKm <= 30.0 => 1,
        $distanceKm <= 75.0 => 2,
        $distanceKm <= PC_SEARCH_MAX_RADIUS_KM => 3,
        default => 99,
    };
}

function pc_compute_taker_ranking_score(array $row, int $deviceTier, int $searchTier, int $textScore, ?float $activeDistanceKm): float
{
    $rating = (float)($row['avg_rating'] ?? 0);
    $reviewCount = (int)($row['review_count'] ?? 0);
    $favoriteCount = (int)($row['favorite_count'] ?? 0);
    $postCount = (int)($row['post_count'] ?? 0);
    $activePostCount = (int)($row['active_post_count'] ?? 0);
    $viewCount = (int)($row['total_view_count'] ?? 0);
    $likeCount = (int)($row['total_like_count'] ?? 0);
    $isFeatured = (int)($row['is_featured'] ?? 0) === 1;
    $viewerHasFavorited = !empty($row['viewer_has_favorited']);
    $isAvailable = isset($row['is_available']) ? (int)$row['is_available'] === 1 : false;
    $trustStage = (string)($row['trust_stage'] ?? 'unverified');
    $matchedServiceCount = (int)($row['matched_service_count'] ?? 0);
    $respondsFast = !empty($row['responds_fast']);
    $viewerClicks = (int)($row['viewer_search_click_count'] ?? 0);
    $viewerSearchFavorites = (int)($row['viewer_search_favorite_count'] ?? 0);
    $viewerSearchBookings = (int)($row['viewer_search_booking_count'] ?? 0);

    $score = 0.0;
    $score += min(5.0, $rating) * 11.0;
    $score += min(40, $reviewCount) * 1.2;
    $score += min(30, $favoriteCount) * 1.5;
    $score += min(20, $postCount) * 1.3;
    $score += min(10, $activePostCount) * 2.6;
    $score += min(30.0, log10(max(1, $viewCount + ($likeCount * 3))) * 11.0);
    $score += $isFeatured ? 8.0 : 0.0;
    $score += $viewerHasFavorited ? 25.0 : 0.0;
    $score += $isAvailable ? 12.0 : 0.0;
    $score += min(4, $matchedServiceCount) * 9.0;
    $score += $respondsFast ? 10.0 : 0.0;
    $score += min(6, $viewerClicks) * 2.5;
    $score += min(4, $viewerSearchFavorites) * 5.0;
    $score += min(3, $viewerSearchBookings) * 8.0;
    $score += match ($trustStage) {
        'pro_verified' => 28.0,
        'trusted' => 18.0,
        'verified' => 9.0,
        default => -8.0,
    };
    $score += match ($deviceTier) {
        0 => 18.0,
        1 => 12.0,
        2 => 7.0,
        3 => 3.0,
        default => 0.0,
    };
    $score += match ($searchTier) {
        0 => 24.0,
        1 => 16.0,
        2 => 8.0,
        3 => 3.0,
        default => 0.0,
    };
    if ($activeDistanceKm !== null) {
        $distanceWeight = max(0.0, (PC_SEARCH_MAX_RADIUS_KM - min(PC_SEARCH_MAX_RADIUS_KM, $activeDistanceKm)) / PC_SEARCH_MAX_RADIUS_KM);
        $score += $distanceWeight * 35.0;
    }
    $score += $textScore / 10.0;

    return $score;
}

function pc_filter_search_candidates(array $rows, string $location, int $limit, bool $hasSearchCenter): array
{
    if ($location === '') {
        return $rows;
    }

    $hasLocationExpansion = false;
    foreach ($rows as $row) {
        if ((int)($row['search_proximity_tier'] ?? 99) <= 3) {
            $hasLocationExpansion = true;
            break;
        }
    }
    if (!$hasLocationExpansion) {
        $textMatches = array_values(array_filter($rows, static function (array $row): bool {
            return (int)($row['search_text_score'] ?? 0) > 0;
        }));
        if (!empty($textMatches)) {
            if ($hasSearchCenter && count($textMatches) < PC_SEARCH_MIN_RADIUS_RESULTS) {
                return pc_merge_candidate_rows($textMatches, pc_closest_search_candidates($rows), PC_SEARCH_MIN_RADIUS_RESULTS);
            }
            return $textMatches;
        }
        return $hasSearchCenter ? pc_fallback_search_candidates($rows) : [];
    }

    $tierLimit = 2;
    $directOrNearby = array_values(array_filter($rows, static function (array $row) use ($tierLimit): bool {
        return (int)($row['search_text_score'] ?? 0) > 0 || (int)($row['search_proximity_tier'] ?? 99) <= $tierLimit;
    }));

    if (count($directOrNearby) < min(12, $limit + 6)) {
        $tierLimit = 3;
        $directOrNearby = array_values(array_filter($rows, static function (array $row) use ($tierLimit): bool {
            return (int)($row['search_text_score'] ?? 0) > 0 || (int)($row['search_proximity_tier'] ?? 99) <= $tierLimit;
        }));
    }

    if (!empty($directOrNearby)) {
        if ($hasSearchCenter && count($directOrNearby) < PC_SEARCH_MIN_RADIUS_RESULTS) {
            return pc_merge_candidate_rows($directOrNearby, pc_closest_search_candidates($rows), PC_SEARCH_MIN_RADIUS_RESULTS);
        }
        return $directOrNearby;
    }

    $textMatches = array_values(array_filter($rows, static function (array $row): bool {
        return (int)($row['search_text_score'] ?? 0) > 0;
    }));
    if (!empty($textMatches)) {
        if ($hasSearchCenter && count($textMatches) < PC_SEARCH_MIN_RADIUS_RESULTS) {
            return pc_merge_candidate_rows($textMatches, pc_closest_search_candidates($rows), PC_SEARCH_MIN_RADIUS_RESULTS);
        }
        return $textMatches;
    }
    return $hasSearchCenter ? pc_fallback_search_candidates($rows) : [];
}

function pc_fallback_search_candidates(array $rows): array
{
    $nearDevice = array_values(array_filter($rows, static function (array $row): bool {
        return (int)($row['device_proximity_tier'] ?? 99) <= 3;
    }));

    return !empty($nearDevice) ? $nearDevice : $rows;
}

function pc_closest_search_candidates(array $rows): array
{
    usort($rows, static function (array $a, array $b): int {
        $ad = $a['search_distance_km'] ?? $a['distance_km'] ?? PHP_FLOAT_MAX;
        $bd = $b['search_distance_km'] ?? $b['distance_km'] ?? PHP_FLOAT_MAX;
        if ($ad == $bd) {
            return ((float)($b['ranking_score'] ?? 0.0)) <=> ((float)($a['ranking_score'] ?? 0.0));
        }
        return ((float)$ad) <=> ((float)$bd);
    });
    return $rows;
}

function pc_merge_candidate_rows(array $primaryRows, array $fallbackRows, int $minCount): array
{
    $merged = [];
    $seen = [];
    foreach (array_merge($primaryRows, $fallbackRows) as $row) {
        $id = (int)($row['id'] ?? 0);
        if ($id <= 0 || isset($seen[$id])) {
            continue;
        }
        $seen[$id] = true;
        $merged[] = $row;
        if (count($merged) >= $minCount) {
            break;
        }
    }
    return $merged;
}

function pc_ensure_search_insights_schema(PDO $db): void
{
    static $done = false;
    if ($done) {
        return;
    }
    $done = true;
    try {
        $db->exec("CREATE TABLE IF NOT EXISTS search_events (
            id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            actor_role ENUM('client','taker') DEFAULT NULL,
            actor_id INT UNSIGNED DEFAULT NULL,
            event_type ENUM('search','click','favorite','booking','alert_created') NOT NULL DEFAULT 'search',
            query_text VARCHAR(255) DEFAULT NULL,
            location_text VARCHAR(255) DEFAULT NULL,
            service_types_json JSON DEFAULT NULL,
            service_match_mode VARCHAR(24) DEFAULT NULL,
            requested_radius_km DECIMAL(6,2) DEFAULT NULL,
            applied_radius_km DECIMAL(6,2) DEFAULT NULL,
            result_count INT UNSIGNED NOT NULL DEFAULT 0,
            taker_id INT UNSIGNED DEFAULT NULL,
            filters_json JSON DEFAULT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_search_events_created(created_at),
            INDEX idx_search_events_actor(actor_role, actor_id, created_at),
            INDEX idx_search_events_type_created(event_type, created_at),
            INDEX idx_search_events_taker(taker_id, created_at),
            INDEX idx_search_events_query(query_text(80), created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        $db->exec("CREATE TABLE IF NOT EXISTS saved_search_alerts (
            id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            actor_role ENUM('client','taker') NOT NULL,
            actor_id INT UNSIGNED NOT NULL,
            query_text VARCHAR(255) DEFAULT NULL,
            location_text VARCHAR(255) DEFAULT NULL,
            service_types_json JSON DEFAULT NULL,
            service_match_mode VARCHAR(24) NOT NULL DEFAULT 'smart',
            radius_km DECIMAL(6,2) NOT NULL DEFAULT 25.00,
            filters_json JSON DEFAULT NULL,
            is_active TINYINT(1) NOT NULL DEFAULT 1,
            last_checked_at DATETIME DEFAULT NULL,
            last_notified_at DATETIME DEFAULT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            INDEX idx_saved_search_alerts_actor(actor_role, actor_id, is_active),
            INDEX idx_saved_search_alerts_active(is_active, last_checked_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    } catch (Throwable $e) {
        pc_log_runtime_error('Search insights schema skipped: ' . $e->getMessage());
    }
}

function pc_record_search_event(PDO $db, ?array $auth, array $event): void
{
    try {
        pc_ensure_search_insights_schema($db);
        $actorRole = null;
        $actorId = null;
        if ($auth) {
            $role = (string)($auth['role'] ?? '');
            if (in_array($role, ['client', 'taker'], true)) {
                $resolved = resolveProfileIdForRole($db, $auth, $role);
                if ($resolved !== null) {
                    $actorRole = $role;
                    $actorId = (int)$resolved;
                }
            }
        }
        $stmt = $db->prepare(
            "INSERT INTO search_events
             (actor_role, actor_id, event_type, query_text, location_text, service_types_json, service_match_mode,
              requested_radius_km, applied_radius_km, result_count, taker_id, filters_json)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );
        $stmt->execute([
            $actorRole,
            $actorId,
            (string)($event['event_type'] ?? 'search'),
            (string)($event['query_text'] ?? ''),
            (string)($event['location_text'] ?? ''),
            json_encode($event['service_types'] ?? [], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
            (string)($event['service_match_mode'] ?? 'smart'),
            $event['requested_radius_km'] ?? null,
            $event['applied_radius_km'] ?? null,
            (int)($event['result_count'] ?? 0),
            $event['taker_id'] ?? null,
            json_encode($event['filters_json'] ?? [], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        ]);
    } catch (Throwable $e) {
        pc_log_runtime_error('Search event skipped: ' . $e->getMessage());
    }
}

function pc_compare_taker_rows(array $a, array $b): int
{
    $textCompare = ((int)($b['search_text_score'] ?? 0)) <=> ((int)($a['search_text_score'] ?? 0));
    if ($textCompare !== 0) {
        return $textCompare;
    }

    $searchTierCompare = ((int)($a['search_proximity_tier'] ?? 99)) <=> ((int)($b['search_proximity_tier'] ?? 99));
    if ($searchTierCompare !== 0) {
        return $searchTierCompare;
    }

    $deviceTierCompare = ((int)($a['device_proximity_tier'] ?? 99)) <=> ((int)($b['device_proximity_tier'] ?? 99));
    if ($deviceTierCompare !== 0) {
        return $deviceTierCompare;
    }

    $distanceA = isset($a['distance_km']) && is_numeric($a['distance_km']) ? (float)$a['distance_km'] : 999999.0;
    $distanceB = isset($b['distance_km']) && is_numeric($b['distance_km']) ? (float)$b['distance_km'] : 999999.0;
    $distanceCompare = $distanceA <=> $distanceB;
    if ($distanceCompare !== 0) {
        return $distanceCompare;
    }

    $favoriteCompare = ((int)!empty($b['viewer_has_favorited'])) <=> ((int)!empty($a['viewer_has_favorited']));
    if ($favoriteCompare !== 0) {
        return $favoriteCompare;
    }

    $rankingCompare = ((float)($b['ranking_score'] ?? 0.0)) <=> ((float)($a['ranking_score'] ?? 0.0));
    if ($rankingCompare !== 0) {
        return $rankingCompare;
    }

    $ratingCompare = ((float)($b['avg_rating'] ?? 0.0)) <=> ((float)($a['avg_rating'] ?? 0.0));
    if ($ratingCompare !== 0) {
        return $ratingCompare;
    }

    $reviewCompare = ((int)($b['review_count'] ?? 0)) <=> ((int)($a['review_count'] ?? 0));
    if ($reviewCompare !== 0) {
        return $reviewCompare;
    }

    return strcmp(
        pc_normalize_search_key((string)($a['full_name'] ?? '')),
        pc_normalize_search_key((string)($b['full_name'] ?? ''))
    );
}

function pc_proximity_label(int $tier, ?float $distanceKm = null): ?string
{
    if ($distanceKm !== null && $tier <= 2) {
        return $distanceKm < 1.0 ? 'Very near' : (round($distanceKm) . ' km away');
    }
    return match ($tier) {
        0 => 'Same pincode',
        1 => 'Nearby area',
        2 => 'Same city',
        3 => 'Same state',
        default => null,
    };
}
