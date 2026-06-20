<?php
require_once 'config.php';
if ($_SERVER['REQUEST_METHOD'] !== 'GET') respond(false,'Method not allowed',[],405);
$takerId = (int)($_GET['takerId'] ?? 0);
$page    = max(1,(int)($_GET['page'] ?? 1));
$limit   = min(50, max(1, (int)($_GET['limit'] ?? 10)));
$offset = ($page-1)*$limit;
if (!$takerId) respond(false,'Missing takerId',[],422);
try {
    $db = getDB();
    $auth = requireAuthenticatedUser();
    rateLimit('reviews_read_user:' . (int)$auth['user_id'], 'reviews-read', 240, 60);
    $taker = $db->prepare('SELECT avg_rating,review_count FROM takers WHERE id=? LIMIT 1');
    $taker->execute([$takerId]);
    $takerRow = $taker->fetch();
    $stmt = $db->prepare(
        'SELECT r.id, r.taker_id, r.client_id, r.booking_id, r.rating, r.comment, r.created_at,
                c.name AS client_name
         FROM reviews r
         JOIN clients c ON c.id=r.client_id
         WHERE r.taker_id=?
         ORDER BY r.created_at DESC
         LIMIT ? OFFSET ?'
    );
    $stmt->execute([$takerId,$limit,$offset]);
    $reviews = $stmt->fetchAll();
    $total = (int)($takerRow['review_count'] ?? 0);
    respond(true,'OK',[
        'taker_id'=>$takerId,
        'avg_rating'=>(float)($takerRow['avg_rating']??0),
        'total'=>$total,
        'page'=>$page,
        'limit'=>$limit,
        'total_pages'=>(int)max(1, ceil($total / max(1, $limit))),
        'reviews'=>$reviews
    ]);
} catch(PDOException $e){ respond(false,$e->getMessage(),[],500); }
