# trial-flow-service — 培训机构试听课排班、转化、退费与课消服务

Kotlin + Spring Boot 3 + Redis 实现的培训机构招生履约服务,覆盖完整业务链路:

**线索 → 试听排课 → 试听反馈 → 承诺核对 → 报名签约 → 课消 → 插班/转班/停课/请假 → 退费 → 履约轨迹(校长视图)**

## 功能总览

| 域 | 能力 |
|---|---|
| 线索 | 记录学生年级、目标科目、基础水平、可上课时间、来源渠道、顾问、校区、是否已参加活动;试听后换校区;多次改约风险标记 |
| 试听排课 | 按 **老师空档 × 班级容量 × 教室 × 教材版本 × 学生水平** 自动匹配;支持随班试听(占班级座位)与 1v1 试听;改约/缺席/取消 |
| 试听反馈 | 老师提交课堂表现、测评结果、适合班型、风险提醒 |
| 报名转化 | 课包/优惠/赠课/分期/发票/班级/课消规则绑定同一学员账户;**承诺与试听反馈一致性核对**(不一致需人工确认);渠道费、顾问提成自动生成 |
| 课消 | 到课消课、提前请假不消课、临时请假消课、缺席按规则消课、补课;规则绑定在合同上 |
| 变更 | 报名后插班、转班(可跨校区)、合同停课/复课、班级停课/复课、老师临时请假(联动取消试听与班级课次) |
| 退费 | 已上课时(提前退费按原价结算即**优惠回收**)、赠课抵扣、教材费、体验课赠品扣减、**投诉成立减免**、**顾问提成冻结与追回**、发票红冲、剩余赠课作废 |
| 争议 | 家长投诉(成立影响退费)、赠课争议(成立补发赠课) |
| 校长视图 | 学员完整履约轨迹(线索到退费结束);经营漏斗与财务汇总;顾问业绩与提成冻结/追回 |
| 多校区 | 老师可多校区共享,时段跨校区全局互斥;渠道费率按渠道配置 |

## 技术栈与结构

- Kotlin 1.9 / Spring Boot 3.3 / Spring Data Redis(Lettuce)/ Maven
- 数据层为内存存储(演示用);**Redis 用于分布式锁与幂等键**,不可用时自动降级为进程内锁(日志 WARN 提示)
- 关键并发点(老师时段、教室时段、班级容量、合同)均通过 Redis 锁保护(`SET NX PX` + Lua 对比释放)

```
src/main/kotlin/com/trialflow/
├── domain/Domain.kt          # 枚举、实体、退费计算纯函数 computeRefund
├── store/DataStore.kt        # 内存数据表 + 履约轨迹事件流
├── infra/LockService.kt      # Redis 分布式锁(降级)/ 幂等键服务
├── service/                  # Lead/Trial/Class/Contract/Consumption/Refund/Query/Trajectory
├── api/                      # REST 控制器、DTO、统一异常
└── config/                   # 业务配置、种子数据
```

## 本地启动

前置:JDK 17+、Maven 3.6+、Redis(可选但推荐;无 Redis 时服务自动降级进程内锁)。

```bash
# 1. 启动 Redis(本机已装 redis-server)
redis-server --daemonize yes --port 6379
#   或者用 Docker: docker compose up -d redis

# 2. 构建并启动服务(端口 8080)
mvn package -DskipTests
java -jar target/trial-flow-service-1.0.0.jar

#   也可以直接 mvn spring-boot:run
#   自定义端口/Redis: SERVER_PORT=8081 REDIS_PORT=6379 java -jar target/trial-flow-service-1.0.0.jar
```

启动后自动写入种子数据:2 个校区、3 间教室、3 位老师(T001 为两校区共享)、3 个课包、4 个正式班级、4 个账号。可用 `curl http://localhost:8080/api/meta` 查看。

## 测试账号

无登录态,用请求头 `X-Actor-Id` 标识操作人(记入履约轨迹):

| 账号 | 姓名 | 角色 | 说明 |
|---|---|---|---|
| `P001` | 赵校长 | PRINCIPAL | 校长视图、投诉/争议处理 |
| `CS001` | 王顾问 | CONSULTANT | 中关村校区顾问 |
| `CS002` | 李顾问 | CONSULTANT | 望京校区顾问 |
| `ADMIN` | 系统管理员 | ADMIN | 基础数据管理 |

老师:`T001` 张敏(数学,**中关村+望京共享**)、`T002` 刘洋(英语,中关村)、`T003` 陈静(数学/物理,望京)。
课包:`PKG-MATH-48`(48 节 9600 元)、`PKG-MATH-24`(24 节 4800 元)、`PKG-ENG-24`(24 节 5200 元)。

## 验证方式

### 1. 单元/集成测试(已实际执行:18 个测试全部通过)

```bash
mvn test
```

