<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/trustHelpers.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    respond(false, 'Method not allowed', [], 405);
}

requireAdminRequest();

$type = strtolower(trim((string)($_GET['type'] ?? 'all')));
$status = strtolower(trim((string)($_GET['status'] ?? 'pending')));
$limit = min(100, max(1, (int)($_GET['limit'] ?? 50)));

if (!in_array($type, ['all', 'taker', 'studio'], true)) {
    respond(false, 'Invalid queue type', [], 422);
}
if (!in_array($status, ['all', 'pending', 'approved', 'rejected', 'not_submitted'], true)) {
    respond(false, 'Invalid queue status', [], 422);
}

try {
    $db = getDB();
    ensureTrustSchema($db);
    $data = [];

    if ($type === 'all' || $type === 'taker') {
        $where = '';
        $params = [];
        if ($status !== 'all') {
            $where = 'WHERE tv.aadhaar_status=? OR tv.portfolio_status=? OR tv.social_status=?';
            $params = [$status, $status, $status];
        }
        $stmt = $db->prepare(
            "SELECT
                t.id AS taker_id,
                t.full_name,
                u.email,
                u.phone,
                tv.aadhaar_status,
                tv.portfolio_status,
                tv.portfolio_photo_count,
                tv.portfolio_device_summary,
                tv.social_status,
                tv.social_url AS submitted_social_url,
                tv.social_url,
                t.portfolio_url,
                t.instagram_url,
                t.youtube_url,
                t.social_link_additional1,
                t.social_link_additional2,
                tv.admin_notes,
                tv.updated_at,
                CASE WHEN tv.aadhaar_front_url IS NULL OR tv.aadhaar_front_url='' THEN 0 ELSE 1 END AS has_aadhaar_front
             FROM taker_verifications tv
             INNER JOIN takers t ON t.id=tv.taker_id
             LEFT JOIN users u ON u.id=t.user_id
             {$where}
             ORDER BY tv.updated_at DESC
             LIMIT {$limit}"
        );
        $stmt->execute($params);
        $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);
        foreach ($rows as &$row) {
            $links = [];
            $seen = [];
            foreach ([
                'submitted_link' => $row['submitted_social_url'] ?? '',
                'portfolio' => $row['portfolio_url'] ?? '',
                'additional_link_1' => $row['social_link_additional1'] ?? '',
                'additional_link_2' => $row['social_link_additional2'] ?? '',
                'instagram' => $row['instagram_url'] ?? '',
                'youtube' => $row['youtube_url'] ?? '',
            ] as $type => $url) {
                $url = trim((string)$url);
                if ($url === '') {
                    continue;
                }
                $key = strtolower(rtrim($url, '/'));
                if (isset($seen[$key])) {
                    continue;
                }
                $seen[$key] = true;
                $links[] = ['type' => $type, 'url' => $url];
            }
            $row['portfolio_links'] = $links;
        }
        unset($row);
        $data['takers'] = $rows;
    }

    if ($type === 'all' || $type === 'studio') {
        $where = '';
        $params = [];
        if ($status !== 'all') {
            $where = 'WHERE sv.business_status=? OR sv.owner_aadhaar_status=?';
            $params = [$status, $status];
        }
        $stmt = $db->prepare(
            "SELECT
                c.id AS client_id,
                c.name,
                u.email,
                u.phone,
                sv.business_status,
                sv.verification_path,
                sv.gstin,
                sv.google_maps_url,
                sv.owner_aadhaar_status,
                sv.admin_notes,
                sv.updated_at,
                CASE WHEN sv.gst_certificate_url IS NULL OR sv.gst_certificate_url='' THEN 0 ELSE 1 END AS has_gst_certificate,
                CASE WHEN sv.shop_license_url IS NULL OR sv.shop_license_url='' THEN 0 ELSE 1 END AS has_shop_license,
                CASE WHEN sv.signboard_url IS NULL OR sv.signboard_url='' THEN 0 ELSE 1 END AS has_signboard,
                CASE WHEN sv.owner_aadhaar_url IS NULL OR sv.owner_aadhaar_url='' THEN 0 ELSE 1 END AS has_owner_aadhaar
             FROM studio_verifications sv
             INNER JOIN clients c ON c.id=sv.client_id
             LEFT JOIN users u ON u.id=c.user_id
             {$where}
             ORDER BY sv.updated_at DESC
             LIMIT {$limit}"
        );
        $stmt->execute($params);
        $data['studios'] = $stmt->fetchAll();
    }

    respond(true, 'OK', $data);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
