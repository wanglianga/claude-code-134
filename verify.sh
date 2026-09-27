#!/usr/bin/env bash
# 端到端验证脚本:对运行中的服务执行完整业务链路检查。
# 用法: ./verify.sh [BASE_URL]   (默认 http://localhost:8080)
set -u
BASE="${1:-http://localhost:8080}"
PASS=0
FAIL=0

say()  { printf '%s\n' "$*"; }
ok()   { PASS=$((PASS+1)); say "  ✅ $1"; }
bad()  { FAIL=$((FAIL+1)); say "  ❌ $1"; }

# check <描述> <实际> <期望>
check() {
  if [ "$2" = "$3" ]; then ok "$1 (=$2)"; else bad "$1 (期望 $3, 实际 $2)"; fi
}
# check_true <描述> <jq布尔结果>
check_true() { check "$1" "$2" "true"; }

# 工作日 16:00 时段(避开种子班级的周末课次)
weekday_slot() {
  local d dow
  d=$(date -d "+$1 days" +%F)
  dow=$(date -d "$d" +%u)
  while [ "$dow" -ge 6 ]; do d=$(date -d "$d +1 day" +%F); dow=$(date -d "$d" +%u); done
  echo "${d}T16:00"
}

RUN_ID=$(date +%s)

J() { jq -r "$@"; }

# 课次时间减去 N 小时 -> ISO 即时时间
minus_hours() {
  local epoch
  epoch=$(date -u -d "${1/T/ }:00" +%s)
  date -u -d "@$((epoch - $2 * 3600))" +%Y-%m-%dT%H:%M:%SZ
}

say "== 0. 服务与种子数据 =="
META=$(curl -sf "$BASE/api/meta") || { say "服务不可达: $BASE"; exit 1; }
check "校区数" "$(echo "$META" | jq '.campuses | length')" "2"
check "共享老师 T001 覆盖两校区" "$(echo "$META" | J '[.teachers[] | select(.id=="T001") | .campusIds] | add | length')" "2"

W1=$(weekday_slot 3); W2=$(weekday_slot 4); W3=$(weekday_slot 5)
ZGC_CLASS=$(curl -s "$BASE/api/classes?campusId=CAMP-ZGC" | J '.[] | select(.subject=="MATH" and .level=="INTERMEDIATE") | .id')
WJ_CLASS=$(curl -s  "$BASE/api/classes?campusId=CAMP-WJ"  | J '.[] | select(.subject=="MATH" and .level=="INTERMEDIATE") | .id')
mapfile -t ZGC_SLOTS < <(curl -s "$BASE/api/classes/$ZGC_CLASS" | J '.slots[]' | sort)
mapfile -t WJ_SLOTS  < <(curl -s "$BASE/api/classes/$WJ_CLASS"  | J '.slots[]' | sort)
say "  班级: ZGC=$ZGC_CLASS WJ=$WJ_CLASS; 试听时段: $W1/$W2/$W3"

say "== 1. 线索创建 =="
LEAD=$(curl -s -X POST "$BASE/api/leads" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -d "{
  \"studentName\":\"王小明\",\"grade\":\"四年级\",\"subject\":\"MATH\",\"level\":\"INTERMEDIATE\",
  \"textbook\":\"PEP\",\"availableSlots\":[\"$W1\",\"$W2\",\"$W3\"],
  \"channel\":\"大众点评\",\"consultantId\":\"CS001\",\"campusId\":\"CAMP-ZGC\",\"activityParticipated\":true}")
LEAD_ID=$(echo "$LEAD" | J '.id')
check "线索创建" "$(echo "$LEAD" | J '.status')" "NEW"
say "  lead=$LEAD_ID"