- `RefundCalcTest`:退费计算(优惠回收、教材费、赠品、投诉减免、赠课抵扣政策、倒欠)
- `TrialServiceTest`:排课冲突、跨校区共享老师互斥、班级容量、老师请假联动、多次改约风险、教材版本不匹配
- `ApiFlowTest`(MockMvc 全链路):线索→试听→反馈→承诺核对 409→签约→分期/发票→课消→插班/转班/停课→赠课争议→投诉→退费→轨迹→校长视图
- `RedisLockIntegrationTest`:启动真实 redis-server 验证分布式锁互斥、持锁冲突、幂等键

### 2. 端到端脚本(已实际执行:连续 3 次运行均 60/60 项通过)

服务启动后执行(脚本每次运行生成全新数据,可重复执行):

```bash
./verify.sh                 # 默认 http://localhost:8080
./verify.sh http://host:port
```

覆盖 13 个阶段 60 项断言:种子数据 → 线索 → 匹配/预约 → 跨校区共享老师冲突 → 两次改约 → 到课赠品 → 反馈 → 承诺核对(409/确认)→ 幂等重放 → 分期发票 → 课消 → 赠课争议 → 跨校区转班 → 停课 → 投诉 → 退费试算与执行(金额精确断言)→ 状态联动(合同关闭/发票红冲/赠课作废/提成追回)→ 轨迹 26 类事件 → 校长视图 → 老师请假联动。

### 3. 手工 curl 示例

```bash
# 创建线索
curl -X POST localhost:8080/api/leads -H 'Content-Type: application/json' -H 'X-Actor-Id: CS001' -d '{
  "studentName":"张三","grade":"四年级","subject":"MATH","level":"INTERMEDIATE","textbook":"PEP",
  "availableSlots":["2026-10-06T16:00"],"channel":"大众点评","consultantId":"CS001",
  "campusId":"CAMP-ZGC","activityParticipated":true}'

# 试听匹配预览 -> 预约 -> 到课 -> 反馈 -> 签约 -> 课消 -> 退费 -> 轨迹
curl "localhost:8080/api/trials/match?leadId=<LEAD_ID>&slot=2026-10-06T16:00"
curl localhost:8080/api/students/<STU_ID>/trajectory        # 校长视角完整履约轨迹
curl localhost:8080/api/principal/overview                  # 经营漏斗与财务汇总
curl localhost:8080/api/consultants/CS001/performance       # 顾问业绩与提成
```

## 关键业务规则

**排课匹配**:老师需满足科目/水平/教材版本/校区,时段空闲(未被 1v1 占用、未请假、无常规班课);教室同时段空闲且无班级上课;随班试听占班级座位(容量=正式学员+试听占位)。多校区共享老师的时段全局互斥。

**课消规则**(绑定合同,可配置):`absentConsumes` 缺席是否消课(默认消)、`leaveAdvanceHours` 提前 N 小时请假不消课(默认 24)、`giftFirst` 是否先耗赠课(默认先正课)、`makeupAllowed` 是否允许补课。

**退费公式**(纯函数 `computeRefund`):
```
应退 = 实缴 − 已上正课结算 − 教材费(已发放) − 赠品价值(未退回) − 已耗赠课抵扣(政策开关)
  · 提前退费:已上正课按"原价"结算,差额即优惠回收
  · 投诉成立:视为机构责任,免除优惠回收与教材费扣减
  · 赠课无现金价值:剩余赠课作废;结果小于 0 时记学员倒欠
```
退费执行联动:合同关闭 → 剩余赠课作废 → 发票红冲 → 顾问提成冻结并按退费比例追回 → 学员退出班级 → 全程写入履约轨迹。

**承诺一致性核对**:签约时比对合同承诺与试听反馈(承诺班型 vs 老师建议班型、承诺老师是否满足授课条件、风险提醒是否已确认),不一致返回 409,需 `acknowledgeMismatch=true` 人工确认后签约,核对结果与承诺记录写入轨迹,避免报名后才发现承诺与试听反馈不一致。

**幂等**:`POST /api/contracts`、`POST /api/contracts/{id}/refund` 支持 `X-Idempotency-Key` 头,重复提交返回首次结果(响应头 `X-Idempotent-Replay: true`)。

## 配置

`src/main/resources/application.yml`:端口、Redis 地址、`app.business.commission-rate`(顾问提成 5%)、`app.business.channel-rates`(渠道费率)、`app.business.gift-consumed-deducts`(已耗赠课是否抵扣)、`app.redis.required`(置 true 时 Redis 不可用直接报错而非降级)。

## 已执行的验证结论

- `mvn test`:18 个测试全部通过(含真实 redis-server 的锁集成测试)。
- `./verify.sh`:应用连接本机 Redis 启动后,连续 3 次执行均 60/60 项通过;应用日志无锁降级记录,幂等键写入 Redis(`idem:*`)。
- 无 Redis 启动验证:`REDIS_PORT=6390`(无服务)启动,接口正常,日志出现"降级为进程内锁"WARN。
