<?php
require_once 'config.php';
require_once 'bookingDayPartHelpers.php';
if ($_SERVER['REQUEST_METHOD'] !== 'GET') respond(false,'Method not allowed',[],405);
$takerId = (int)($_GET['takerId'] ?? 0);
$month   = trim($_GET['month']   ?? '');
if (!$takerId) respond(false,'Missing takerId',[],422);
try {
    $db = getDB();
    ensureBookingDayPartSchema($db);
    $chk = $db->prepare('SELECT id,full_name FROM takers WHERE id=? LIMIT 1');
    $chk->execute([$takerId]); $taker = $chk->fetch();
    if (!$taker) respond(false,'Taker not found',[],404);
    $params = [$takerId]; $dateCond = '';
    $monthStart = null;
    $monthEnd = null;
    if ($month && preg_match('/^\d{4}-\d{2}$/',$month)) {
        $monthStart = $month . '-01';
        $monthEnd = (new DateTime($monthStart))->modify('+1 month')->format('Y-m-d');
        $dateCond = "AND `date` >= ? AND `date` < ?";
        $params[] = $monthStart;
        $params[] = $monthEnd;
    }

    $stmt = $db->prepare("SELECT date,status,day_part FROM availability WHERE taker_id=? $dateCond ORDER BY date ASC, FIELD(day_part,'full_day','first_half','second_half')");
    $stmt->execute($params);
    respond(true,'OK',['taker_id'=>$takerId,'taker_name'=>$taker['full_name'],'availability'=>$stmt->fetchAll()]);
} catch(PDOException $e){ respond(false,$e->getMessage(),[],500); }