say "== 2. 试听匹配与预约 =="
MATCH=$(curl -s "$BASE/api/trials/match?leadId=$LEAD_ID&slot=$W1")
check_true "匹配到排课选项" "$(echo "$MATCH" | jq 'length > 0')"
TRIAL=$(curl -s -X POST "$BASE/api/trials" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' \
  -d "{\"leadId\":\"$LEAD_ID\",\"slot\":\"$W1\"}")
TRIAL_ID=$(echo "$TRIAL" | J '.id')
check "预约试听(自动匹配 T001)" "$(echo "$TRIAL" | J '.teacherId')" "T001"

# 跨校区共享老师冲突:同老师同时段在望京不可再排
LEAD2=$(curl -s -X POST "$BASE/api/leads" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS002' -d "{
  \"studentName\":\"测试冲突\",\"grade\":\"五年级\",\"subject\":\"MATH\",\"level\":\"INTERMEDIATE\",
  \"textbook\":\"PEP\",\"availableSlots\":[\"$W1\"],
  \"channel\":\"自然到店\",\"consultantId\":\"CS002\",\"campusId\":\"CAMP-WJ\",\"activityParticipated\":false}")
LEAD2_ID=$(echo "$LEAD2" | J '.id')
MATCH2=$(curl -s "$BASE/api/trials/match?leadId=$LEAD2_ID&slot=$W1")
check_true "共享老师 T001 同时段在另一校区不可排" "$(echo "$MATCH2" | jq '[.[] | select(.teacherId=="T001")] | length == 0')"

say "== 3. 两次改约 -> 风险标记 =="
T2=$(curl -s -X POST "$BASE/api/trials/$TRIAL_ID/reschedule" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -d "{\"newSlot\":\"$W2\"}")
T3=$(curl -s -X POST "$BASE/api/trials/$(echo "$T2" | J '.id')/reschedule" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -d "{\"newSlot\":\"$W3\"}")
TRIAL_ID=$(echo "$T3" | J '.id')
check "改约后状态" "$(echo "$T3" | J '.status')" "BOOKED"

say "== 4. 到课(赠品)与试听反馈 =="
curl -s -o /dev/null -X POST "$BASE/api/trials/$TRIAL_ID/attend" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' \
  -d '{"giftName":"错题本礼盒","giftValueCents":5000}'
FB=$(curl -s -X POST "$BASE/api/trials/$TRIAL_ID/feedback" -H 'Content-Type: application/json' -H 'X-Actor-Id: T001' -d '{
  "teacherId":"T001","performance":"互动积极,计算粗心","assessmentScore":78,
  "suitableClassType":"SMALL","riskWarnings":["计算基础薄弱"]}')
check "反馈测评分" "$(echo "$FB" | J '.assessmentScore')" "78"

say "== 5. 承诺一致性核对 =="
CONTRACT_BODY=$(cat <<EOF
{
  "leadId":"$LEAD_ID","packageId":"PKG-MATH-48","classGroupId":"$ZGC_CLASS",
  "giftLessons":4,"discountCents":96000,
  "installments":[{"no":1,"dueDate":"2026-10-01","amountCents":432000},{"no":2,"dueDate":"2026-11-01","amountCents":432000}],
  "paidInstallments":1,"textbookDelivered":true,
  "promises":[
    {"type":"CLASS_TYPE","content":"承诺一对一教学","refValue":"ONE_ON_ONE","promisedBy":"CS001"},
    {"type":"OTHER","content":"风险已告知家长","promisedBy":"CS001"}
  ]
}
EOF
)
CODE=$(curl -s -o /tmp/resp.json -w "%{http_code}" -X POST "$BASE/api/contracts" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -d "$CONTRACT_BODY")
check "承诺不一致未确认 -> 409" "$CODE" "409"
check "错误码 PROMISE_MISMATCH" "$(J '.error.code' /tmp/resp.json)" "PROMISE_MISMATCH"

CONTRACT_BODY_ACK=$(echo "$CONTRACT_BODY" | jq '.acknowledgeMismatch = true')
CONTRACT=$(curl -s -X POST "$BASE/api/contracts" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -H "X-Idempotency-Key: verify-ct-$RUN_ID" -d "$CONTRACT_BODY_ACK")
CT_ID=$(echo "$CONTRACT" | J '.id')
STU_ID=$(echo "$CONTRACT" | J '.studentId')
check "确认后签约成功" "$(echo "$CONTRACT" | J '.status')" "ACTIVE"
check "承诺核对标记 MISMATCH" "$(echo "$CONTRACT" | J '.promiseCheck.status')" "MISMATCH"
check "渠道费(大众点评 10%)" "$(echo "$CONTRACT" | J '.channelFeeCents')" "86400"
check "实缴(1/2 期)" "$(echo "$CONTRACT" | J '.paidCents')" "432000"

REPLAY=$(curl -s -X POST "$BASE/api/contracts" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -H "X-Idempotency-Key: verify-ct-$RUN_ID" -d "$CONTRACT_BODY_ACK")
check "幂等重放返回同一合同" "$(echo "$REPLAY" | J '.id')" "$CT_ID"

say "== 6. 分期与发票 =="
curl -s -o /dev/null -X POST "$BASE/api/contracts/$CT_ID/pay-installment" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -d '{"no":2}'
INV=$(curl -s -X POST "$BASE/api/contracts/$CT_ID/invoice" -H 'X-Actor-Id: CS001')
check "发票金额=全额实缴" "$(echo "$INV" | J '.invoice.amountCents')" "864000"

say "== 7. 课消(到课/请假/缺席) =="
for i in 0 1 2; do
  curl -s -o /dev/null -X POST "$BASE/api/students/$STU_ID/lessons/complete" -H 'Content-Type: application/json' -H 'X-Actor-Id: T001' -d "{\"slot\":\"${ZGC_SLOTS[$i]}\"}"
done
EARLY=$(minus_hours "${ZGC_SLOTS[3]}" 48)
curl -s -o /dev/null -X POST "$BASE/api/students/$STU_ID/lessons/leave" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' \
  -d "{\"slot\":\"${ZGC_SLOTS[3]}\",\"requestedAt\":\"$EARLY\",\"reason\":\"外出比赛\"}"
LATE=$(minus_hours "${ZGC_SLOTS[4]}" 2)
curl -s -o /dev/null -X POST "$BASE/api/students/$STU_ID/lessons/leave" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' \
  -d "{\"slot\":\"${ZGC_SLOTS[4]}\",\"requestedAt\":\"$LATE\",\"reason\":\"临时发烧\"}"
curl -s -o /dev/null -X POST "$BASE/api/students/$STU_ID/lessons/absent" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -d "{\"slot\":\"${ZGC_SLOTS[5]}\"}"
ACC=$(curl -s "$BASE/api/students/$STU_ID/account")
check "已消正课=5(3到课+1临时请假+1缺席)" "$(echo "$ACC" | J '.consumedPaid')" "5"
check "请假次数=2" "$(echo "$ACC" | J '.leaveCount')" "2"

say "== 8. 赠课争议 =="
GD=$(curl -s -X POST "$BASE/api/contracts/$CT_ID/gift-disputes" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' \
  -d '{"claimedGiftLessons":6,"reason":"顾问口头承诺 6 节赠课"}')
curl -s -o /dev/null -X POST "$BASE/api/gift-disputes/$(echo "$GD" | J '.id')/resolve" -H 'Content-Type: application/json' -H 'X-Actor-Id: P001' -d '{"approve":true}'
check "争议成立后赠课总数=6" "$(curl -s "$BASE/api/students/$STU_ID/account" | J '.giftTotal')" "6"

say "== 9. 转班(跨校区) =="
curl -s -o /dev/null -X POST "$BASE/api/students/$STU_ID/transfer" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -d "{\"toClassGroupId\":\"$WJ_CLASS\"}"
curl -s -o /dev/null -X POST "$BASE/api/students/$STU_ID/lessons/complete" -H 'Content-Type: application/json' -H 'X-Actor-Id: T003' -d "{\"slot\":\"${WJ_SLOTS[0]}\"}"
check "转班后在新校区消课,累计=6" "$(curl -s "$BASE/api/students/$STU_ID/account" | J '.consumedPaid')" "6"

say "== 10. 停课/复课 =="
curl -s -o /dev/null -X POST "$BASE/api/contracts/$CT_ID/suspend" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -d '{"reason":"家中有事"}'
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/students/$STU_ID/lessons/complete" -H 'Content-Type: application/json' -H 'X-Actor-Id: T003' -d "{\"slot\":\"${WJ_SLOTS[1]}\"}")
check "停课期间课消被拒" "$CODE" "409"
curl -s -o /dev/null -X POST "$BASE/api/contracts/$CT_ID/resume" -H 'X-Actor-Id: CS001'

say "== 11. 投诉与退费 =="
CPL=$(curl -s -X POST "$BASE/api/students/$STU_ID/complaints" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -d '{"content":"承诺一对一实际为小班课"}')
curl -s -o /dev/null -X POST "$BASE/api/complaints/$(echo "$CPL" | J '.id')/resolve" -H 'Content-Type: application/json' -H 'X-Actor-Id: P001' -d '{"upheld":true,"note":"情况属实"}'
CLAW_BEFORE=$(curl -s "$BASE/api/consultants/CS001/performance" | J '.commission.clawedBackCents')
QUOTE=$(curl -s "$BASE/api/contracts/$CT_ID/refund-quote?giftReturned=false")
check "试算:已上 6 节按折后价结算(投诉成立)" "$(echo "$QUOTE" | J '.consumedValueCents')" "108000"
check "试算:投诉减免=优惠回收+教材费" "$(echo "$QUOTE" | J '.complaintWaiverCents')" "42000"
check "试算:赠品未退扣 50 元" "$(echo "$QUOTE" | J '.giftDeductCents')" "5000"
check "试算:应退 7510 元" "$(echo "$QUOTE" | J '.refundCents')" "751000"

REFUND=$(curl -s -X POST "$BASE/api/contracts/$CT_ID/refund" -H 'Content-Type: application/json' -H 'X-Actor-Id: P001' -H "X-Idempotency-Key: verify-rf-$RUN_ID" \
  -d '{"reason":"服务与承诺不符","giftReturned":false}')
check "退费执行" "$(echo "$REFUND" | J '.quote.refundCents')" "751000"
REFUND2=$(curl -s -X POST "$BASE/api/contracts/$CT_ID/refund" -H 'Content-Type: application/json' -H 'X-Actor-Id: P001' -H "X-Idempotency-Key: verify-rf-$RUN_ID" \
  -d '{"reason":"服务与承诺不符","giftReturned":false}')
check "退费幂等重放" "$(echo "$REFUND2" | J '.id')" "$(echo "$REFUND" | J '.id')"

CLOSED=$(curl -s "$BASE/api/contracts/$CT_ID")
check "合同关闭" "$(echo "$CLOSED" | J '.status')" "CLOSED_REFUND"
check "发票红冲" "$(echo "$CLOSED" | J '.invoice.status')" "RED_FLUSHED"
check "剩余赠课作废" "$(curl -s "$BASE/api/students/$STU_ID/account" | J '.remainingGift')" "0"
PERF=$(curl -s "$BASE/api/consultants/CS001/performance")
check "顾问提成追回增加 375.50 元" "$(echo "$PERF" | J '.commission.clawedBackCents')" "$((CLAW_BEFORE + 37550))"

say "== 12. 履约轨迹与校长视图 =="
TRAJ=$(curl -s "$BASE/api/students/$STU_ID/trajectory")
for EV in LEAD_CREATED TRIAL_BOOKED TRIAL_RESCHEDULED RISK_FLAG TRIAL_ATTENDED TRIAL_FEEDBACK CONVERTED \
          CONTRACT_SIGNED PROMISE_MISMATCH_ACKED INSTALLMENT_PAID INVOICE_ISSUED LESSON_CONSUMED \
          LESSON_LEAVE LESSON_LEAVE_LATE LESSON_ABSENT GIFT_DISPUTE_RESOLVED CLASS_TRANSFERRED \
          CONTRACT_SUSPENDED CONTRACT_RESUMED COMPLAINT_FILED COMPLAINT_RESOLVED \
          COMMISSION_FROZEN COMMISSION_CLAWED_BACK INVOICE_RED_FLUSHED REFUND_EXECUTED; do
  check_true "轨迹含 $EV" "$(echo "$TRAJ" | jq "[.trajectory[] | select(.type==\"$EV\")] | length > 0")"
done
OV=$(curl -s "$BASE/api/principal/overview")
check_true "校长视图:转化数>=1" "$(echo "$OV" | jq '.funnel.converted >= 1')"
check_true "校长视图:退费金额>=751000" "$(echo "$OV" | jq '.finance.refundedCents >= 751000')"

say "== 13. 老师请假联动 =="
W7=$(weekday_slot $((7 + RUN_ID % 7)))
# 每次运行新建一位老师,避免请假状态跨运行累积
T_NEW=$(curl -s -X POST "$BASE/api/teachers" -H 'Content-Type: application/json' -H 'X-Actor-Id: ADMIN' -d "{
  \"name\":\"临时老师$RUN_ID\",\"subjects\":[\"MATH\"],\"levels\":[\"INTERMEDIATE\"],
  \"textbooks\":[\"PEP\"],\"campusIds\":[\"CAMP-ZGC\"],\"availableSlots\":[\"$W7\"]}" | J '.id')
LEAD3=$(curl -s -X POST "$BASE/api/leads" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -d "{
  \"studentName\":\"请假联动\",\"grade\":\"三年级\",\"subject\":\"MATH\",\"level\":\"INTERMEDIATE\",
  \"textbook\":\"PEP\",\"availableSlots\":[\"$W7\"],
  \"channel\":\"抖音\",\"consultantId\":\"CS001\",\"campusId\":\"CAMP-ZGC\",\"activityParticipated\":false}")
LEAD3_ID=$(echo "$LEAD3" | J '.id')
TRIAL3=$(curl -s -X POST "$BASE/api/trials" -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -d "{\"leadId\":\"$LEAD3_ID\",\"slot\":\"$W7\",\"teacherId\":\"$T_NEW\"}")
TRIAL3_ID=$(echo "$TRIAL3" | J '.id')
LEAVE=$(curl -s -X POST "$BASE/api/teachers/$T_NEW/leave" -H 'Content-Type: application/json' -H 'X-Actor-Id: P001' \
  -d "{\"slots\":[\"$W7\"],\"reason\":\"突发疾病\"}")
check_true "请假取消待上试听" "$(echo "$LEAVE" | jq --arg t "$TRIAL3_ID" '.cancelledTrials | index($t) != null')"
check "试听状态=CANCELLED" "$(curl -s "$BASE/api/trials/$TRIAL3_ID" | J '.status')" "CANCELLED"

say ""
say "==================================="
say "结果: 通过 $PASS 项, 失败 $FAIL 项"
[ "$FAIL" -eq 0 ] && say "ALL CHECKS PASSED" || { say "HAS FAILURES"; exit 1; }
