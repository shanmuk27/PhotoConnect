<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/trustHelpers.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    respond(false, 'Method not allowed', [], 405);
}

$takerId = (int)($_GET['takerId'] ?? 0);
$clientId = (int)($_GET['clientId'] ?? 0);

try {
    $db = getDB();
    ensureTrustSchema($db);
    $auth = requireAuthenticatedUser();

    if ($auth && $clientId <= 0) {
        $resolved = resolveProfileIdForRole($db, $auth, 'client');
        if ($resolved !== null) {
            $clientId = $resolved;
        }
    }

    $viewerRole = $clientId > 0 ? 'client' : null;
    $viewerId = $clientId > 0 ? $clientId : null;

    $data = [];
    if ($takerId > 0) {
        $data['taker_trust'] = pc_taker_trust_summary($db, $takerId, $viewerRole, $viewerId);
    }
    if ($clientId > 0) {
        if ($auth) {
            authorizeClientProfile($db, $auth, $clientId);
        }
        $data['studio_trust'] = pc_studio_trust_summary($db, $clientId);
    }

    respond(true, 'OK', $data);
} catch (PDOException $e) {
    respond(false, $e->getMessage(), [], 500);
}
